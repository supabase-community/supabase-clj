(ns supabase.realtime.protocol
  "Phoenix Channel v1.0.0 JSON wire protocol for Supabase Realtime.

  Pure builders + parser. No I/O, no state. The connection module composes
  these into a transport-bound conversation.

  ## Frame shape

      {:topic    \"realtime:room:lobby\"
       :event    \"phx_join\"
       :payload  {...}
       :ref      \"1\"
       :join_ref \"1\"}    ; only on channel-bound messages

  ## Event kinds

  `event-kind` returns one of:

      :phx-reply :phx-close :phx-error
      :postgres-changes :broadcast
      :presence-state :presence-diff
      :system :heartbeat-reply :unknown"
  (:require [clojure.string :as str]
            [jsonista.core :as json]))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn encode
  "JSON-encodes `frame` to a string."
  ^String [frame]
  (json/write-value-as-string frame mapper))

(defn parse-frame
  "Parses a JSON text frame into a Clojure map with keyword keys.

  Payload values are returned as-is (postgres column names stay snake_case)."
  [text]
  (json/read-value text mapper))

;; ---------------------------------------------------------------------------
;; Topic + ref helpers
;; ---------------------------------------------------------------------------

(defn realtime-topic
  "Ensures a topic is prefixed with `realtime:`. Idempotent."
  [topic]
  (if (str/starts-with? topic "realtime:") topic (str "realtime:" topic)))

(defn ref-counter
  "Returns an atom suitable for generating monotonic outgoing `:ref` ids."
  []
  (atom 0))

(defn next-ref
  "Increments `counter` and returns the new value as a string."
  [counter]
  (str (swap! counter inc)))

;; ---------------------------------------------------------------------------
;; Frame builders
;; ---------------------------------------------------------------------------

(defn- postgres-binding-payload [binding]
  (let [{:keys [event schema table filter]} (:filter binding)]
    (cond-> {:event (name event) :schema schema :table table}
      filter (assoc :filter filter))))

(defn join-frame
  "Builds a `phx_join` frame for `topic` with the given channel `config`
  and `bindings` vector. `access-token` is optional and added to the
  payload when present."
  [ref topic config bindings access-token]
  (let [pg-bindings (->> bindings
                         (filter #(= :postgres-changes (:type %)))
                         (mapv postgres-binding-payload))
        config' (assoc config :postgres_changes pg-bindings)
        payload (cond-> {:config config'}
                  access-token (assoc :access_token access-token))]
    {:topic topic :event "phx_join" :payload payload :ref ref :join_ref ref}))

(defn leave-frame
  "Builds a `phx_leave` frame."
  [ref join-ref topic]
  {:topic topic :event "phx_leave" :payload {} :ref ref :join_ref join-ref})

(defn heartbeat-frame
  "Builds a heartbeat frame on the reserved `phoenix` topic."
  [ref]
  {:topic "phoenix" :event "heartbeat" :payload {} :ref ref})

(defn broadcast-frame
  "Builds a broadcast frame. Server echoes back to other subscribers
  (and to self when `broadcast.self` is enabled)."
  [ref join-ref topic event payload]
  {:topic topic
   :event "broadcast"
   :payload {:type "broadcast" :event event :payload payload}
   :ref ref
   :join_ref join-ref})

(defn presence-track-frame
  "Builds a presence track frame for the given `state` map."
  [ref join-ref topic state]
  {:topic topic
   :event "presence"
   :payload {:type "presence" :event "track" :payload (or state {})}
   :ref ref
   :join_ref join-ref})

(defn presence-untrack-frame
  "Builds a presence untrack frame."
  [ref join-ref topic]
  {:topic topic
   :event "presence"
   :payload {:type "presence" :event "untrack"}
   :ref ref
   :join_ref join-ref})

(defn access-token-frame
  "Builds an `access_token` event frame to refresh auth for a joined channel."
  [ref join-ref topic token]
  {:topic topic
   :event "access_token"
   :payload {:access_token token}
   :ref ref
   :join_ref join-ref})

;; ---------------------------------------------------------------------------
;; Inbound classification
;; ---------------------------------------------------------------------------

(def ^:private event->kind
  {"phx_reply"      :phx-reply
   "phx_close"      :phx-close
   "phx_error"      :phx-error
   "postgres_changes" :postgres-changes
   "broadcast"      :broadcast
   "presence_state" :presence-state
   "presence_diff"  :presence-diff
   "system"         :system})

(defn event-kind
  "Returns the kind keyword for a decoded inbound frame. `:unknown` for
  events not recognized — caller should log + ignore."
  [frame]
  (let [event (:event frame)]
    (cond
      (and (= "phx_reply" event) (= "phoenix" (:topic frame))) :heartbeat-reply
      :else (get event->kind event :unknown))))
