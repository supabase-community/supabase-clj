(ns supabase.realtime.specs
  "Malli schemas for Supabase Realtime API inputs.

  Validates caller arguments only. Server payloads pass through unchecked —
  postgres column data is user-defined and shouldn't be gated."
  (:require [malli.core :as m]
            [supabase.core.error :as error]))

(def BroadcastConfig
  (m/schema [:map
             {:closed true}
             [:self {:optional true} :boolean]]))

(def PresenceConfig
  (m/schema [:map
             {:closed true}
             [:key {:optional true} :string]]))

(def ChannelConfig
  "Schema for the `:config` key passed to `channel`."
  (m/schema [:map
             {:closed true}
             [:broadcast {:optional true} #'BroadcastConfig]
             [:presence  {:optional true} #'PresenceConfig]
             [:private   {:optional true} :boolean]]))

(def ChannelOpts
  (m/schema [:map
             {:closed true}
             [:config {:optional true} #'ChannelConfig]]))

(def PostgresEvent
  (m/schema [:enum :insert :update :delete :all "*"]))

(def PostgresFilter
  "Schema for a postgres_changes binding filter."
  (m/schema [:map
             {:closed true}
             [:event :keyword]
             [:schema :string]
             [:table  :string]
             [:filter {:optional true} :string]]))

(def BroadcastFilter
  (m/schema [:map
             {:closed true}
             [:event :string]]))

(def PresenceEvent
  (m/schema [:enum :sync :join :leave]))

(def PresenceFilter
  (m/schema [:map
             {:closed true}
             [:event #'PresenceEvent]]))

(def BindingType
  (m/schema [:enum :postgres-changes :broadcast :presence]))

(defn ensure-valid
  "Returns nil if `value` matches `schema`, else an anomaly with the malli
  explanation attached."
  [schema value]
  (when-not (m/validate schema value)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid input"
                    :malli/explanation (m/explain schema value)
                    :supabase/service :realtime})))

(defn ensure-filter
  "Picks the correct schema for `binding-type` and validates `filter`."
  [binding-type filter]
  (case binding-type
    :postgres-changes (ensure-valid PostgresFilter filter)
    :broadcast        (ensure-valid BroadcastFilter filter)
    :presence         (ensure-valid PresenceFilter filter)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message
                    (str "Unknown binding type: " binding-type)
                    :supabase/service :realtime})))
