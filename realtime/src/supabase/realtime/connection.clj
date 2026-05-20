(ns supabase.realtime.connection
  "WebSocket connection lifecycle for Supabase Realtime.

  Holds a single hato-backed WebSocket per `connect` call. State lives in
  one atom; mutation goes through `swap!`; user callbacks run outside the
  swap to avoid running user code under contention.

  The `Transport` protocol is the test seam — tests substitute a recording
  transport (see `realtime_test`) without redefining hato internals."
  (:require [clojure.string :as str]
            [hato.websocket :as ws]
            [supabase.core.error :as error]
            [supabase.realtime.protocol :as proto])
  (:import (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

;; ---------------------------------------------------------------------------
;; Transport protocol
;; ---------------------------------------------------------------------------

(defprotocol Transport
  (send-text [this text]
    "Sends `text` as a single text frame. Returns truthy on success or
     throws/returns an exception object on failure.")
  (close! [this code reason]
    "Initiates an orderly close of the underlying connection."))

;; ---------------------------------------------------------------------------
;; URL + headers
;; ---------------------------------------------------------------------------

(defn- http->ws [url]
  (-> url
      (str/replace-first #"^http://" "ws://")
      (str/replace-first #"^https://" "wss://")))

(defn build-ws-url
  "Returns the full Realtime WebSocket URL for `client` with `params`
  (default: `apikey` + `vsn`) merged into the query string."
  ([client] (build-ws-url client {}))
  ([client extra-params]
   (let [base (str (http->ws (:realtime-url client)) "/websocket")
         params (merge {"apikey" (:api-key client) "vsn" "1.0.0"} extra-params)
         qs (->> params
                 (map (fn [[k v]] (str (name k) "=" v)))
                 (str/join "&"))]
     (str base "?" qs))))

(defn- upgrade-headers [client]
  (let [token (or (:access-token client) (:api-key client))]
    {"Authorization" (str "Bearer " token)
     "X-Client-Info" (str "supabase-realtime-clj/0.1.0")}))

;; ---------------------------------------------------------------------------
;; hato Transport impl
;; ---------------------------------------------------------------------------

(defn- buffering-on-message
  "Wraps `dispatch` with logic to reassemble partial text frames."
  [dispatch]
  (let [buf (StringBuilder.)]
    (fn [_ws data last?]
      (.append buf (str data))
      (when last?
        (let [text (.toString buf)]
          (.setLength buf 0)
          (try (dispatch text)
               (catch Throwable t
                 (dispatch ::error t))))))))

(defn ws-transport
  "Opens a hato WebSocket to `url` with the given upgrade `headers` and
  `handlers` map. Returns a reified `Transport`.

  `handlers` keys:
    :on-open    (fn [])
    :on-text    (fn [text])
    :on-close   (fn [code reason])
    :on-error   (fn [throwable])

  Partial text frames are buffered until `last?` is true."
  [url headers handlers]
  (let [{:keys [on-open on-text on-close on-error]} handlers
        socket @(ws/websocket url
                              {:headers headers
                               :on-open    (fn [_ws] (when on-open (on-open)))
                               :on-message (buffering-on-message
                                            (fn [text]
                                              (when on-text (on-text text))))
                               :on-close   (fn [_ws code reason]
                                             (when on-close (on-close code reason)))
                               :on-error   (fn [_ws err]
                                             (when on-error (on-error err)))})]
    (reify Transport
      (send-text [_ text] (ws/send! socket text) true)
      (close! [_ code reason] (ws/close! socket (or code 1000) (or reason ""))))))

;; ---------------------------------------------------------------------------
;; State helpers
;; ---------------------------------------------------------------------------

(defn- initial-state []
  {:status   :connecting
   :ref-seq  0
   :channels {}
   :send-buf []})

(defn- new-ref [state-atom]
  (-> (swap! state-atom update :ref-seq inc) :ref-seq str))

(defn- open? [state-atom]
  (= :open (:status @state-atom)))

;; ---------------------------------------------------------------------------
;; Send + buffering
;; ---------------------------------------------------------------------------

(defn enqueue!
  "Buffers a frame if the socket isn't open yet, otherwise sends it now.
  Returns true on send, false on buffer."
  [conn frame]
  (let [text (proto/encode frame)]
    (if (open? (:state conn))
      (do (send-text (:transport conn) text) true)
      (do (swap! (:state conn) update :send-buf conj text) false))))

(defn- flush-send-buf! [conn]
  (let [[old _] (swap-vals! (:state conn) assoc :send-buf [])]
    (doseq [text (:send-buf old)]
      (try (send-text (:transport conn) text)
           (catch Throwable t
             (when-let [f (:on-error conn)] (f (error/from-exception t :realtime))))))))

;; ---------------------------------------------------------------------------
;; Channel-state helpers
;; ---------------------------------------------------------------------------

(defn channel-state
  "Reads the current channel-state map for `topic`, or nil."
  [conn topic]
  (get-in @(:state conn) [:channels topic]))

(defn upsert-channel!
  "Initializes channel-state for `topic` if absent. Returns the updated
  channel-state."
  [conn topic config]
  (let [s (swap! (:state conn) update-in [:channels topic]
                 (fn [cs]
                   (or cs {:topic topic
                           :state :idle
                           :config config
                           :join-ref nil
                           :bindings []
                           :presence {}
                           :push-buf []})))]
    (get-in s [:channels topic])))

(defn update-channel!
  "Applies `f` (with extra `args`) to the channel-state at `topic`. Returns
  the new channel-state, or nil if the topic isn't tracked."
  [conn topic f & args]
  (let [s (swap! (:state conn) update-in [:channels topic]
                 (fn [cs] (when cs (apply f cs args))))]
    (get-in s [:channels topic])))

(defn add-binding!
  "Appends a binding to channel `topic`. Returns the new channel-state or nil."
  [conn topic binding]
  (update-channel! conn topic update :bindings (fnil conj []) binding))

(defn remove-channel!
  "Drops `topic` from the channels map."
  [conn topic]
  (swap! (:state conn) update :channels dissoc topic)
  nil)

;; ---------------------------------------------------------------------------
;; Inbound frame dispatch
;; ---------------------------------------------------------------------------

(defn- safe-call [f & args]
  (try (apply f args)
       (catch Throwable _ nil)))

(defn- handle-join-reply
  "Server ack of a `phx_join`: flip channel to :joined, capture
  server-assigned postgres_changes ids, flush push-buf."
  [conn frame]
  (let [topic (:topic frame)
        payload (:payload frame)
        response (:response payload)
        pg-ids (mapv :id (:postgres_changes response))
        cs (update-channel! conn topic
                            (fn [cs]
                              (let [assigned (atom 0)
                                    bindings (mapv (fn [b]
                                                     (if (= :postgres-changes (:type b))
                                                       (let [idx @assigned]
                                                         (swap! assigned inc)
                                                         (if (< idx (count pg-ids))
                                                           (assoc b :server-id (nth pg-ids idx))
                                                           b))
                                                       b))
                                                   (:bindings cs))]
                                (assoc cs :state :joined :bindings bindings))))]
    (when cs
      ;; flush push buffer
      (let [[old _] (swap-vals! (:state conn)
                                update-in [:channels topic :push-buf]
                                (constantly []))
            pushes (get-in old [:channels topic :push-buf])]
        (doseq [text pushes]
          (try (send-text (:transport conn) text)
               (catch Throwable t
                 (when-let [f (:on-error conn)]
                   (f (error/from-exception t :realtime)))))))
      cs)))

(defn- handle-join-error
  [conn frame]
  (let [topic (:topic frame)
        payload (:payload frame)]
    (update-channel! conn topic assoc :state :errored)
    (when-let [f (:on-error conn)]
      (f (error/anomaly :cognitect.anomalies/fault
                        {:cognitect.anomalies/message "Channel join failed"
                         :supabase/service :realtime
                         :supabase/code :join-failed
                         :realtime/topic topic
                         :realtime/payload payload})))))

(defn- handle-phx-reply [conn frame]
  (let [payload (:payload frame)
        status (:status payload)
        topic (:topic frame)
        cs (channel-state conn topic)]
    (cond
      ;; reply to a phx_join we sent
      (and cs (= :joining (:state cs)) (= "ok" status))
      (handle-join-reply conn frame)

      (and cs (= :joining (:state cs)))
      (handle-join-error conn frame)

      ;; reply to a phx_leave
      (and cs (= :leaving (:state cs)))
      (do (remove-channel! conn topic) nil)

      :else nil)))

(defn- dispatch-binding-callbacks
  "Invokes every binding callback whose filter matches `predicate-fn`."
  [bindings binding-type predicate-fn payload]
  (doseq [b bindings
          :when (and (= binding-type (:type b))
                     (predicate-fn b))]
    (when-let [cb (:callback b)]
      (safe-call cb payload))))

(defn- handle-postgres-changes [conn frame]
  (let [topic (:topic frame)
        payload (:payload frame)
        ids (set (get-in payload [:ids]))
        data (:data payload)
        bindings (:bindings (channel-state conn topic))]
    (dispatch-binding-callbacks
     bindings :postgres-changes
     (fn [b]
       (or (empty? ids)
           (contains? ids (:server-id b))))
     data)))

(defn- handle-broadcast [conn frame]
  (let [topic (:topic frame)
        inner (:payload frame)
        event (:event inner)
        inner-payload (:payload inner)
        bindings (:bindings (channel-state conn topic))]
    (dispatch-binding-callbacks
     bindings :broadcast
     (fn [b]
       (let [pattern (get-in b [:filter :event])]
         (or (= "*" pattern) (= event pattern))))
     inner-payload)))

(defn- presence-event-binding-match? [event b]
  (let [pattern (get-in b [:filter :event])]
    (or (= :sync pattern) (= pattern event))))

(defn- handle-presence-state [conn frame]
  (let [topic (:topic frame)
        payload (:payload frame)
        bindings (:bindings (channel-state conn topic))]
    (update-channel! conn topic assoc :presence payload)
    (dispatch-binding-callbacks
     bindings :presence
     (partial presence-event-binding-match? :sync)
     payload)))

(defn- merge-presence-diff [presence joins leaves]
  (as-> presence p
    (apply dissoc p (map name (keys leaves)))
    (merge p joins)))

(defn- handle-presence-diff [conn frame]
  (let [topic (:topic frame)
        payload (:payload frame)
        joins (:joins payload)
        leaves (:leaves payload)
        bindings (:bindings (channel-state conn topic))]
    (update-channel! conn topic update :presence merge-presence-diff joins leaves)
    (when (seq joins)
      (dispatch-binding-callbacks
       bindings :presence
       (partial presence-event-binding-match? :join)
       joins))
    (when (seq leaves)
      (dispatch-binding-callbacks
       bindings :presence
       (partial presence-event-binding-match? :leave)
       leaves))))

(defn- handle-phx-error [conn frame]
  (let [topic (:topic frame)]
    (update-channel! conn topic assoc :state :errored)
    (when-let [f (:on-error conn)]
      (f (error/anomaly :cognitect.anomalies/fault
                        {:cognitect.anomalies/message "Channel error"
                         :supabase/service :realtime
                         :supabase/code :channel-error
                         :realtime/topic topic
                         :realtime/payload (:payload frame)})))))

(defn- handle-phx-close [conn frame]
  (remove-channel! conn (:topic frame)))

(defn dispatch-frame
  "Routes a decoded inbound `frame` to the matching state-update + user
  callbacks. Pure mechanism; user callbacks run after state updates."
  [conn frame]
  (case (proto/event-kind frame)
    :heartbeat-reply nil
    :phx-reply       (handle-phx-reply conn frame)
    :phx-error       (handle-phx-error conn frame)
    :phx-close       (handle-phx-close conn frame)
    :postgres-changes (handle-postgres-changes conn frame)
    :broadcast       (handle-broadcast conn frame)
    :presence-state  (handle-presence-state conn frame)
    :presence-diff   (handle-presence-diff conn frame)
    :system          nil
    :unknown         nil))

;; ---------------------------------------------------------------------------
;; Heartbeat
;; ---------------------------------------------------------------------------

(defn- start-heartbeat!
  ^ScheduledExecutorService [conn interval-ms]
  (let [exec (Executors/newSingleThreadScheduledExecutor
              (reify java.util.concurrent.ThreadFactory
                (newThread [_ r]
                  (doto (Thread. r "supabase-realtime-heartbeat")
                    (.setDaemon true)))))]
    (.scheduleAtFixedRate exec
                          (fn []
                            (try
                              (when (open? (:state conn))
                                (let [ref (new-ref (:state conn))
                                      frame (proto/heartbeat-frame ref)]
                                  (send-text (:transport conn) (proto/encode frame))))
                              (catch Throwable t
                                (when-let [f (:on-error conn)]
                                  (f (error/from-exception t :realtime))))))
                          interval-ms interval-ms TimeUnit/MILLISECONDS)
    exec))

;; ---------------------------------------------------------------------------
;; connect / disconnect
;; ---------------------------------------------------------------------------

(defn- on-text-handler [conn-promise]
  (fn [text]
    (when-let [conn @conn-promise]
      (let [frame (try (proto/parse-frame text)
                       (catch Throwable t
                         (when-let [f (:on-error conn)]
                           (f (error/from-exception t :realtime)))
                         nil))]
        (when frame
          (dispatch-frame conn frame))))))

(defn- on-open-handler [conn-promise]
  (fn []
    (when-let [conn @conn-promise]
      (swap! (:state conn) assoc :status :open)
      (flush-send-buf! conn))))

(defn- on-close-handler [conn-promise]
  (fn [_code _reason]
    (when-let [conn @conn-promise]
      (swap! (:state conn) assoc :status :closed))))

(defn- on-error-handler [conn-promise]
  (fn [err]
    (when-let [conn @conn-promise]
      (swap! (:state conn) assoc :status :errored)
      (when-let [f (:on-error conn)]
        (f (error/from-exception err :realtime))))))

(defn connect
  "Opens a Realtime connection for `client`.

  ## Options

    - `:on-error`           — `(fn [anomaly])` for async transport/server errors
    - `:heartbeat-ms`       — heartbeat interval in ms (default 30000)
    - `:params`             — extra query params merged into the WS URL
    - `:transport-factory`  — `(fn [url headers handlers])` returning a
                              `Transport`. Defaults to `ws-transport`.
                              Useful for tests.

  Returns a connection map: `{:client :transport :state :on-error :heartbeat}`,
  or an anomaly on failure."
  ([client] (connect client {}))
  ([client opts]
   (let [{:keys [on-error heartbeat-ms params transport-factory]
          :or   {heartbeat-ms 30000
                 transport-factory ws-transport}} opts
         url (build-ws-url client (or params {}))
         headers (upgrade-headers client)
         state (atom (initial-state))
         conn-promise (atom nil)
         handlers {:on-open  (on-open-handler conn-promise)
                   :on-text  (on-text-handler conn-promise)
                   :on-close (on-close-handler conn-promise)
                   :on-error (on-error-handler conn-promise)}]
     (try
       (let [transport (transport-factory url headers handlers)
             conn {:client    client
                   :transport transport
                   :state     state
                   :on-error  on-error}
             heartbeat (start-heartbeat! conn heartbeat-ms)
             conn (assoc conn :heartbeat heartbeat)]
         (reset! conn-promise conn)
         conn)
       (catch Throwable t
         (error/from-exception t :realtime))))))

(defn disconnect
  "Closes the connection: stops the heartbeat, closes the socket, and marks
  state as `:closed`. Idempotent."
  [conn]
  (when conn
    (when-let [^ScheduledExecutorService exec (:heartbeat conn)]
      (.shutdownNow exec))
    (try (close! (:transport conn) 1000 "client closing")
         (catch Throwable _ nil))
    (swap! (:state conn) assoc :status :closed)
    conn))
