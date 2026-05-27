(ns supabase.core.http
  "Composable HTTP request builder and executor for Supabase services.

  Requests are built as plain maps using a threading-friendly API, then
  executed through a [[supabase.core.transport/Transport]]. The default
  transport wraps Hato; tests and integrators can swap it.

  ## Request map

  A request map contains:

    - `:method`        — HTTP method keyword (`:get` `:post` `:put` `:patch` `:delete`)
    - `:url`           — fully resolved URL string
    - `:headers`       — map of header name to value
    - `:query`         — map of query parameter name to value
    - `:body`          — request body (map, string, bytes, File, InputStream, or nil)
    - `:multipart`     — vector of multipart parts (mutually exclusive with `:body`)
    - `:response-as`   — `:string` (default), `:byte-array`, `:stream`, `:reader`
    - `:decoder`       — fn from raw body to parsed body (default JSON for `:string`)
    - `:error-parser`  — fn `[status body headers service]` → anomaly map
    - `:log?`          — emit debug/error log lines for this request
    - `:timeout`       — per-request timeout (ms)
    - `:transport`     — explicit transport instance (overrides client transport)
    - `:service`       — originating service keyword (`:auth`, `:storage`, etc.)
    - `:client`        — reference to the client map

  ## Usage

      (require '[supabase.core.http :as http])

      ;; Build and execute a request
      (-> (http/request client)
          (http/with-service-url :auth-url \"/signup\")
          (http/with-method :post)
          (http/with-body {:email \"user@example.com\" :password \"secret\"})
          (http/execute))
      ;; => {:status 200, :body {...}, :headers {...}} on success
      ;; => anomaly map on HTTP error (status >= 400)

      ;; Streaming response (no decoding)
      (-> (http/request client)
          (http/with-service-url :storage-url \"/object/bucket/path\")
          (http/with-response-as :stream)
          (http/execute))
      ;; => {:status 200, :body #object[java.io.InputStream ...], :headers {...}}

      ;; Multipart upload
      (-> (http/request client)
          (http/with-service-url :storage-url \"/object/bucket/path\")
          (http/with-method :post)
          (http/with-multipart [{:name \"file\" :content (io/file \"a.png\")
                                  :content-type \"image/png\" :filename \"a.png\"}])
          (http/execute))"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn true}))

;; ---------------------------------------------------------------------------
;; Request construction
;; ---------------------------------------------------------------------------

(defn request
  "Initializes a request map from a client, pre-populating auth headers.

  The request starts with `:method :get` and includes the client's global
  headers plus `authorization` and `apikey` headers derived from the client."
  [client]
  (let [global-headers (get-in client [:global :headers] {})
        auth-headers {"authorization" (str "Bearer " (:access-token client))
                      "apikey" (:api-key client)}]
    {:method  :get
     :url     nil
     :headers (merge global-headers auth-headers)
     :query   {}
     :body    nil
     :service nil
     :client  client}))

(defn with-service-url
  "Sets the request URL by resolving a service URL key from the client and
  appending `path`. `service-url-key` is a keyword like `:auth-url`."
  [req service-url-key path]
  (let [base-url (get-in req [:client service-url-key])
        service (keyword (first (str/split (name service-url-key) #"-")))]
    (assoc req
           :url (str base-url path)
           :service service)))

(defn with-method
  "Sets the HTTP method for the request."
  [req method]
  (assoc req :method method))

(defn with-body
  "Sets the request body.

  Behaviour:

    - Map → JSON-encoded and `content-type: application/json` is set.
    - String → passed through as-is.
    - `byte[]` / `File` / `InputStream` → passed through as-is. Caller is
      responsible for setting `content-type`.
    - nil → cleared."
  [req body]
  (cond
    (nil? body)
    (assoc req :body nil)

    (map? body)
    (-> req
        (assoc :body (json/write-value-as-string body))
        (update :headers assoc "content-type" "application/json"))

    :else
    (assoc req :body body)))

(defn with-multipart
  "Attaches a multipart payload. Mutually exclusive with `:body`.

  `parts` is a vector of part maps shaped like:

      {:name         \"file\"                  ;; form field name (required)
       :content      <File | InputStream | byte[] | String>  ;; required
       :content-type \"image/png\"             ;; optional
       :filename     \"a.png\"                 ;; optional, becomes filename=
       :encoding     \"UTF-8\"}                ;; optional charset

  Hato is responsible for assembling the multipart body and setting the
  `multipart/form-data` content-type with boundary."
  [req parts]
  (-> req
      (assoc :multipart parts)
      (assoc :body nil)))

(defn with-headers
  "Merges additional headers into the request. Later values override
  earlier ones."
  [req headers]
  (update req :headers merge headers))

(defn with-query
  "Merges query parameters into the request. Later values override
  earlier ones."
  [req params]
  (update req :query merge params))

(defn merge-query-param
  "Appends `value` to the existing value at `key` in the query map,
  joined by `sep` (default `,`). When the key is absent, sets it to
  `value`. Useful for PostgREST-style stacked filters."
  ([req key value] (merge-query-param req key value ","))
  ([req key value sep]
   (update-in req [:query key]
              (fn [existing]
                (if existing (str existing sep value) value)))))

(defn with-response-as
  "Sets how the transport should coerce the response body.

    - `:string` (default) — UTF-8 string. Decoded by [[with-decoder]]
      (JSON by default).
    - `:byte-array` — raw bytes. No decoding.
    - `:stream`     — `java.io.InputStream`. No decoding. Caller closes.
    - `:reader`     — `java.io.Reader`. No decoding. Caller closes.

  Non-string bodies skip the default JSON decode step."
  [req as]
  (assoc req :response-as as))

(defn with-decoder
  "Sets a custom body decoder. `decoder` is a 1-arg fn taking the raw
  response body and returning the parsed value. Applied only when
  `:response-as` is `:string` (the default).

  Pass `identity` to receive the raw string body without JSON parsing."
  [req decoder]
  (assoc req :decoder decoder))

(defn with-error-parser
  "Installs a custom error parser. `parser` is a 4-arg fn
  `[status body headers service]` returning an anomaly map. Default is
  [[supabase.core.error/from-http-response]].

  Service modules (auth, storage, postgrest, functions) use this to
  enrich anomalies with service-specific error codes."
  [req parser]
  (assoc req :error-parser parser))

(defn with-transport
  "Overrides the transport used for this request."
  [req t]
  (assoc req :transport t))

(defn with-timeout
  "Per-request timeout in milliseconds."
  [req ms]
  (assoc req :timeout ms))

(defn with-logging
  "Enables or disables structured request/response logging for this
  request. When the request map (or its client) has `:log? true`,
  `clojure.tools.logging` emits a debug line on dispatch and on
  success, plus an error line on failure."
  ([req] (with-logging req true))
  ([req on?] (assoc req :log? on?)))

;; ---------------------------------------------------------------------------
;; Response handling
;; ---------------------------------------------------------------------------

(defn- json-decode-safe
  "Best-effort JSON decode. Returns raw body on parse failure."
  [body]
  (if (string? body)
    (try (json/read-value body json-mapper)
         (catch Exception _ body))
    body))

(defn- decode-body
  "Decodes a response body based on `response-as` and an optional
  `decoder` fn."
  [body {:keys [response-as decoder]}]
  (let [as (or response-as :string)]
    (cond
      ;; Binary or streaming response: pass through untouched
      (#{:byte-array :stream :reader} as) body
      ;; Caller-supplied decoder wins
      decoder (decoder body)
      ;; Default: JSON decode strings, pass through everything else
      :else (json-decode-safe body))))

(defn- default-error-parser
  "Adapter that delegates to `supabase.core.error/from-http-response`,
  discarding the headers argument the parser contract supplies."
  [status body _headers service]
  (error/from-http-response status body service))

(defn- handle-response
  "Converts a raw transport response into either a success map or an
  anomaly map."
  [{:keys [status body headers]} req]
  (let [service (:service req)
        parser (or (:error-parser req) default-error-parser)]
    (if (< status 400)
      {:status status
       :body (decode-body body req)
       :headers headers}
      ;; Errors always parse the body as JSON so the caller gets a
      ;; structured anomaly even when the request expected binary.
      (parser status (json-decode-safe body) headers service))))

;; ---------------------------------------------------------------------------
;; Request preparation
;; ---------------------------------------------------------------------------

(defn- log-enabled? [req]
  (or (:log? req)
      (get-in req [:client :log?])))

(defn- log-request [req]
  (when (log-enabled? req)
    (log/debugf "supabase %s %s %s" (:service req) (:method req) (:url req))))

(defn- log-response [req resp elapsed-ms]
  (when (log-enabled? req)
    (if (error/anomaly? resp)
      (log/errorf "supabase %s %s %s -> anomaly %s in %dms"
                  (:service req) (:method req) (:url req)
                  (:supabase/code resp) elapsed-ms)
      (log/debugf "supabase %s %s %s -> %d in %dms"
                  (:service req) (:method req) (:url req)
                  (:status resp) elapsed-ms))))

(defn- build-transport-request
  "Transforms an internal request map into the lower-level map the
  transport expects."
  [{:keys [method url headers query body multipart response-as timeout]}]
  (cond-> {:method method
           :url url
           :headers headers
           :as (or response-as :string)
           ;; Hato throws on >= 400 by default. Disable so we can map to anomalies.
           :throw-exceptions? false}
    (seq query)  (assoc :query-params query)
    body         (assoc :body body)
    multipart    (assoc :multipart multipart)
    timeout      (assoc :timeout timeout)))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn execute
  "Executes the request synchronously through the configured transport.

  Returns a response map on success (status < 400) or an anomaly map on
  error.

  ## Logging

  When `:log?` is true on the request map or the client, a debug line is
  emitted on dispatch and an error/debug line on completion."
  [req]
  (log-request req)
  (let [t (transport/resolve-transport req)
        start (System/nanoTime)
        result (try
                 (handle-response (transport/execute t (build-transport-request req)) req)
                 (catch Exception e
                   (error/from-exception e (:service req))))
        elapsed (long (/ (- (System/nanoTime) start) 1000000))]
    (log-response req result elapsed)
    result))

(defn execute!
  "Like [[execute]], but throws an `ex-info` on error.

  The anomaly map is attached as the ex-data of the thrown exception."
  [req]
  (let [result (execute req)]
    (if (error/anomaly? result)
      (throw (ex-info (or (:cognitect.anomalies/message result) "Request failed") result))
      result)))

(defn execute-async
  "Executes the request asynchronously. Returns a `CompletableFuture`
  that resolves to the same value [[execute]] would return."
  [req]
  (log-request req)
  (let [t (transport/resolve-transport req)
        start (System/nanoTime)
        handle (fn [raw]
                 (let [result (handle-response raw req)
                       elapsed (long (/ (- (System/nanoTime) start) 1000000))]
                   (log-response req result elapsed)
                   result))
        on-error (fn [^Throwable ex]
                   (let [result (error/from-exception ex (:service req))
                         elapsed (long (/ (- (System/nanoTime) start) 1000000))]
                     (log-response req result elapsed)
                     result))]
    (-> (transport/execute-async t (build-transport-request req))
        (.thenApply (reify java.util.function.Function
                      (apply [_ raw] (handle raw))))
        (.exceptionally (reify java.util.function.Function
                          (apply [_ ex] (on-error ex)))))))
