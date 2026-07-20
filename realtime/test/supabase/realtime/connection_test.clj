(ns supabase.realtime.connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.core.client :as client]
            [supabase.realtime.connection :as conn]
            [supabase.realtime.protocol :as proto]))

(def test-client (client/make-client "https://abc.supabase.co" "anon-key"))

(defn recording-transport
  "Returns {:factory :sent :feed :open :close-args}. Pass `:factory` as
  `:transport-factory` in opts. Use `(open)` to fire on-open, `(feed s)`
  to inject a text frame."
  []
  (let [sent (atom [])
        handlers (atom nil)
        close-args (atom nil)
        transport (reify conn/Transport
                    (send-text [_ s] (swap! sent conj s) true)
                    (close! [_ code reason]
                      (reset! close-args [code reason])
                      :closed))
        factory (fn [_url _headers hs]
                  (reset! handlers hs)
                  transport)]
    {:factory factory
     :sent sent
     :open (fn [] ((:on-open @handlers)))
     :feed (fn [s] ((:on-text @handlers) s))
     :error (fn [e] ((:on-error @handlers) e))
     :close (fn [c r] ((:on-close @handlers) c r))
     :close-args close-args}))

(defn- connect-test
  ([] (connect-test {}))
  ([extra-opts]
   (let [rt (recording-transport)
         conn (conn/connect test-client
                            (merge {:transport-factory (:factory rt)
                                    :heartbeat-ms 60000
                                    :on-error (fn [_] nil)}
                                   extra-opts))]
     [conn rt])))

;; ---------------------------------------------------------------------------
;; URL + headers
;; ---------------------------------------------------------------------------

