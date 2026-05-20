(ns supabase.postgrest.error
  "PostgREST-specific anomaly enrichment.

  PostgREST error bodies carry extra fields — `hint`, `code`, `details` —
  that callers want surfaced. This module wraps `core.error` anomalies
  with those extras."
  (:require [supabase.core.error :as error]))

(defn enrich
  "Decorates a `core.error` anomaly with PostgREST-specific fields when
  the response body carries them. Returns the input unchanged if it is
  not an anomaly or has no database fields."
  [result]
  (if-not (error/anomaly? result)
    result
    (let [body (:http/body result)
          db-fields? (and (map? body)
                          (or (:hint body) (:code body)
                              (:details body) (:message body)))]
      (if-not db-fields?
        result
        (cond-> (assoc result
                       :supabase/service :postgrest
                       :supabase/code :database-error)
          (:message body) (assoc :cognitect.anomalies/message (:message body))
          (:hint body)    (assoc :database/hint (:hint body))
          (:code body)    (assoc :database/code (:code body))
          (:details body) (assoc :database/detail (:details body)))))))
