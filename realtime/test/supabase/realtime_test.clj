(ns supabase.realtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]
            [supabase.realtime :as rt]
            [supabase.realtime.connection :as conn]
            [supabase.realtime.filters :as f]
            [supabase.realtime.protocol :as proto])
  (:import (java.util.concurrent CompletableFuture)))

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

(deftest connect-rejects-invalid-opts
  (is (error/anomaly? (rt/connect test-client {:bogus 1}))))

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

(deftest on-ignores-duplicate-postgres-binding
  (let [rt (recording-transport)
        errs (atom [])
        conn (rt/connect test-client {:transport-factory (:factory rt)
                                      :heartbeat-ms 60000
                                      :on-error #(swap! errs conj %)})]
    (try
      (let [ch (rt/channel conn "r")
            cb1 (fn [_] :first)
            cb2 (fn [_] :second)
            f {:event :insert :schema "public" :table "u"}]
        (is (= ch (rt/on ch :postgres-changes f cb1)))
        (is (= ch (rt/on ch :postgres-changes f cb2)))
        (let [bindings (:bindings (conn/channel-state conn "realtime:r"))]
          (is (= 1 (count bindings)))
          (is (= cb1 (:callback (first bindings)))))
        (is (= 1 (count @errs)))
        (is (= :duplicate-binding (:supabase/code (first @errs)))))
      (finally (rt/disconnect conn)))))

(deftest on-keeps-distinct-postgres-bindings
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")
            base {:event :insert :schema "public" :table "u"}]
        (rt/on ch :postgres-changes base identity)
        (rt/on ch :postgres-changes (assoc base :event :update) identity)
        (rt/on ch :postgres-changes (assoc base :schema "private") identity)
        (rt/on ch :postgres-changes (assoc base :table "v") identity)
        (rt/on ch :postgres-changes (assoc base :filter "id=eq.1") identity)
        (is (= 5 (count (:bindings (conn/channel-state conn "realtime:r")))))))))

(deftest on-duplicate-detection-normalizes-event
  (testing "* and :all collapse to the same filter"
    (with-conn
      (fn [conn _]
        (let [ch (rt/channel conn "r")]
          (rt/on ch :postgres-changes
                 {:event "*" :schema "public" :table "u"} identity)
          (rt/on ch :postgres-changes
                 {:event :all :schema "public" :table "u"} identity)
          (is (= 1 (count (:bindings (conn/channel-state conn "realtime:r"))))))))))

(deftest on-does-not-dedupe-broadcast-bindings
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")]
        (rt/on ch :broadcast {:event "x"} identity)
        (rt/on ch :broadcast {:event "x"} identity)
        (is (= 2 (count (:bindings (conn/channel-state conn "realtime:r")))))))))

(deftest on-serializes-filter-builder
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")]
        (rt/on ch :postgres-changes
               {:event :update :schema "public" :table "orders"
                :filter (-> (f/gt "amount" 100) (f/eq "status" "open"))}
               identity)
        (let [binding (first (:bindings (conn/channel-state conn "realtime:r")))]
          (is (= "amount=gt.100,status=eq.open"
                 (get-in binding [:filter :filter]))))))))

(deftest on-builder-and-string-filters-dedupe
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")]
        (rt/on ch :postgres-changes
               {:event :update :schema "public" :table "orders"
                :filter (f/gt "amount" 100)}
               identity)
        (rt/on ch :postgres-changes
               {:event :update :schema "public" :table "orders"
                :filter "amount=gt.100"}
               identity)
        (is (= 1 (count (:bindings (conn/channel-state conn "realtime:r")))))))))

(deftest on-duplicate-detection-includes-select
  (with-conn
    (fn [conn _]
      (let [ch (rt/channel conn "r")
            base {:event :insert :schema "public" :table "u"}]
        (rt/on ch :postgres-changes (assoc base :select ["id"]) identity)
        (rt/on ch :postgres-changes (assoc base :select ["id"]) identity)
        (rt/on ch :postgres-changes (assoc base :select ["id" "email"]) identity)
        (rt/on ch :postgres-changes base identity)
        (is (= 3 (count (:bindings (conn/channel-state conn "realtime:r")))))))))

(deftest on-select-flows-into-join-frame
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/on ch :postgres-changes
               {:event :insert :schema "public" :table "u"
                :select ["id" "email"]}
               identity)
        (rt/subscribe ch))
      (let [pg (first (get-in (last-sent-frame rt)
                              [:payload :config :postgres_changes]))]
        (is (= ["id" "email"] (:select pg)))))))

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