(deftest build-ws-url-uses-wss-and-defaults
  (let [u (conn/build-ws-url test-client)]
    (is (re-find #"^wss://" u))
    (is (re-find #"/realtime/v1/websocket\?" u))
    (is (re-find #"apikey=anon-key" u))
    (is (re-find #"vsn=1\.0\.0" u))))

(deftest build-ws-url-merges-extra-params
  (let [u (conn/build-ws-url test-client {"log_level" "info"})]
    (is (re-find #"log_level=info" u))))

;; ---------------------------------------------------------------------------
;; Buffering
;; ---------------------------------------------------------------------------

(deftest enqueue-buffers-when-not-open
  (let [[conn rt] (connect-test)]
    (try
      (is (false? (conn/enqueue! conn {:event "x" :topic "t" :ref "1" :payload {}})))
      (is (empty? @(:sent rt)))
      (is (= 1 (count (:send-buf @(:state conn)))))
      (finally (conn/disconnect conn)))))

(deftest enqueue-sends-after-open
  (let [[conn rt] (connect-test)]
    (try
      ((:open rt))
      (conn/enqueue! conn {:event "x" :topic "t" :ref "1" :payload {}})
      (is (= 1 (count @(:sent rt))))
      (finally (conn/disconnect conn)))))

(deftest open-flushes-send-buffer
  (let [[conn rt] (connect-test)]
    (try
      (conn/enqueue! conn {:event "a" :topic "t" :ref "1" :payload {}})
      (conn/enqueue! conn {:event "b" :topic "t" :ref "2" :payload {}})
      (is (empty? @(:sent rt)))
      ((:open rt))
      (is (= 2 (count @(:sent rt))))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; Channel state
;; ---------------------------------------------------------------------------

(deftest upsert-channel-initializes
  (let [[conn rt] (connect-test)]
    (try
      (let [cs (conn/upsert-channel! conn "realtime:r" {:private false})]
        (is (= :idle (:state cs)))
        (is (= [] (:bindings cs))))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; Dispatch: phx_reply for join
;; ---------------------------------------------------------------------------

(deftest dispatch-phx-reply-completes-join
  (let [[conn rt] (connect-test)]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {:private false})
      (conn/update-channel! conn "realtime:r" assoc :state :joining :join-ref "1")
      (conn/add-binding! conn "realtime:r"
                         {:type :postgres-changes
                          :filter {:event :insert :schema "public" :table "u"}
                          :callback identity})
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "phx_reply"
                    :ref "1"
                    :payload {:status "ok"
                              :response {:postgres_changes [{:id 42}]}}}))
      (let [cs (conn/channel-state conn "realtime:r")]
        (is (= :joined (:state cs)))
        (is (= 42 (:server-id (first (:bindings cs))))))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; Dispatch: postgres_changes routing
;; ---------------------------------------------------------------------------

(deftest postgres-changes-routes-by-server-id
  (let [[conn rt] (connect-test)
        captured (atom nil)]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {:private false})
      (conn/add-binding! conn "realtime:r"
                         {:type :postgres-changes
                          :filter {:event :insert :schema "public" :table "u"}
                          :server-id 7
                          :callback #(reset! captured %)})
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "postgres_changes"
                    :payload {:ids [7]
                              :data {:type "INSERT" :record {:id 1}}}}))
      (is (= {:type "INSERT" :record {:id 1}} @captured))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; Dispatch: broadcast routing
;; ---------------------------------------------------------------------------

(deftest broadcast-routes-by-event-name
  (let [[conn rt] (connect-test)
        captured (atom nil)]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {})
      (conn/add-binding! conn "realtime:r"
                         {:type :broadcast
                          :filter {:event "typing"}
                          :callback #(reset! captured %)})
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "broadcast"
                    :payload {:type "broadcast"
                              :event "typing"
                              :payload {:user "a"}}}))
      (is (= {:user "a"} @captured))
      (finally (conn/disconnect conn)))))

(deftest broadcast-wildcard-matches-all
  (let [[conn rt] (connect-test)
        hits (atom [])]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {})
      (conn/add-binding! conn "realtime:r"
                         {:type :broadcast
                          :filter {:event "*"}
                          :callback #(swap! hits conj %)})
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "broadcast"
                    :payload {:type "broadcast" :event "x" :payload {:n 1}}}))
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "broadcast"
                    :payload {:type "broadcast" :event "y" :payload {:n 2}}}))
      (is (= [{:n 1} {:n 2}] @hits))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; Dispatch: presence
;; ---------------------------------------------------------------------------

(deftest presence-state-stores-snapshot
  (let [[conn rt] (connect-test)
        sync-fired (atom nil)]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {})
      (conn/add-binding! conn "realtime:r"
                         {:type :presence
                          :filter {:event :sync}
                          :callback #(reset! sync-fired %)})
      ((:feed rt) (proto/encode
                   {:topic "realtime:r"
                    :event "presence_state"
                    :payload {:user1 [{:online_at 1}]}}))
      (is (= {:user1 [{:online_at 1}]} (:presence (conn/channel-state conn "realtime:r"))))
      (is (some? @sync-fired))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; phx_error invokes on-error
;; ---------------------------------------------------------------------------

(deftest phx-error-invokes-on-error
  (let [rt (recording-transport)
        errs (atom [])
        conn (conn/connect test-client
                           {:transport-factory (:factory rt)
                            :heartbeat-ms 60000
                            :on-error #(swap! errs conj %)})]
    (try
      ((:open rt))
      (conn/upsert-channel! conn "realtime:r" {})
      ((:feed rt) (proto/encode {:topic "realtime:r"
                                 :event "phx_error"
                                 :payload {:reason "nope"}}))
      (is (seq @errs))
      (is (= :channel-error (:supabase/code (first @errs))))
      (finally (conn/disconnect conn)))))

;; ---------------------------------------------------------------------------
;; disconnect cleans up
;; ---------------------------------------------------------------------------

(deftest disconnect-marks-closed-and-closes-socket
  (let [[conn rt] (connect-test)]
    ((:open rt))
    (conn/disconnect conn)
    (is (= :closed (:status @(:state conn))))
    (is (= [1000 "client closing"] @(:close-args rt)))))

;; ---------------------------------------------------------------------------
;; Reconnect
;; ---------------------------------------------------------------------------

(defn counting-transports
  "Like `recording-transport` but each factory call yields a fresh transport
  and increments `:calls`. `:fail-after` makes factory calls beyond N throw."
  ([] (counting-transports {}))
  ([{:keys [fail-after]}]
   (let [calls (atom 0)
         sent (atom [])
         handlers (atom nil)
         factory (fn [_url _headers hs]
                   (let [n (swap! calls inc)]
                     (when (and fail-after (> n fail-after))
                       (throw (ex-info "dial failed" {:attempt n})))
                     (reset! handlers hs)
                     (reify conn/Transport
                       (send-text [_ s] (swap! sent conj s) true)
                       (close! [_ _ _] :closed))))]
     {:factory factory
      :calls calls
      :sent sent
      :open (fn [] ((:on-open @handlers)))
      :close (fn [c r] ((:on-close @handlers) c r))})))

(defn- wait-for
  "Polls `pred` every 10ms up to `timeout-ms`. Returns last pred value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 10) (recur)))))))

(deftest unexpected-close-reconnects-and-rejoins
  (let [rt (counting-transports)
        conn (conn/connect test-client
                           {:transport-factory (:factory rt)
                            :heartbeat-ms 60000
                            :reconnect-after-ms (constantly 5)
                            :on-error (fn [_] nil)})]
    (try
      ((:open rt))
      ;; join a channel
      (conn/upsert-channel! conn "realtime:r" {:private false})
      (conn/update-channel! conn "realtime:r" assoc :state :joining :join-ref "1")
      (conn/dispatch-frame conn {:topic "realtime:r"
                                 :event "phx_reply"
                                 :ref "1"
                                 :payload {:status "ok"
                                           :response {:postgres_changes []}}})
      (is (= :joined (:state (conn/channel-state conn "realtime:r"))))
      ;; socket drops
      (let [sent-before (count @(:sent rt))]
        ((:close rt) 1006 "abnormal")
        (is (wait-for #(= 2 @(:calls rt)) 2000))
        (is (contains? #{:reconnecting :connecting} (:status @(:state conn))))
        ;; new socket opens — channel rejoins with a fresh phx_join
        ((:open rt))
        (is (= :open (:status @(:state conn))))
        (is (= :joining (:state (conn/channel-state conn "realtime:r"))))
        (is (> (count @(:sent rt)) sent-before))
        (let [rejoin (proto/parse-frame (last @(:sent rt)))]
          (is (= "phx_join" (:event rejoin)))
          (is (= "realtime:r" (:topic rejoin)))))
      (finally (conn/disconnect conn)))))

(deftest reconnect-gives-up-after-max-attempts
  (let [rt (counting-transports {:fail-after 1})
        errs (atom [])
        conn (conn/connect test-client
                           {:transport-factory (:factory rt)
                            :heartbeat-ms 60000
                            :reconnect-after-ms (constantly 1)
                            :max-reconnect-attempts 2
                            :on-error #(swap! errs conj %)})]
    (try
      ((:open rt))
      ((:close rt) 1006 "abnormal")
      (is (wait-for #(= :errored (:status @(:state conn))) 3000))
      (is (some #(= :reconnect-exhausted (:supabase/code %)) @errs))
      (finally (conn/disconnect conn)))))

(deftest disconnect-does-not-reconnect
  (let [rt (counting-transports)
        conn (conn/connect test-client
                           {:transport-factory (:factory rt)
                            :heartbeat-ms 60000
                            :reconnect-after-ms (constantly 1)
                            :on-error (fn [_] nil)})]
    ((:open rt))
    (conn/disconnect conn)
    (Thread/sleep 100)
    (is (= 1 @(:calls rt)))
    (is (= :closed (:status @(:state conn))))))
