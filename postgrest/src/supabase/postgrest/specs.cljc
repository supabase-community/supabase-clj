(ns supabase.postgrest.specs
  "Malli schemas for PostgREST builder option maps.

  Validates caller-supplied opts only. Filter values pass through to the
  URL — PostgREST validates them server-side."
  (:require [malli.core :as m]
            [supabase.core.error :as error]))

(def CountAlgorithm
  (m/schema [:enum :exact :planned :estimated]))

(def Returning
  (m/schema [:enum :representation :minimal :none :headers-only]))

(def SelectOpts
  (m/schema [:map
             {:closed true}
             [:count {:optional true} #'CountAlgorithm]
             [:returning {:optional true} :boolean]]))

(def MutationOpts
  (m/schema [:map
             {:closed true}
             [:on-conflict {:optional true} :string]
             [:returning {:optional true} #'Returning]
             [:count {:optional true} #'CountAlgorithm]]))

(def OrderOpts
  (m/schema [:map
             {:closed true}
             [:asc {:optional true} :boolean]
             [:null-first {:optional true} :boolean]
             [:foreign-table {:optional true} :string]]))

(def ForeignTableOpts
  (m/schema [:map
             {:closed true}
             [:foreign-table {:optional true} :string]]))

(def ExplainOpts
  (m/schema [:map
             {:closed true}
             [:analyze {:optional true} :boolean]
             [:verbose {:optional true} :boolean]
             [:settings {:optional true} :boolean]
             [:buffers {:optional true} :boolean]
             [:wal {:optional true} :boolean]
             [:format {:optional true} [:enum :json :text]]]))

(def TextSearchOpts
  (m/schema [:map
             {:closed true}
             [:type {:optional true} [:enum :plain :phrase :websearch]]
             [:config {:optional true} :string]]))

(def RpcOpts
  (m/schema [:map
             {:closed true}
             [:head {:optional true} :boolean]
             [:get {:optional true} :boolean]
             [:count {:optional true} #'CountAlgorithm]]))

(def AggregationOpts
  (m/schema [:map
             {:closed true}
             [:as {:optional true} :string]]))

(def MediaType
  (m/schema [:enum :default :csv :json :openapi :postgis
             :pgrst-plan :pgrst-object :pgrst-array]))

(defn ensure-valid
  "Returns nil if `value` matches `schema`, else an anomaly with the malli
  explanation attached."
  [schema value]
  (when-not (m/validate schema value)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid input"
                    :malli/explanation (m/explain schema value)
                    :supabase/service :postgrest})))
