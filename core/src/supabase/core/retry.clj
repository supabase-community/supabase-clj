(ns supabase.core.retry
  "Pure retry policy for transient HTTP failures in the Supabase Clojure SDK.

  This namespace decides *whether* and *how long* to wait before retrying a
  failed request. It performs no sleeping and no I/O: `supabase.core.http`
  calls these functions from its retry loop and owns the actual waiting.

  ## Transient failures

  A failure is worth retrying when it is likely to resolve on its own:

    - HTTP statuses 429, 502, 503, 504 (`transient-status?`)
    - Transport exceptions: connection refused, connect/read timeouts, no
      route to host, reset or broken-pipe sockets, and unexpected EOF
      (`transient-exception?`, which also walks the cause chain)

  ## Delay computation

  Delays combine two sources, with the server always winning:

    1. A `Retry-After` response header, when present and parseable
       (`retry-after-ms`), either as delay-seconds or an RFC 1123 HTTP-date.
    2. Exponential backoff with full jitter (`backoff-ms`), capped at
       `:max-delay-ms`.

  `next-delay-ms` applies that precedence. `merge-opts` resolves client-level
  and request-level retry options against `default-opts`.

  ## Usage

      (require '[supabase.core.retry :as retry])

      (retry/transient-status? 503)              ;; => true
      (retry/retry-after-ms {\"retry-after\" \"3\"}) ;; => 3000
      (retry/backoff-ms 2)                       ;; => long in [0, 400]
      (retry/next-delay-ms 2 {\"retry-after\" \"3\"}) ;; => 3000
      (retry/merge-opts {:max-attempts 5} true)  ;; => {:max-attempts 5, ...}"
  (:require [clojure.string :as str])
  (:import (java.io EOFException)
           (java.net ConnectException NoRouteToHostException SocketException)
           (java.net.http HttpConnectTimeoutException HttpTimeoutException)
           (java.time Instant ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeParseException)))

;; ---------------------------------------------------------------------------
;; Options
;; ---------------------------------------------------------------------------

(def default-opts
  "Default retry options."
  {:max-attempts     3
   :initial-delay-ms 200
   :max-delay-ms     5000
   :multiplier       2.0})

;; ---------------------------------------------------------------------------
;; Transient failure detection
;; ---------------------------------------------------------------------------

(defn transient-status?
  "True for HTTP statuses worth retrying: 429, 502, 503, 504."
  [status]
  (contains? #{429 502 503 504} status))

(defn- socket-transient-message?
  "True when a SocketException message indicates a reset or broken pipe."
  [^String msg]
  (and (some? msg)
       (let [m (str/lower-case msg)]
         (or (str/includes? m "connection reset")
             (str/includes? m "broken pipe")))))

(defn- transient-single-exception?
  "True when a single exception (no cause walk) is transient."
  [^Throwable ex]
  (or (instance? ConnectException ex)
      (instance? HttpConnectTimeoutException ex)
      (instance? HttpTimeoutException ex)
      (instance? NoRouteToHostException ex)
      (instance? EOFException ex)
      (and (instance? SocketException ex)
           (socket-transient-message? (.getMessage ex)))))

(defn transient-exception?
  "True for exceptions that indicate a transient transport failure:
  `java.net.ConnectException`, `java.net.http.HttpConnectTimeoutException`,
  `java.net.http.HttpTimeoutException`, `java.net.NoRouteToHostException`,
  `java.net.SocketException` whose message contains \"Connection reset\" or
  \"Broken pipe\" (case-insensitive), and `java.io.EOFException`.

  Walks the cause chain, so a transient failure wrapped in another exception
  (for example via `ex-info`) is still detected."
  [^Throwable ex]
  (boolean (some transient-single-exception? (take-while some? (iterate #(.getCause ^Throwable %) ex)))))

;; ---------------------------------------------------------------------------
;; Retry-After header
;; ---------------------------------------------------------------------------

(defn- parse-long-safe
  "Parses `s` as a long, returning nil on failure."
  [^String s]
  (try
    (Long/parseLong (str/trim s))
    (catch NumberFormatException _ nil)))

(defn- http-date-ms
  "Milliseconds from now until the given RFC 1123 HTTP-date, clamped at >= 0.
  Returns nil when the value is not a parseable HTTP-date."
  [^String s]
  (try
    (let [target (.toInstant (ZonedDateTime/parse s DateTimeFormatter/RFC_1123_DATE_TIME))]
      (max 0 (- (.toEpochMilli target) (.toEpochMilli (Instant/now)))))
    (catch DateTimeParseException _ nil)))

(defn retry-after-ms
  "Parses a Retry-After header value from a response headers map.

  Headers arrive with lower-case string keys, so the lookup key is
  \"retry-after\". The value may be a non-negative integer (delay-seconds)
  or an HTTP-date (RFC 1123, e.g. \"Tue, 18 Aug 2026 12:00:00 GMT\").
  Negative integer values are clamped to 0. Returns the delay in
  milliseconds as a long, or nil when the header is absent or unparseable.
  Never throws.

      (retry-after-ms {\"retry-after\" \"3\"})  ;; => 3000
      (retry-after-ms {})                     ;; => nil"
  [headers]
  (when-let [value (get headers "retry-after")]
    (when (string? value)
      (if-let [seconds (parse-long-safe value)]
        (max 0 (* seconds 1000))
        (http-date-ms value)))))

;; ---------------------------------------------------------------------------
;; Backoff
;; ---------------------------------------------------------------------------

(defn backoff-ms
  "Exponential backoff with full jitter for the given 1-based `attempt`.

  The delay cap is `min(max-delay-ms, initial-delay-ms * multiplier^(attempt-1))`;
  the result is a uniformly random long in [0, cap]. Missing option keys fall
  back to `default-opts`.

      (backoff-ms 1) ;; => long in [0, 200] with default opts"
  ([attempt]
   (backoff-ms attempt nil))
  ([attempt opts]
   (let [{:keys [initial-delay-ms max-delay-ms multiplier]} (merge default-opts opts)
         cap (min max-delay-ms (* initial-delay-ms (Math/pow multiplier (dec attempt))))]
     (long (rand (inc (long cap)))))))

(defn next-delay-ms
  "Delay in milliseconds before the next attempt.

  An honored Retry-After header in `headers` wins over computed backoff;
  otherwise returns `backoff-ms` for `attempt`. Returns a long.

      (next-delay-ms 1 {\"retry-after\" \"3\"}) ;; => 3000
      (next-delay-ms 1 {})                    ;; => long in [0, 200]"
  ([attempt headers]
   (next-delay-ms attempt headers nil))
  ([attempt headers opts]
   (if-let [retry-after (retry-after-ms headers)]
     retry-after
     (backoff-ms attempt opts))))

;; ---------------------------------------------------------------------------
;; Option resolution
;; ---------------------------------------------------------------------------

(defn- resolve-opts
  "Normalizes a retry option value into a full opts map.

  nil and false resolve to nil (disabled); true resolves to `default-opts`;
  an integer resolves to `default-opts` with that `:max-attempts`; a map is
  merged over `default-opts`."
  [opts]
  (cond
    (map? opts)     (merge default-opts opts)
    (true? opts)    default-opts
    (integer? opts) (assoc default-opts :max-attempts opts)
    :else           nil))

(defn merge-opts
  "Resolves retry options from defaults, client-level opts, and a
  request-level override.

  The request override may be:

    - nil/absent: client opts win
    - false:      retry disabled (returns nil)
    - true:       client opts (or defaults) as-is
    - integer N:  client opts with `:max-attempts` N
    - map:        merged over client opts

  Client opts may likewise be true (use defaults), an integer
  (`:max-attempts`), or a map. Returns a resolved opts map containing every
  key of `default-opts`, or nil when retries are disabled.

      (merge-opts {:max-attempts 5} false) ;; => nil
      (merge-opts true 5)                  ;; => (assoc default-opts :max-attempts 5)"
  [client-opts request-opts]
  (let [client (resolve-opts client-opts)]
    (cond
      (false? request-opts)  nil
      (nil? request-opts)    client
      (true? request-opts)   (or client default-opts)
      (integer? request-opts) (assoc (or client default-opts) :max-attempts request-opts)
      (map? request-opts)    (merge (or client default-opts) request-opts)
      :else                  client)))
