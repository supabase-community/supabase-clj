(ns supabase.realtime
  "Public API for Supabase Realtime: WebSocket-based postgres_changes,
  broadcast, and presence subscriptions.

  ## Quick start

      (require '[supabase.core.client :as sc]
               '[supabase.realtime :as rt])

      (def client (sc/make-client \"https://abc.supabase.co\" \"anon-key\"))
      (def conn   (rt/connect client {:on-error println}))

      (def ch (rt/channel conn \"room:lobby\"
                          {:config {:broadcast {:self false}}}))

      (rt/on ch :postgres-changes
             {:event :insert :schema \"public\" :table \"users\"}
             (fn [payload] (println \"row\" payload)))

      (rt/on ch :broadcast {:event \"typing\"}
             (fn [payload] (println \"typing\" payload)))

      (rt/subscribe ch)
      (rt/broadcast ch \"typing\" {:user \"alice\"})
      (rt/track    ch {:online_at (System/currentTimeMillis)})

      (rt/unsubscribe ch)
      (rt/disconnect conn)

  ## v0.1.0 scope

  In:  postgres_changes, broadcast send/receive, basic presence,
       manual `set-auth`, heartbeat, multi-channel per connection.

  Out (deferred): broadcast ack/wait_for_ack, HTTP fallback,
       binary v2 protocol."
  (:refer-clojure :exclude [not])
  (:require [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.realtime.connection :as conn]
            [supabase.realtime.filters :as filters]
            [supabase.realtime.protocol :as proto]
            [supabase.realtime.specs :as specs]))

;; ---------------------------------------------------------------------------
;; Connection lifecycle
;; ---------------------------------------------------------------------------

(defn connect
  "Opens a Realtime connection for `client`. See
  `supabase.realtime.connection/connect` for options."
  ([client] (connect client {}))
  ([client opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ConnectOpts opts)
       (conn/connect client opts))))

(defn disconnect
  "Closes the connection. Idempotent."
  [c]
  (conn/disconnect c))

;; ---------------------------------------------------------------------------
;; Channel construction
;; ---------------------------------------------------------------------------

(defn- valid-conn? [c]
  (and (map? c) (:state c) (:reconnect-exec c)))

(defn channel
  "Returns a channel value bound to `conn` + `topic`. No network I/O.

  `opts` may include `:config` (broadcast/presence/private). The channel
  is registered in connection state so subsequent `on` / `subscribe` calls
  can find it. Returns the channel map or an anomaly."
  ([conn topic] (channel conn topic {}))
  ([conn topic opts]
   (cond
     (clojure.core/not (valid-conn? conn))
     (error/anomaly :cognitect.anomalies/incorrect
                    {:cognitect.anomalies/message "Invalid connection"
                     :supabase/service :realtime})

     :else
     (or (specs/ensure-valid specs/ChannelOpts opts)
         (let [topic' (proto/realtime-topic topic)
               config (merge {:broadcast {:self false}
                              :presence  {:key ""}
                              :private   false}
                             (:config opts))]
           (conn/upsert-channel! conn topic' config)
           {:conn conn :topic topic'})))))

;; ---------------------------------------------------------------------------
;; Bindings
;; ---------------------------------------------------------------------------

(defn- normalize-postgres-filter [filter]
  (update filter :event #(if (= "*" %) :all (keyword %))))

(defn- serialize-builder
  "Converts a `supabase.realtime.filters` builder (a vector of condition
  strings) in a postgres_changes filter map into its wire string. Raw
  string filters pass through untouched."
  [filter]
  (if (vector? (:filter filter))
    (update filter :filter filters/build)
    filter))

(defn- select-key [filter]
  (some-> (:select filter) vec))

(defn- same-postgres-filter?
  "True when two postgres_changes filters would be collapsed into a single
  server subscription: equal event, schema, table, filter (absent and nil
  treated alike), and select column list. Mirrors realtime-js
  `isSamePostgresFilter`."
  [a b]
  (and (= ((juxt :event :schema :table :filter) a)
          ((juxt :event :schema :table :filter) b))
       (= (select-key a) (select-key b))))

(defn- duplicate-postgres-binding?
  "True when channel `topic` already holds a postgres_changes binding with
  an equivalent filter."
  [conn topic filter]
  (boolean
   (some #(and (= :postgres-changes (:type %))
               (same-postgres-filter? (:filter %) filter))
         (:bindings (conn/channel-state conn topic)))))

(defn on
  "Registers a binding on `ch`. Returns the channel value (for threading)
  or an anomaly on validation failure.

  Binding types:
    :postgres-changes  — filter `{:event :insert/:update/:delete/:all
                                  :schema \"public\" :table \"users\"
                                  :filter \"id=eq.42\" (optional)
                                  :select [\"id\" \"email\"] (optional)}`
    :broadcast         — filter `{:event \"typing\"}` (use `\"*\"` for all)
    :presence          — filter `{:event :sync | :join | :leave}`

  The postgres_changes `:filter` also accepts a builder from
  `supabase.realtime.filters` — it is serialized to its wire string at
  registration time:

      (rt/on ch :postgres-changes
             {:event :update :schema \"public\" :table \"orders\"
              :filter (-> (f/gt \"amount\" 100) (f/eq \"status\" \"open\"))}
             handle-order)

  `:select` narrows the payload to the listed columns (the primary key
  always comes through). Registering the same postgres_changes filter twice
  is a no-op (the duplicate is dropped and the connection's `:on-error` is
  notified with a `:duplicate-binding` anomaly): the server collapses
  identical filters into one subscription, so keeping the duplicate would
  desync client and server binding lists and fail `subscribe` with a
  mismatch.

  Must be called BEFORE `subscribe` for postgres_changes — server-side
  binding ids are correlated at join time."
  [ch binding-type filter callback]
  (let [filter (if (= :postgres-changes binding-type)
                 (serialize-builder filter)
                 filter)]
    (or (specs/ensure-filter binding-type filter)
        (let [filter' (if (= :postgres-changes binding-type)
                        (normalize-postgres-filter filter)
                        filter)
            binding {:type binding-type
                     :filter filter'
                     :callback callback}
              c (:conn ch)
              topic (:topic ch)]
          (if (and (= :postgres-changes binding-type)
                   (duplicate-postgres-binding? c topic filter'))
            (do (when-let [f (:on-error c)]
                  (f (error/anomaly :cognitect.anomalies/incorrect
                                    {:cognitect.anomalies/message
                                     (str "duplicate postgres-changes binding for "
                                          topic " ignored")
                                     :supabase/service :realtime
                                     :supabase/code :duplicate-binding
                                     :realtime/topic topic
                                     :realtime/filter filter'})))
                ch)
            (do (conn/add-binding! c topic binding)
                ch))))))

;; ---------------------------------------------------------------------------
;; Lifecycle ops on a channel
;; ---------------------------------------------------------------------------

(defn- channel-conn [ch] (:conn ch))

(defn- new-ref [ch]
  (let [s (:state (channel-conn ch))]
    (-> (swap! s update :ref-seq inc) :ref-seq str)))

(defn subscribe
  "Sends `phx_join` for `ch` and transitions to `:joining`. The channel
  receives `:joined` asynchronously when the server replies. Returns `ch`."
  [ch]
  (let [c (channel-conn ch)
        topic (:topic ch)
        cs (conn/channel-state c topic)
        ref (new-ref ch)
        token (conn/resolve-token c)
        frame (proto/join-frame ref topic (:config cs) (:bindings cs) token)]
    (conn/update-channel! c topic assoc :state :joining :join-ref ref)
    (conn/enqueue! c frame)
    ch))

(defn unsubscribe
  "Sends `phx_leave` and transitions to `:leaving`. Channel state is removed
  when the server acks."
  [ch]
  (let [c (channel-conn ch)
        topic (:topic ch)
        cs (conn/channel-state c topic)
        ref (new-ref ch)
        frame (proto/leave-frame ref (:join-ref cs) topic)]
    (conn/update-channel! c topic assoc :state :leaving)
    (conn/enqueue! c frame)
    ch))

(defn- push-or-buffer!
  "Sends `frame` immediately if the channel is `:joined`, otherwise buffers
  it on the per-channel push-buf to be flushed when the join ack arrives."
  [ch frame]
  (let [c (channel-conn ch)
        topic (:topic ch)
        cs (conn/channel-state c topic)]
    (if (= :joined (:state cs))
      (conn/enqueue! c frame)
      (conn/update-channel! c topic update :push-buf
                            (fnil conj []) (proto/encode frame)))
    ch))

(defn- http-broadcast!
  "POSTs a broadcast to the Realtime REST endpoint. Returns nil on success
  or the anomaly from `supabase.core.http/execute`."
  [client topic event payload]
  (let [resp (-> (http/request client)
                 (http/with-service-url :realtime-url "/api/broadcast")
                 (http/with-method :post)
                 (http/with-body {:messages [{:topic topic
                                              :event event
                                              :payload payload}]})
                 (http/execute))]
    (when (error/anomaly? resp) resp)))

(defn broadcast
  "Sends a broadcast message on `ch`. Buffered until the channel joins.
  Returns `ch`.

  When the connection was opened with `:http-fallback? true` and the socket
  is not `:open`, the broadcast is sent over HTTP POST to `/api/broadcast`
  instead of buffering — mirrors realtime-ex. In that case returns `ch` on
  HTTP success, or the anomaly from the failed request. Broadcasts sent via
  `broadcast-with-ack` never fall back; they buffer like today."
  [ch event payload]
  (let [c (channel-conn ch)
        topic (:topic ch)]
    (if (and (:http-fallback? c)
             (not= :open (:status @(:state c))))
      (or (http-broadcast! (:client c) topic event payload) ch)
      (let [cs (conn/channel-state c topic)
            ref (new-ref ch)
            frame (proto/broadcast-frame ref (:join-ref cs) topic event payload)]
        (push-or-buffer! ch frame)))))

;; ---------------------------------------------------------------------------
;; Broadcast acks
;; ---------------------------------------------------------------------------

(defn- new-ack-ref []
  (str "ack:" (random-uuid)))

(defn broadcast-with-ack
  "Sends a broadcast and returns the ack-ref (string) identifying it. The
  server replies with a `phx_reply` carrying that ref once the broadcast is
  accepted — requires `:config {:broadcast {:ack true}}` on the channel.

      (let [ack (rt/broadcast-with-ack ch \"typing\" {:user \"a\"})]
        (rt/wait-for-ack ch ack {:timeout-ms 3000}))

  Buffered like `broadcast` until the channel joins."
  [ch event payload]
  (let [c (channel-conn ch)
        topic (:topic ch)
        cs (conn/channel-state c topic)
        ack-ref (new-ack-ref)
        p (promise)
        frame (proto/broadcast-frame ack-ref (:join-ref cs) topic event payload)]
    (conn/update-channel! c topic assoc-in [:pending-acks ack-ref] p)
    (push-or-buffer! ch frame)
    ack-ref))

(defn wait-for-ack
  "Blocks up to `timeout-ms` (default 5000) for the server ack of `ack-ref`
  from `broadcast-with-ack`. Returns `:acknowledged`, or an anomaly:
  `:ack-timeout` when the wait expires, `:ack-not-found` for an unknown ref."
  ([ch ack-ref] (wait-for-ack ch ack-ref {}))
  ([ch ack-ref {:keys [timeout-ms] :or {timeout-ms 5000}}]
   (let [c (channel-conn ch)
         topic (:topic ch)
         p (get-in @(:state c) [:channels topic :pending-acks ack-ref])]
     (if-not p
       (error/anomaly :cognitect.anomalies/not-found
                      {:cognitect.anomalies/message "Unknown ack-ref"
                       :supabase/service :realtime
                       :supabase/code :ack-not-found
                       :realtime/ack-ref ack-ref})
       (let [v (deref p timeout-ms ::timeout)]
         (conn/update-channel! c topic update :pending-acks dissoc ack-ref)
         (if (= ::timeout v)
           (error/anomaly :cognitect.anomalies/busy
                          {:cognitect.anomalies/message "Broadcast ack timed out"
                           :supabase/service :realtime
                           :supabase/code :ack-timeout
                           :realtime/ack-ref ack-ref})
           :acknowledged))))))

(defn track
  "Sends a presence `track` message with `state`. Returns `ch`."
  [ch state]
  (let [topic (:topic ch)
        cs (conn/channel-state (channel-conn ch) topic)
        ref (new-ref ch)
        frame (proto/presence-track-frame ref (:join-ref cs) topic state)]
    (push-or-buffer! ch frame)))

(defn untrack
  "Sends a presence `untrack` message. Returns `ch`."
  [ch]
  (let [topic (:topic ch)
        cs (conn/channel-state (channel-conn ch) topic)
        ref (new-ref ch)
        frame (proto/presence-untrack-frame ref (:join-ref cs) topic)]
    (push-or-buffer! ch frame)))

(defn presence-state
  "Returns the latest presence map captured for `ch`. Empty if no
  `presence_state` received yet."
  [ch]
  (or (:presence (conn/channel-state (channel-conn ch) (:topic ch))) {}))

;; ---------------------------------------------------------------------------
;; Auth refresh
;; ---------------------------------------------------------------------------

(defn- send-access-token! [c topic token]
  (let [cs (conn/channel-state c topic)
        ref (-> (swap! (:state c) update :ref-seq inc) :ref-seq str)
        frame (proto/access-token-frame ref (:join-ref cs) topic token)]
    (conn/enqueue! c frame)))

(defn set-auth
  "Refreshes the auth token for a channel or every joined channel on a
  connection. Sends an `access_token` event per joined channel.

  The token is recorded on the connection and resolved into subsequent
  join/rejoin payloads (unless an `:access-token-fn` is set — its result
  always wins). Pass nil to clear it on sign-out, so a stale token no
  longer leaks into later join payloads."
  [ch-or-conn token]
  (cond
    ;; conn map
    (and (map? ch-or-conn) (:state ch-or-conn) (:reconnect-exec ch-or-conn))
    (let [c ch-or-conn
          channels (-> @(:state c) :channels)]
      (conn/set-auth-token! c token)
      (doseq [[topic cs] channels
              :when (= :joined (:state cs))]
        (send-access-token! c topic token))
      c)

    ;; channel map
    (and (map? ch-or-conn) (:conn ch-or-conn))
    (let [c (:conn ch-or-conn)
          topic (:topic ch-or-conn)
          cs (conn/channel-state c topic)]
      (conn/set-auth-token! c token)
      (when (= :joined (:state cs))
        (send-access-token! c topic token))
      ch-or-conn)

    :else
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "set-auth requires a channel or connection"
                    :supabase/service :realtime})))

;; ---------------------------------------------------------------------------
;; Re-exports — postgres_changes filter builder (supabase.realtime.filters)
;; ---------------------------------------------------------------------------

(def eq         filters/eq)
(def neq        filters/neq)
(def lt         filters/lt)
(def lte        filters/lte)
(def gt         filters/gt)
(def gte        filters/gte)
(def in         filters/in)
(def like       filters/like)
(def ilike      filters/ilike)
(def match      filters/match)
(def imatch     filters/imatch)
(def is         filters/is)
(def isdistinct filters/isdistinct)
(def not        filters/not)
(def build      filters/build)
