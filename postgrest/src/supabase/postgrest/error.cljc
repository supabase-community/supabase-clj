(ns supabase.postgrest.error
  "PostgREST-specific anomaly enrichment.

  PostgREST error bodies carry extra fields — `message`, `hint`, `code`,
  `details` — that callers want surfaced. This module decorates a
  `core.error` anomaly with those fields under the `:postgrest/*` namespace
  and, for a handful of well-known codes, refines the anomaly category."
  (:require [supabase.core.error :as error]))

(def ^:private code->category
  "Maps known PostgREST/SQLSTATE error codes to anomaly categories,
  overriding the status-derived default."
  {"PGRST116" :cognitect.anomalies/not-found   ;; 0 or >1 rows for a single-object request
   "PGRST301" :cognitect.anomalies/forbidden   ;; JWT / role authorization failure
   "42501"    :cognitect.anomalies/forbidden}) ;; SQLSTATE insufficient_privilege

(defn enrich
  "Decorates a `core.error` anomaly with PostgREST-specific fields when the
  response body carries them, and upgrades the category for recognized
  codes. Returns the input unchanged if it is not an anomaly or has no
  database fields."
  [result]
  (if-not (error/anomaly? result)
    result
    (let [body (:http/body result)
          db-fields? (and (map? body)
                          (or (:hint body) (:code body)
                              (:details body) (:message body)))]
      (if-not db-fields?
        result
        (let [code (:code body)]
          (cond-> (assoc result
                         :supabase/service :postgrest
                         :supabase/code :database-error)
            (:message body) (assoc :cognitect.anomalies/message (:message body)
                                   :postgrest/message (:message body))
            (:hint body)    (assoc :postgrest/hint (:hint body))
            code            (assoc :postgrest/code code)
            (:details body) (assoc :postgrest/details (:details body))
            (code->category code) (assoc :cognitect.anomalies/category
                                         (code->category code))))))))
