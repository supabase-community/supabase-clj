(ns supabase.core.http
  "Composable HTTP request builder and executor for Supabase services.

  Requests are built as plain maps using a threading-friendly API, then
  executed synchronously via Hato. This is the backbone that all service
  modules (auth, storage, postgrest, functions, realtime) use internally.

  ## Request Map Structure

  A request map contains:

    - `:method`    — HTTP method keyword (`:get`, `:post`, `:put`, `:patch`, `:delete`)
    - `:url`       — fully resolved URL string
    - `:headers`   — map of header name to value
    - `:query`     — map of query parameter name to value
    - `:body`      — request body (map, string, or nil)
    - `:service`   — originating service keyword (`:auth`, `:storage`, etc.)
    - `:client`    — reference to the client map

  ## Usage

      (require '[supabase.core.http :as http])

      ;; Build and execute a request
      (-> (http/request client)
          (http/with-service-url :auth-url \"/signup\")
          (http/with-method :post)
          (http/with-body {:email \"user@example.com\" :password \"secret\"})
          (http/execute))

      ;; Returns {:status 200, :body {...}, :headers {...}} on success
      ;; Returns anomaly map on HTTP error (status >= 400)

      ;; Throwing variant for those who prefer exceptions
      (-> (http/request client)
          (http/with-service-url :auth-url \"/signup\")
          (http/with-method :post)
          (http/with-body {:email \"user@example.com\" :password \"secret\"})
          (http/execute!))

      ;; Async variant returning CompletableFuture
      (-> (http/request client)
          (http/with-service-url :functions-url \"/hello\")
          (http/with-method :post)
          (http/with-body {:name \"world\"})
          (http/execute-async))"
  (:require [clojure.string :as str]
            [hato.client :as hc]
            [jsonista.core :as json]
            [supabase.core.error :as error]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn true}))

;; ---------------------------------------------------------------------------
;; Request construction
;; ---------------------------------------------------------------------------

(defn request
  "Initializes a request map from a client, pre-populating auth headers.

  The request starts with `:method :get` and includes the client's global
  headers plus `authorization` and `apikey` headers derived from the client.

      (request client)
      ;; => {:method :get, :headers {...}, :client client, ...}"
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
  appending `path`.

  `service-url-key` is a keyword like `:auth-url`, `:storage-url`, etc.
  The `:service` field is inferred from the key name.

      (-> (request client)
          (with-service-url :auth-url \"/token\"))"
  [req service-url-key path]
  (let [base-url (get-in req [:client service-url-key])
        service (keyword (first (str/split (name service-url-key) #"-")))]
    (assoc req
           :url (str base-url path)
           :service service)))

(defn with-method
  "Sets the HTTP method for the request.

      (with-method req :post)"
  [req method]
  (assoc req :method method))

(defn with-body
  "Sets the request body. Maps are JSON-encoded automatically.
  Strings and nil are passed through as-is.

      (with-body req {:email \"user@example.com\"})"
  [req body]
  (if (map? body)
    (-> req
        (assoc :body (json/write-value-as-string body))
        (update :headers assoc "content-type" "application/json"))
    (assoc req :body body)))

(defn with-headers
  "Merges additional headers into the request. Later values override earlier
  ones for the same header name.

      (with-headers req {\"prefer\" \"return=representation\"})"
  [req headers]
  (update req :headers merge headers))

(defn with-query
  "Merges query parameters into the request. Later values override earlier
  ones for the same parameter name.

      (with-query req {\"select\" \"*\" \"order\" \"id.asc\"})"
  [req params]
  (update req :query merge params))

(defn with-response-as
  "Sets how Hato should coerce the response body. Defaults to `:string`,
  which `handle-response` then JSON-decodes. Use `:byte-array`, `:stream`,
  or `:input-stream` for binary responses (storage downloads). Non-string
  bodies skip the JSON parse step.

      (with-response-as req :byte-array)"
  [req as]
  (assoc req :response-as as))

;; ---------------------------------------------------------------------------
;; Response handling
;; ---------------------------------------------------------------------------

(defn- parse-response-body
  "Attempts to parse a response body as JSON. Returns the raw body on failure."
  [body]
  (if (string? body)
    (try
      (json/read-value body json-mapper)
      (catch Exception _ body))
    body))

(defn- handle-response
  "Converts a Hato response into either a success map or an anomaly map.

  Responses with status < 400 return `{:status s, :body b, :headers h}`.
  Responses with status >= 400 return an anomaly map via `error/from-http-response`.

  When `parse-body?` is false (binary response), the body is passed through
  unchanged on success. Error responses still attempt JSON parsing so the
  caller gets a structured anomaly."
  [{:keys [status body headers]} service parse-body?]
  (if (< status 400)
    {:status status
     :body (if parse-body? (parse-response-body body) body)
     :headers headers}
    (error/from-http-response status (parse-response-body body) service)))

;; ---------------------------------------------------------------------------
;; Execution
;; ---------------------------------------------------------------------------

(defn- build-hato-request
  "Transforms an internal request map into Hato's expected format."
  [{:keys [method url headers query body response-as]}]
  (cond-> {:method method
           :url url
           :headers headers
           :as (or response-as :string)}
    (seq query) (assoc :query-params query)
    body        (assoc :body body)))

(defn execute
  "Executes the request synchronously via Hato.

  Returns a response map on success (status < 400) or an anomaly map on error.

      (-> (request client)
          (with-service-url :auth-url \"/token\")
          (with-method :post)
          (with-body {:grant-type \"password\" :email \"a@b.com\" :password \"x\"})
          (execute))
      ;; => {:status 200, :body {...}, :headers {...}}"
  [{:keys [service response-as] :as req}]
  (try
    (let [resp (hc/request (build-hato-request req))]
      (handle-response resp service (or (nil? response-as) (= :string response-as))))
    (catch Exception e
      (error/from-exception e service))))

(defn execute!
  "Like `execute`, but throws an `ex-info` on error instead of returning an anomaly map.

  The anomaly map is attached as the ex-data of the thrown exception.

      (try
        (-> (request client)
            (with-service-url :auth-url \"/token\")
            (with-method :post)
            (execute!))
        (catch Exception e
          (ex-data e))) ;; => anomaly map"
  [req]
  (let [result (execute req)]
    (if (error/anomaly? result)
      (throw (ex-info (:cognitect.anomalies/message result "Request failed") result))
      result)))

(defn execute-async
  "Executes the request asynchronously, returning a `CompletableFuture`.

  The future completes with the same value `execute` would return — either
  a response map or an anomaly map.

      @(-> (request client)
           (with-service-url :functions-url \"/hello\")
           (with-method :post)
           (with-body {:name \"world\"})
           (execute-async))"
  [{:keys [service response-as] :as req}]
  (let [hato-req (build-hato-request req)
        parse? (or (nil? response-as) (= :string response-as))]
    (-> (hc/request (assoc hato-req :async? true))
        (.thenApply (reify java.util.function.Function
                      (apply [_ resp]
                        (handle-response resp service parse?))))
        (.exceptionally (reify java.util.function.Function
                          (apply [_ ex]
                            (error/from-exception ex service)))))))
