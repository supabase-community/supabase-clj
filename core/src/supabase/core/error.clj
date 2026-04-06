(ns supabase.core.error
  "Anomaly-based error handling for the Supabase Clojure SDK.

  Errors are represented as plain maps following the cognitect/anomalies convention.
  This avoids tagged tuples and exceptions by default, matching the data-driven
  philosophy of libraries like cognitect/aws-api.

  ## Anomaly Categories

  The SDK maps HTTP status codes and domain errors to these categories:

    - `:cognitect.anomalies/incorrect`   — bad request, validation failure (4xx client errors)
    - `:cognitect.anomalies/forbidden`   — authentication/authorization failure (401, 403)
    - `:cognitect.anomalies/not-found`   — resource not found (404)
    - `:cognitect.anomalies/conflict`    — resource already exists (409)
    - `:cognitect.anomalies/busy`        — rate limited, resource locked (423, 429)
    - `:cognitect.anomalies/unavailable` — server error, service unavailable (5xx)
    - `:cognitect.anomalies/fault`       — unexpected server-side failure

  ## Structure

  An anomaly map always contains `:cognitect.anomalies/category` and may include:

    - `:cognitect.anomalies/message` — human-readable error description
    - `:supabase/service`            — originating service (`:auth`, `:storage`, etc.)
    - `:supabase/code`               — semantic error code keyword (e.g. `:not-found`)
    - `:http/status`                 — original HTTP status code
    - `:http/body`                   — response body (parsed or raw)
    - `:http/headers`                — response headers

  ## Usage

      (require '[supabase.core.error :as error])

      ;; Check if a result is an error
      (error/anomaly? result)

      ;; Create an anomaly from an HTTP response
      (error/from-http-response 404 {:message \"Not Found\"} :storage)

      ;; Create a domain-specific anomaly
      (error/anomaly :cognitect.anomalies/incorrect
        {:supabase/service :auth
         :cognitect.anomalies/message \"Invalid credentials\"})"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Anomaly predicates
;; ---------------------------------------------------------------------------

(defn anomaly?
  "Returns true if `x` is an anomaly map (contains `:cognitect.anomalies/category`)."
  [x]
  (and (map? x) (contains? x :cognitect.anomalies/category)))

;; ---------------------------------------------------------------------------
;; Anomaly construction
;; ---------------------------------------------------------------------------

(defn anomaly
  "Creates an anomaly map with the given `category` and optional extra fields.

  `category` must be a valid `cognitect.anomalies` category keyword.

      (anomaly :cognitect.anomalies/incorrect
        {:cognitect.anomalies/message \"Bad request\"
         :supabase/service :auth})"
  ([category]
   (anomaly category {}))
  ([category extra]
   (merge {:cognitect.anomalies/category category} extra)))

;; ---------------------------------------------------------------------------
;; HTTP status -> anomaly category mapping
;; ---------------------------------------------------------------------------

(def ^:private status->category
  "Maps HTTP status codes to cognitect.anomalies categories."
  {400 :cognitect.anomalies/incorrect
   401 :cognitect.anomalies/forbidden
   403 :cognitect.anomalies/forbidden
   404 :cognitect.anomalies/not-found
   405 :cognitect.anomalies/incorrect
   409 :cognitect.anomalies/conflict
   411 :cognitect.anomalies/incorrect
   413 :cognitect.anomalies/incorrect
   416 :cognitect.anomalies/incorrect
   422 :cognitect.anomalies/incorrect
   423 :cognitect.anomalies/busy
   429 :cognitect.anomalies/busy
   500 :cognitect.anomalies/fault
   501 :cognitect.anomalies/unsupported
   503 :cognitect.anomalies/unavailable
   504 :cognitect.anomalies/unavailable})

(def ^:private status->code
  "Maps HTTP status codes to semantic error code keywords."
  {400 :bad-request
   401 :unauthorized
   403 :forbidden
   404 :not-found
   405 :method-not-allowed
   409 :resource-already-exists
   411 :missing-content-length
   413 :content-too-large
   416 :invalid-range
   422 :unprocessable-entity
   423 :resource-locked
   429 :too-many-requests
   500 :server-error
   501 :not-implemented
   503 :service-unavailable
   504 :gateway-timeout})

(defn- default-category
  "Returns the default anomaly category for an HTTP status code."
  [status]
  (cond
    (<= 400 status 499) :cognitect.anomalies/incorrect
    (<= 500 status 599) :cognitect.anomalies/fault
    :else               :cognitect.anomalies/fault))

(defn humanize-code
  "Converts a keyword error code to a human-readable string.

      (humanize-code :not-found) ;; => \"Not Found\"
      (humanize-code :bad-request) ;; => \"Bad Request\""
  [code]
  (->> (str/split (name code) #"-")
       (map str/capitalize)
       (str/join " ")))

;; ---------------------------------------------------------------------------
;; HTTP response -> anomaly
;; ---------------------------------------------------------------------------

(defn from-http-response
  "Creates an anomaly map from an HTTP error response.

  `status` is the HTTP status code (>= 400). `body` is the parsed response body.
  `service` is an optional keyword identifying the Supabase service.

      (from-http-response 404 {:message \"Not found\"} :storage)
      ;; => {:cognitect.anomalies/category :cognitect.anomalies/not-found
      ;;     :cognitect.anomalies/message  \"Not Found\"
      ;;     :supabase/service :storage
      ;;     :supabase/code    :not-found
      ;;     :http/status      404
      ;;     :http/body        {:message \"Not found\"}}"
  ([status body]
   (from-http-response status body nil))
  ([status body service]
   (let [category (get status->category status (default-category status))
         code (get status->code status :unexpected)]
     (cond-> {:cognitect.anomalies/category category
              :cognitect.anomalies/message (humanize-code code)
              :supabase/code code
              :http/status status
              :http/body body}
       service (assoc :supabase/service service)))))

(defn from-exception
  "Creates an anomaly map from a caught exception.

      (try
        (do-something)
        (catch Exception e
          (error/from-exception e :auth)))"
  ([^Throwable ex]
   (from-exception ex nil))
  ([^Throwable ex service]
   (cond-> {:cognitect.anomalies/category :cognitect.anomalies/fault
            :cognitect.anomalies/message (.getMessage ex)
            :supabase/code :exception
            :exception ex}
     service (assoc :supabase/service service))))
