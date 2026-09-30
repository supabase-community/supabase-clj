(ns supabase.functions.specs
  "Malli schemas for the Functions module."
  (:require [malli.core :as m]
            [supabase.core.error :as error]))

(def Region
  "Supported deployment regions for `x-region`. `:any` is a no-op
  (no header is sent)."
  (m/schema [:enum :any
             :us-west-1 :us-west-2 :us-east-1
             :eu-west-1 :eu-west-2 :eu-west-3
             :ap-south-1 :ap-southeast-1 :ap-southeast-2
             :ap-northeast-1 :ap-northeast-2
             :sa-east-1 :ca-central-1 :eu-central-1]))

(def Method
  "Supported HTTP methods for function invocation."
  (m/schema [:enum :get :post :put :patch :delete]))

(def ResponseAs
  "How to return the response body."
  (m/schema [:enum :auto :json :text :byte-array :stream]))

(def InvokeOpts
  "Options accepted by `supabase.functions/invoke`."
  (m/schema [:map
             [:body {:optional true} :any]
             [:headers {:optional true} [:map-of :string :string]]
             [:method {:optional true} #'Method]
             [:region {:optional true} #'Region]
             [:response-as {:optional true} #'ResponseAs]
             [:timeout {:optional true} :int]
             [:access-token {:optional true} :string]]))

(defn ensure-valid
  "Returns nil when `value` conforms to `schema`; otherwise an anomaly
  with the malli explanation attached."
  [schema value]
  (when-not (m/validate schema value)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid input"
                    :malli/explanation (m/explain schema value)
                    :supabase/service :functions})))
