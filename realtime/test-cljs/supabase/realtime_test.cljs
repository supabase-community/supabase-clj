(ns supabase.realtime-test
  (:require [cljs.test :refer [deftest is testing async]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.realtime :as rt]
            [supabase.realtime.connection :as conn]
            [supabase.realtime.protocol :as proto]))

(def test-client (client/make-client "https://abc.supabase.co" "anon-key"))

(defn recording-transport []
  (let [sent (atom [])
        handlers (atom nil)
        transport (reify conn/Transport
                    (send-text [_ s] (swap! sent conj s) true)
                    (close! [_ _ _] :closed))
        factory (fn [_url _headers hs]
                  (reset! handlers hs)
                  transport)]
    {:factory factory
     :sent sent
     :open (fn [] ((:on-open @handlers)))
     :feed (fn [s] ((:on-text @handlers) s))}))

(defn- with-conn
  "Calls `(f conn rt)` with a recording-transport connection, disconnecting
  after."
  [f]
  (let [rt (recording-transport)
        conn (rt/connect test-client {:transport-factory (:factory rt)
                                      :heartbeat-ms 60000})]
    (try (f conn rt)
         (finally (rt/disconnect conn)))))

(defn- last-sent-frame [rt]
  (proto/parse-frame (last @(:sent rt))))

(deftest connect-test
  (testing "returns a conn map with state and transport"
    (with-conn
      (fn [conn _]
        (is (map? conn))
        (is (some? (:transport @(:state conn)))))))
  (testing "invalid client yields an anomaly"
    (is (error/anomaly? (rt/connect {})))))

(deftest channel-test
  (testing "prefixes the topic"
    (with-conn
      (fn [conn _]
        (is (= "realtime:room:lobby" (:topic (rt/channel conn "room:lobby")))))))
  (testing "rejects invalid conn"
    (is (error/anomaly? (rt/channel {} "t")))))

(deftest subscribe-join-flow-test
  (with-conn
    (fn [conn rt]
      (let [ch (rt/channel conn "room:lobby")
            received (atom [])]
        (rt/on ch :postgres-changes
               {:event :insert :schema "public" :table "users"}
               (fn [payload] (swap! received conj payload)))
        ((:open rt))
        (rt/subscribe ch)
        (testing "sends a phx_join with the binding"
          (let [frame (last-sent-frame rt)]
            (is (= "phx_join" (:event frame)))
            (is (= "realtime:room:lobby" (:topic frame)))
            (is (= [{:event "insert" :schema "public" :table "users"}]
                   (get-in frame [:payload :config :postgres_changes])))))
        (testing "join ack flips the channel to :joined"
          (let [ref (:join-ref (conn/channel-state conn "realtime:room:lobby"))]
            ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                       :event "phx_reply"
                                       :ref ref
                                       :payload {:status "ok"
                                                 :response {:postgres_changes [{:id 7}]}}}))
            (is (= :joined (:state (conn/channel-state conn "realtime:room:lobby"))))))
        (testing "postgres_changes dispatches to the binding callback"
          ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                     :event "postgres_changes"
                                     :payload {:ids [7]
                                               :data {:type "INSERT"
                                                      :record {:id 1}}}}))
          (is (= [{:type "INSERT" :record {:id 1}}] @received)))))))

(deftest broadcast-test
  (with-conn
    (fn [conn rt]
      (let [ch (rt/channel conn "room:lobby")]
        ((:open rt))
        (rt/subscribe ch)
        (let [ref (:join-ref (conn/channel-state conn "realtime:room:lobby"))]
          ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                     :event "phx_reply"
                                     :ref ref
                                     :payload {:status "ok" :response {}}})))
        (rt/broadcast ch "typing" {:user "alice"})
        (let [frame (last-sent-frame rt)]
          (is (= "broadcast" (:event frame)))
          (is (= "typing" (get-in frame [:payload :event])))
          (is (= {:user "alice"} (get-in frame [:payload :payload]))))))))

(deftest broadcast-with-ack-test
  (async done
         (with-conn
           (fn [conn rt]
             (let [ch (rt/channel conn "room:lobby" {:config {:broadcast {:ack true}}})]
               ((:open rt))
               (rt/subscribe ch)
               (let [ref (:join-ref (conn/channel-state conn "realtime:room:lobby"))]
                 ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                            :event "phx_reply"
                                            :ref ref
                                            :payload {:status "ok" :response {}}})))
               (let [ack (rt/broadcast-with-ack ch "typing" {:user "a"})]
                 ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                            :event "phx_reply"
                                            :ref ack
                                            :payload {:status "ok" :response {}}}))
                 (-> (rt/wait-for-ack ch ack {:timeout-ms 500})
                     (.then (fn [v]
                              (is (= :acknowledged v))
                              (done)))
                     (.catch (fn [e] (is false (str e)) (done))))))))))

(deftest wait-for-ack-timeout-test
  (async done
         (with-conn
           (fn [conn _]
             (let [ch (rt/channel conn "room:lobby")
                   ;; never joined, so the broadcast buffers and the ack never lands
                   ack (rt/broadcast-with-ack ch "typing" {:user "a"})]
               (-> (rt/wait-for-ack ch ack {:timeout-ms 20})
                   (.then (fn [v]
                            (is (error/anomaly? v))
                            (is (= :ack-timeout (:supabase/code v)))
                            (done)))
                   (.catch (fn [e] (is false (str e)) (done)))))))))

(deftest presence-test
  (with-conn
    (fn [conn rt]
      (let [ch (rt/channel conn "room:lobby")
            syncs (atom [])]
        (rt/on ch :presence {:event :sync}
               (fn [payload] (swap! syncs conj payload)))
        (rt/subscribe ch)
        ((:feed rt) (proto/encode {:topic "realtime:room:lobby"
                                   :event "presence_state"
                                   :payload {"u1" {:metas [{:phx_ref "r1"}]}}}))
        (is (= {:u1 {:metas [{:phx_ref "r1"}]}} (rt/presence-state ch)))
        (is (= 1 (count @syncs)))))))

(deftest protocol-roundtrip-test
  (testing "encode/parse-frame round-trips"
    (let [frame {:topic "realtime:x" :event "phx_join" :payload {:config {}} :ref "1"}]
      (is (= frame (proto/parse-frame (proto/encode frame))))))
  (testing "topics get the realtime: prefix"
    (is (= "realtime:a" (proto/realtime-topic "a")))
    (is (= "realtime:a" (proto/realtime-topic "realtime:a")))))

(deftest filters-test
  (is (= "amount=gt.100,status=eq.open"
         (rt/build (-> (rt/gt "amount" 100)
                       (rt/eq "status" "open")))))
  (is (= "status=not.in.(draft,archived)"
         (rt/build (rt/not "status" :in ["draft" "archived"])))))
