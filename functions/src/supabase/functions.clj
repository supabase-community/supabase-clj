(ns supabase.functions
  "Invoke Supabase Edge Functions.

  Provides a single entry point, [[invoke]], that issues an HTTP request
  to the project's `/functions/v1/<name>` endpoint and returns either
  `{:status :body :headers}` or an anomaly map.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.functions :as fns])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (fns/invoke c \"hello\" {:body {:name \"world\"}})
      ;; => {:status 200, :body {:message \"Hello, world!\"}, :headers {...}}

  ## Body encoding

  The body type drives `content-type`:

    - `map`              → `application/json`
    - printable `String` → `text/plain`
    - `byte[]`           → `application/octet-stream`
    - `File`/`InputStream` → caller sets `content-type`

  A `content-type` already present in `:headers` always wins.

  ## Response decoding

  `:response-as` controls how the body is returned:

    - `:auto`       — pick a decoder from the response `content-type` (default)
    - `:json`       — always JSON-decode the body
    - `:text`       — always return a UTF-8 string
    - `:byte-array` — raw `byte[]`
    - `:stream`     — `java.io.InputStream` (caller closes)

  ## Errors

  Network failures become `{:supabase/code :functions-fetch-error}`.
  Edge runtime relay errors (502/504) become `:functions-relay-error`.
  404 becomes `:functions-not-found`. All other non-2xx responses
  become `:functions-http-error`. The HTTP body (if any) is preserved
  under `:http/body`."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.functions.specs :as specs]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn true}))

;; ---------------------------------------------------------------------------
;; Body encoding
;; ---------------------------------------------------------------------------

(defn- printable-string?
  "Best-effort check: any non-whitespace character below 0x20 flags the
  string as binary-ish, so we send it as `application/octet-stream`."
  [^String s]
  (every? (fn [^Character c]
            (or (Character/isWhitespace c)
                (>= (int c) 0x20)))
          s))

(defn- default-content-type
  "Picks a content-type for the body or returns nil when the caller
  must set one themselves (Files, InputStreams)."
  [body]
  (cond
    (nil? body)    nil
    (map? body)    "application/json"
    (bytes? body)  "application/octet-stream"
    (string? body) (if (printable-string? body)
                     "text/plain"
                     "application/octet-stream")
    :else          nil))

(defn- header-key-name [k]
  (cond
    (keyword? k) (name k)
    (string? k)  k
    :else        (str k)))

(defn- find-content-type
  "Case-insensitive lookup of any `content-type` value in a header map."
  [headers]
  (some (fn [[k v]]
          (when (= "content-type" (str/lower-case (header-key-name k)))
            v))
        (or headers {})))

(defn- apply-body
  "Sets the body on the request and pins `content-type` correctly.

  Precedence:
    1. caller-supplied `content-type` in `custom-headers` wins
    2. type-derived default (`default-content-type` body)
    3. whatever `http/with-body` already set (for map bodies it sets
       `application/json` automatically)"
  [req body custom-headers]
  (if (nil? body)
    req
    (let [user-ct    (find-content-type custom-headers)
          default-ct (default-content-type body)
          req'       (http/with-body req body)]
      (cond
        user-ct    (http/with-headers req' {"content-type" user-ct})
        default-ct (http/with-headers req' {"content-type" default-ct})
        :else      req'))))

;; ---------------------------------------------------------------------------
;; Response decoding
;; ---------------------------------------------------------------------------

(defn- header
  "Case-insensitive header lookup."
  [headers k]
  (when (and headers (string? k))
    (or (get headers k)
        (get headers (str/lower-case k))
        (some (fn [[hk hv]]
                (when (and (string? hk)
                           (= (str/lower-case hk) (str/lower-case k)))
                  hv))
              headers))))

(defn- json-decode-safe [body]
  (if (string? body)
    (try (json/read-value body json-mapper)
         (catch Exception _ body))
    body))

(defn- pick-hato-as
  "Hato `:as` value derived from the caller's `:response-as` choice."
  [response-as]
  (case (or response-as :auto)
    (:auto :json :text) :string
    :byte-array         :byte-array
    :stream             :stream))

(defn- decode-body
  "Runs the second-pass decoder based on `:response-as` and (for
  `:auto`) the response content-type."
  [body content-type response-as]
  (case (or response-as :auto)
    :auto       (cond
                  (nil? content-type) body
                  (str/starts-with? content-type "application/json")
                  (json-decode-safe body)
                  :else body)
    :json       (json-decode-safe body)
    :text       body
    :byte-array body
    :stream     body))

;; ---------------------------------------------------------------------------
;; Error mapping
;; ---------------------------------------------------------------------------

(defn- functions-error-parser
  "Maps Edge Functions HTTP errors to anomalies with a Functions-specific
  `:supabase/code`. Used via `http/with-error-parser`."
  [status body _headers _service]
  (let [base (error/from-http-response status body :functions)]
    (assoc base :supabase/code
           (case (long status)
             404 :functions-not-found
             502 :functions-relay-error
             504 :functions-relay-error
             :functions-http-error))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn update-auth
  "Returns a copy of `client` with `access-token` swapped in. Thin
  wrapper over `supabase.core.client/update-access-token`."
  [client access-token]
  (client/update-access-token client access-token))

(defn- effective-client
  "Returns a per-call client copy if `:access-token` was supplied,
  otherwise the original client."
  [client {:keys [access-token]}]
  (if access-token
    (update-auth client access-token)
    client))

(defn invoke
  "Invokes the Edge Function named `function-name` and returns
  `{:status :body :headers}` on success, an anomaly on failure.

  ## Options (all optional)

    - `:body`         — request body. Maps are JSON-encoded; printable
                        strings sent as `text/plain`; byte arrays as
                        `application/octet-stream`. File/InputStream
                        pass through (caller sets `content-type`).
    - `:headers`      — extra HTTP headers.
    - `:method`       — `:get`, `:post` (default), `:put`, `:patch`, `:delete`.
    - `:region`       — region keyword. `:any` is a no-op.
    - `:response-as`  — `:auto` (default), `:json`, `:text`, `:byte-array`,
                        `:stream`.
    - `:timeout`      — milliseconds (default 15000).
    - `:access-token` — override the client's access token for this call.

  ## Examples

      (invoke client \"hello\" {:body {:name \"world\"}})
      (invoke client \"csv\" {:response-as :stream})
      (invoke client \"resize\" {:method :get :region :us-east-1})"
  ([client function-name] (invoke client function-name {}))
  ([client function-name opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/InvokeOpts opts)
       (let [{:keys [body headers method region response-as timeout]} opts
             method (or method :post)
             timeout (or timeout 15000)
             custom-headers (or headers {})
             eff-client (effective-client client opts)
             hato-as (pick-hato-as response-as)
             base-req (-> (http/request eff-client)
                          (http/with-service-url :functions-url (str "/" function-name))
                          (http/with-method method)
                          (http/with-error-parser functions-error-parser)
                          (http/with-timeout timeout)
                          (http/with-headers custom-headers)
                          ;; Disable core's default JSON decoder; we own decoding here.
                          (http/with-decoder identity)
                          (http/with-response-as hato-as))
             req (cond-> base-req
                   (some? body) (apply-body body custom-headers)
                   (and region (not= :any region))
                   (http/with-headers {"x-region" (name region)}))
             resp (http/execute req)]
         (if (error/anomaly? resp)
           resp
           (let [ct (header (:headers resp) "content-type")]
             (update resp :body decode-body ct response-as)))))))
