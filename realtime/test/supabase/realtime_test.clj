(ns supabase.realtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  [f]
  (let [rt (recording-transport)
        conn (rt/connect test-client {:transport-factory (:factory rt)
                                      :heartbeat-ms 60000})]
    (try (f conn rt)
         (finally (rt/disconnect conn)))))

(defn- last-sent-frame [rt]
  (proto/parse-frame (last @(:sent rt))))

;; ---------------------------------------------------------------------------
;; connect validation
;; ---------------------------------------------------------------------------

(deftest connect-invalid-client-is-anomaly
  (is (error/anomaly? (rt/connect {}))))

(deftest connect-returns-conn-map
  (with-conn
    (fn [conn _]
      (is (map? conn))
      (is (some? (:transport @(:state conn))))
      (is (some? (:state conn))))))

;; ---------------------------------------------------------------------------
;; channel construction
;; ---------------------------------------------------------------------------

(deftest channel-prefixes-topic
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "room:lobby")]
        (is (= "realtime:room:lobby" (:topic ch)))))))

(deftest channel-rejects-invalid-conn
  (is (error/anomaly? (rt/channel {} "t"))))

(deftest channel-rejects-bad-opts
  (with-conn
    (fn [conn _]
      (is (error/anomaly? (rt/channel conn "t" {:bogus 1}))))))

;; ---------------------------------------------------------------------------
;; on bindings
;; ---------------------------------------------------------------------------

(deftest on-validates-postgres-filter
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")]
        (is (error/anomaly? (rt/on ch :postgres-changes {} identity)))))))

(deftest on-appends-binding
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")]
        (rt/on ch :broadcast {:event "x"} identity)
        (rt/on ch :postgres-changes
               {:event :insert :schema "public" :table "u"} identity)
        (let [bindings (:bindings (conn/channel-state conn "realtime:r"))]
          (is (= 2 (count bindings)))
          (is (= :broadcast (:type (first bindings)))))))))

;; ---------------------------------------------------------------------------
;; subscribe sends phx_join
;; ---------------------------------------------------------------------------

(deftest subscribe-sends-join-frame
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/on ch :postgres-changes
               {:event :insert :schema "public" :table "u"} identity)
        (rt/subscribe ch))
      (let [f (last-sent-frame rt)]
        (is (= "phx_join" (:event f)))
        (is (= "realtime:r" (:topic f)))
        (is (= 1 (count (get-in f [:payload :config :postgres_changes]))))
        (is (= "anon-key" (get-in f [:payload :access_token])))))))

;; ---------------------------------------------------------------------------
;; broadcast send + buffering
;; ---------------------------------------------------------------------------

(deftest broadcast-buffers-until-joined
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch)
        (rt/broadcast ch "ping" {:n 1})
        ;; not yet joined — push buffered, only join frame sent
        (is (= 1 (count @(:sent rt))))
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "phx_reply"
                      :ref "1"
                      :payload {:status "ok"
                                :response {:postgres_changes []}}}))
        ;; ack flushes push-buf
        (is (= 2 (count @(:sent rt))))
        (let [f (last-sent-frame rt)]
          (is (= "broadcast" (:event f)))
          (is (= "ping" (get-in f [:payload :event]))))))))

;; ---------------------------------------------------------------------------
;; postgres_changes end-to-end via api
;; ---------------------------------------------------------------------------

(deftest postgres-changes-callback-fires
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [hit (atom nil)
            ch (rt/channel conn "r")]
        (rt/on ch :postgres-changes
               {:event :insert :schema "public" :table "u"}
               #(reset! hit %))
        (rt/subscribe ch)
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "phx_reply"
                      :ref "1"
                      :payload {:status "ok"
                                :response {:postgres_changes [{:id 99}]}}}))
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "postgres_changes"
                      :payload {:ids [99]
                                :data {:type "INSERT" :record {:id 1}}}}))
        (is (= {:type "INSERT" :record {:id 1}} @hit))))))

;; ---------------------------------------------------------------------------
;; set-auth fan-out
;; ---------------------------------------------------------------------------

(deftest set-auth-on-conn-fans-out-to-joined-channels
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch)
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "phx_reply"
                      :ref "1"
                      :payload {:status "ok"
                                :response {:postgres_changes []}}}))
        (let [n-before (count @(:sent rt))]
          (rt/set-auth conn "new-token")
          (is (= (inc n-before) (count @(:sent rt))))
          (let [f (last-sent-frame rt)]
            (is (= "access_token" (:event f)))
            (is (= "new-token" (get-in f [:payload :access_token])))))))))

;; ---------------------------------------------------------------------------
;; presence-state read
;; ---------------------------------------------------------------------------

(deftest presence-state-reads-latest
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch)
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "presence_state"
                      :payload {:userA [{:online_at 123}]}}))
        (is (= {:userA [{:online_at 123}]} (rt/presence-state ch)))))))

;; ---------------------------------------------------------------------------
;; broadcast acks
;; ---------------------------------------------------------------------------

(defn- join-channel! [rt ch]
  (rt/subscribe ch)
  ((:feed rt) (proto/encode
               {:topic (:topic ch)
                :event "phx_reply"
                :ref "1"
                :payload {:status "ok"
                          :response {:postgres_changes []}}})))

(deftest broadcast-with-ack-round-trip
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r" {:config {:broadcast {:ack true}}})]
        (join-channel! rt ch)
        (let [ack-ref (rt/broadcast-with-ack ch "ping" {:n 1})]
          (is (string? ack-ref))
          (is (str/starts-with? ack-ref "ack:"))
          (let [f (last-sent-frame rt)]
            (is (= "broadcast" (:event f)))
            (is (= ack-ref (:ref f))))
          ;; server acks with a phx_reply carrying the ack ref
          ((:feed rt) (proto/encode
                       {:topic (:topic ch)
                        :event "phx_reply"
                        :ref ack-ref
                        :payload {:status "ok" :response {}}}))
          (is (= :acknowledged (rt/wait-for-ack ch ack-ref {:timeout-ms 500}))))))))

(deftest wait-for-ack-times-out
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (join-channel! rt ch)
        (let [ack-ref (rt/broadcast-with-ack ch "ping" {})]
          (let [res (rt/wait-for-ack ch ack-ref {:timeout-ms 50})]
            (is (error/anomaly? res))
            (is (= :ack-timeout (:supabase/code res)))))))))

(deftest wait-for-ack-unknown-ref
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")
            res (rt/wait-for-ack ch "ack:nope" {:timeout-ms 10})]
        (is (error/anomaly? res))
        (is (= :ack-not-found (:supabase/code res)))))))

;; ---------------------------------------------------------------------------
;; unsubscribe removes channel after ack
;; ---------------------------------------------------------------------------

(deftest unsubscribe-removes-channel-after-leave-ack
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch)
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "phx_reply"
                      :ref "1"
                      :payload {:status "ok"
                                :response {:postgres_changes []}}}))
        (rt/unsubscribe ch)
        ((:feed rt) (proto/encode
                     {:topic "realtime:r"
                      :event "phx_reply"
                      :ref "2"
                      :payload {:status "ok" :response {}}}))
        (is (nil? (conn/channel-state conn "realtime:r")))))))
