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
       binary v2 protocol, auto token refresh."
  (:require [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.realtime.connection :as conn]
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
     (not (valid-conn? conn))
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

(defn on
  "Registers a binding on `ch`. Returns the channel value (for threading)
  or an anomaly on validation failure.

  Binding types:
    :postgres-changes  — filter `{:event :insert/:update/:delete/:all
                                  :schema \"public\" :table \"users\"
                                  :filter \"id=eq.42\" (optional)}`
    :broadcast         — filter `{:event \"typing\"}` (use `\"*\"` for all)
    :presence          — filter `{:event :sync | :join | :leave}`

  Must be called BEFORE `subscribe` for postgres_changes — server-side
  binding ids are correlated at join time."
  [ch binding-type filter callback]
  (or (specs/ensure-filter binding-type filter)
      (let [filter' (if (= :postgres-changes binding-type)
                      (normalize-postgres-filter filter)
                      filter)
            binding {:type binding-type
                     :filter filter'
                     :callback callback}]
        (conn/add-binding! (:conn ch) (:topic ch) binding)
        ch)))

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
        token (or (:access-token (:client c)) (:api-key (:client c)))
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

(defn broadcast
  "Sends a broadcast message on `ch`. Buffered until the channel joins.
  Returns `ch`."
  [ch event payload]
  (let [topic (:topic ch)
        cs (conn/channel-state (channel-conn ch) topic)
        ref (new-ref ch)
        frame (proto/broadcast-frame ref (:join-ref cs) topic event payload)]
    (push-or-buffer! ch frame)))

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
  connection. Sends an `access_token` event per joined channel."
  [ch-or-conn token]
  (cond
    ;; conn map
    (and (map? ch-or-conn) (:state ch-or-conn) (:reconnect-exec ch-or-conn))
    (let [c ch-or-conn
          channels (-> @(:state c) :channels)]
      (doseq [[topic cs] channels
              :when (= :joined (:state cs))]
        (send-access-token! c topic token))
      c)

    ;; channel map
    (and (map? ch-or-conn) (:conn ch-or-conn))
    (let [c (:conn ch-or-conn)
          topic (:topic ch-or-conn)
          cs (conn/channel-state c topic)]
      (when (= :joined (:state cs))
        (send-access-token! c topic token))
      ch-or-conn)

    :else
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "set-auth requires a channel or connection"
                    :supabase/service :realtime})))
