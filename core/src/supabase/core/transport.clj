(ns supabase.core.transport
  "Pluggable HTTP transport for the Supabase Clojure SDK.

  All HTTP traffic is executed through an implementation of the
  [[Transport]] protocol. The default implementation is a thin wrapper
  around `hato.client`; service modules (auth, storage, postgrest,
  functions, realtime) never call Hato directly.

  Why a protocol? Three reasons:

    1. **Testability** — tests inject a fake transport that returns
       canned responses without touching the network.
    2. **Pooling** — a per-client transport can own a single
       `HttpClient` instance with custom timeouts, pool sizing, and
       HTTP version selection.
    3. **Adapters** — callers who need clj-http, http-kit, or a custom
       HTTP stack can implement [[Transport]] and inject it via the
       client map (`:transport`) or per request.

  ## Request format

  A transport receives a lower-level request map produced by
  `supabase.core.http`:

      {:method      :post
       :url         \"https://abc.supabase.co/auth/v1/token\"
       :headers     {\"authorization\" \"Bearer ...\" ...}
       :query-params {\"grant_type\" \"password\"}
       :body        \"{\\\"email\\\":\\\"a@b.com\\\"}\" ;; string | bytes | File | InputStream
       :multipart   [{:name \"file\" :content #object[File ...]
                      :content-type \"image/png\" :file-name \"a.png\"}]
       :as          :string ;; :string | :byte-array | :stream | :reader
       :timeout     30000}  ;; optional

  ## Response format

  The transport must return a map with at minimum:

      {:status  200
       :headers {\"content-type\" \"application/json\" ...}
       :body    \"{...}\" ;; type depends on :as
       :uri     \"...\"   ;; optional, but recommended for logging
       :request {...}}    ;; optional echo of the request for debugging

  Exceptions are caught by `supabase.core.http`, not by the transport,
  and converted to anomalies via `supabase.core.error/from-exception`.

  ## Async

  `execute-async` returns a `java.util.concurrent.CompletionStage` (or
  `CompletableFuture`) that resolves to a response map. The same
  exception handling rules apply."
  (:require [hato.client :as hc])
  (:import (java.net.http HttpClient HttpClient$Redirect HttpClient$Version)
           (java.time Duration)))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol Transport
  "HTTP transport contract used by the Supabase SDK."
  (execute [this request]
    "Executes a request synchronously and returns a Hato-style response map.")
  (execute-async [this request]
    "Executes a request asynchronously. Returns a CompletionStage that
    resolves to a Hato-style response map."))

;; ---------------------------------------------------------------------------
;; HttpClient builder (per-client pool)
;; ---------------------------------------------------------------------------

(defn- ms->duration [ms]
  (when ms (Duration/ofMillis ms)))

(defn build-http-client
  "Builds a `java.net.http.HttpClient` configured from the given pool
  options map. All keys optional.

  ## Options

    - `:connect-timeout` — connect timeout in milliseconds
    - `:version`         — `:http-1.1` or `:http-2` (default `:http-2`)
    - `:redirect-policy` — `:always`, `:never`, `:normal` (default `:normal`)
    - `:cookie-handler`  — a `java.net.CookieHandler` instance
    - `:executor`        — an `Executor` for async requests
    - `:ssl-context`     — a custom `javax.net.ssl.SSLContext`

  Returns an `HttpClient` ready to be passed to Hato via the
  `:http-client` request key."
  [{:keys [connect-timeout version redirect-policy cookie-handler executor ssl-context]
    :or {version :http-2 redirect-policy :normal}}]
  (let [b (HttpClient/newBuilder)]
    (when-let [d (ms->duration connect-timeout)]
      (.connectTimeout b d))
    (case version
      :http-1.1 (.version b HttpClient$Version/HTTP_1_1)
      :http-2   (.version b HttpClient$Version/HTTP_2)
      nil)
    (case redirect-policy
      :always (.followRedirects b HttpClient$Redirect/ALWAYS)
      :never  (.followRedirects b HttpClient$Redirect/NEVER)
      :normal (.followRedirects b HttpClient$Redirect/NORMAL)
      nil)
    (when cookie-handler (.cookieHandler b cookie-handler))
    (when executor (.executor b executor))
    (when ssl-context (.sslContext b ssl-context))
    (.build b)))

;; ---------------------------------------------------------------------------
;; Default Hato-backed transport
;; ---------------------------------------------------------------------------

(defrecord HatoTransport [http-client]
  Transport
  (execute [_ request]
    (hc/request (cond-> request
                  http-client (assoc :http-client http-client))))
  (execute-async [_ request]
    (hc/request (cond-> (assoc request :async? true)
                  http-client (assoc :http-client http-client)))))

(defn hato-transport
  "Creates a [[Transport]] backed by Hato.

  With no argument, uses Hato's default `HttpClient` (which has its own
  internal pool but is not configurable per Supabase client).

  With a pool options map, builds a dedicated `HttpClient` per the
  options in [[build-http-client]]:

      (hato-transport {:connect-timeout 5000 :version :http-2})"
  ([] (->HatoTransport nil))
  ([pool-opts]
   (->HatoTransport (when (seq pool-opts) (build-http-client pool-opts)))))

(def default-transport
  "Shared default transport used when none is configured on the
  request or client. Backed by Hato's default `HttpClient`."
  (hato-transport))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(defn resolve-transport
  "Picks the transport for a request: explicit request `:transport`
  wins, then client `:transport`, then [[default-transport]]."
  [req]
  (or (:transport req)
      (get-in req [:client :transport])
      default-transport))