(deftest subscribe-uses-access-token-fn-in-join-frame
  (let [rt (recording-transport)
        conn (rt/connect test-client {:transport-factory (:factory rt)
                                      :heartbeat-ms 60000
                                      :access-token-fn (constantly "fn-token")})]
    (try
      ((:open rt))
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch))
      (is (= "fn-token" (get-in (last-sent-frame rt) [:payload :access_token])))
      (finally (rt/disconnect conn)))))

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
;; HTTP broadcast fallback
;; ---------------------------------------------------------------------------

(defn stub-transport
  "Captures the last HTTP request and returns the supplied response."
  [response]
  (let [captured (atom nil)
        t (reify transport/Transport
            (execute [_ req]
              (reset! captured req)
              response)
            (execute-async [_ req]
              (reset! captured req)
              (CompletableFuture/completedFuture response)))]
    [captured t]))

(defn- with-http-conn
  "Connects with the recording WS transport plus a stub HTTP transport.
  The socket is never opened, so status stays `:connecting`. `f` receives
  [conn rt captured-http-req]."
  [extra-opts f]
  (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})
        c (assoc test-client :transport t)
        rt (recording-transport)
        conn (rt/connect c (merge {:transport-factory (:factory rt)
                                   :heartbeat-ms 60000}
                                  extra-opts))]
    (try (f conn rt captured)
         (finally (rt/disconnect conn)))))

(deftest broadcast-falls-back-to-http-when-down-and-opted-in
  (with-http-conn {:http-fallback? true}
    (fn [conn rt captured]
      (let [ch (rt/channel conn "r")
            res (rt/broadcast ch "ping" {:n 1})]
        (is (= ch res))
        ;; nothing went over the socket or into the push buffer
        (is (empty? @(:sent rt)))
        (is (empty? (:push-buf (conn/channel-state conn "realtime:r"))))
        (is (= :post (:method @captured)))
        (is (= "https://abc.supabase.co/realtime/v1/api/broadcast" (:url @captured)))
        (is (= "Bearer anon-key" (get-in @captured [:headers "authorization"])))
        (is (= {:messages [{:topic "realtime:r" :event "ping" :payload {:n 1}}]}
               (proto/parse-frame (:body @captured))))))))

(deftest broadcast-buffers-when-fallback-not-enabled
  (with-http-conn {}
    (fn [conn _ captured]
      (let [ch (rt/channel conn "r")]
        (rt/broadcast ch "ping" {:n 1})
        (is (nil? @captured))
        (is (= 1 (count (:push-buf (conn/channel-state conn "realtime:r")))))))))

(deftest broadcast-with-ack-never-falls-back
  (with-http-conn {:http-fallback? true}
    (fn [conn _ captured]
      (let [ch (rt/channel conn "r" {:config {:broadcast {:ack true}}})]
        (rt/broadcast-with-ack ch "ping" {:n 1})
        (is (nil? @captured))
        (is (= 1 (count (:push-buf (conn/channel-state conn "realtime:r")))))))))

(deftest broadcast-fallback-returns-anomaly-on-http-failure
  (let [[_ t] (stub-transport {:status 500 :body "{\"error\":\"boom\"}" :headers {}})
        c (assoc test-client :transport t)
        rt (recording-transport)
        conn (rt/connect c {:transport-factory (:factory rt)
                            :heartbeat-ms 60000
                            :http-fallback? true})]
    (try
      (let [ch (rt/channel conn "r")
            res (rt/broadcast ch "ping" {})]
        (is (error/anomaly? res)))
      (finally (rt/disconnect conn)))))

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

(deftest set-auth-token-flows-into-later-joins
  (with-conn
    (fn [conn rt]
      ((:open rt))
      (rt/set-auth conn "user-jwt")
      (let [ch (rt/channel conn "r")]
        (rt/subscribe ch)
        (is (= "user-jwt" (get-in (last-sent-frame rt) [:payload :access_token])))))))

(deftest set-auth-nil-clears-stale-join-token
  (testing "after sign-out a later join carries no stale token"
    (with-conn
      (fn [conn rt]
        ((:open rt))
        (rt/set-auth conn "user-jwt")
        (let [ch (rt/channel conn "r")]
          (rt/subscribe ch)
          (is (= "user-jwt" (get-in (last-sent-frame rt) [:payload :access_token]))))
        (rt/set-auth conn nil)
        (let [ch2 (rt/channel conn "r2")]
          (rt/subscribe ch2)
          (is (= "anon-key" (get-in (last-sent-frame rt) [:payload :access_token]))))))))

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
