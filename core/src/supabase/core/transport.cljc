(ns supabase.core.transport
  "Pluggable HTTP transport for the Supabase Clojure SDK.

  All HTTP traffic is executed through an implementation of the
  [[Transport]] protocol. The default implementation wraps `hato.client`
  on the JVM and `js/fetch` on ClojureScript; service modules (auth,
  storage, postgrest, functions, realtime) never call either directly.

  Why a protocol? Three reasons:

    1. **Testability** — tests inject a fake transport that returns
       canned responses without touching the network.
    2. **Pooling** — on the JVM, a per-client transport can own a single
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

  On ClojureScript only `:string` and `:byte-array` are meaningful for
  `:as` (the latter yields a `js/Uint8Array`), and `:body` must be a
  string. Multipart bodies are assembled with `js/FormData`.

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

  On the JVM, `execute-async` returns a
  `java.util.concurrent.CompletionStage` that resolves to a response map.
  On ClojureScript it returns a `js/Promise`. The same exception handling
  rules apply. ClojureScript has no synchronous `execute` (fetch is
  async-only); calling it throws."
  #?(:clj (:require [hato.client :as hc]))
  #?(:clj (:import (java.net.http HttpClient HttpClient$Redirect HttpClient$Version)
                   (java.time Duration)))
  #?(:cljs (:require [clojure.string :as str])))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol Transport
  "HTTP transport contract used by the Supabase SDK."
  (execute [this request]
    "Executes a request synchronously and returns a Hato-style response map.
    Not available on ClojureScript (throws); use `execute-async`.")
  (execute-async [this request]
    "Executes a request asynchronously. Returns a CompletionStage (JVM) or
    js/Promise (ClojureScript) that resolves to a Hato-style response map."))

#?(:clj
   (do
     ;; -----------------------------------------------------------------------
     ;; HttpClient builder (per-client pool)
     ;; -----------------------------------------------------------------------

     (defn- ms->duration [ms]
       (when ms (Duration/ofMillis ms)))

     (defn build-http-client
       "Builds a `java.net.http.HttpClient` configured from the given pool
       options map. All keys optional. JVM only.

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

     ;; -----------------------------------------------------------------------
     ;; Default Hato-backed transport (JVM)
     ;; -----------------------------------------------------------------------

     (defrecord HatoTransport [http-client]
       Transport
       (execute [_ request]
         (hc/request (cond-> request
                       http-client (assoc :http-client http-client))))
       (execute-async [_ request]
         (hc/request (cond-> (assoc request :async? true)
                       http-client (assoc :http-client http-client)))))

     (defn hato-transport
       "Creates a [[Transport]] backed by Hato. JVM only.

       With no argument, uses Hato's default `HttpClient` (which has its own
       internal pool but is not configurable per Supabase client).

       With a pool options map, builds a dedicated `HttpClient` per the
       options in [[build-http-client]]:

           (hato-transport {:connect-timeout 5000 :version :http-2})"
       ([] (->HatoTransport nil))
       ([pool-opts]
        (->HatoTransport (when (seq pool-opts) (build-http-client pool-opts)))))))

#?(:cljs
   (do
     ;; -----------------------------------------------------------------------
     ;; Fetch-backed transport (ClojureScript)
     ;; -----------------------------------------------------------------------

     (defn- append-query
       "Appends `:query-params` to `url`, URL-encoding keys and values."
       [url query-params]
       (if (seq query-params)
         (let [qs (->> query-params
                       (map (fn [[k v]]
                              (str (js/encodeURIComponent (name k)) "="
                                   (js/encodeURIComponent (str v)))))
                       (str/join "&"))]
           (str url (if (str/includes? url "?") "&" "?") qs))
         url))

     (defn- ->body
       "Builds the fetch body: FormData for `:multipart`, raw otherwise."
       [{:keys [multipart body]}]
       (if (seq multipart)
         (let [form (js/FormData.)]
           (doseq [{:keys [name content filename content-type]} multipart]
             (let [value (if (and content-type (instance? js/Blob content))
                           (js/Blob. #js [content] #js {:type content-type})
                           content)]
               (if filename
                 (.append form name value filename)
                 (.append form name value))))
           form)
         body))

     (defn- response-headers
       "Converts a `js/Headers` object into a plain map with lower-case keys."
       [headers]
       (let [acc (atom {})]
         (.forEach headers (fn [v k] (swap! acc assoc (str/lower-case k) v)))
         @acc))

     (defn- read-body
       "Reads a `js/Response` body per `:as` (`:string` or `:byte-array`)."
       [^js resp as]
       (if (= as :byte-array)
         (.then (.arrayBuffer resp) (fn [buf] (js/Uint8Array. buf)))
         (.text resp)))

     (defn- fetch-request
       "Executes a transport request via `js/fetch`, honoring `:timeout`
       (ms) with an `AbortController`. Returns a js/Promise of the
       response map. A timeout rejection is a DOMException named
       `AbortError`, which `supabase.core.retry` treats as transient."
       [{:keys [method url headers query-params body multipart as timeout] :as request}]
       (let [controller (js/AbortController.)
             init (cond-> {:method (-> method name str/upper-case)
                           :headers (clj->js headers)
                           :signal (.-signal controller)}
                    (or body multipart)
                    (assoc :body (->body request)))
             timer (when timeout
                     (js/setTimeout (fn [] (.abort controller)) timeout))]
         (-> (js/fetch (append-query url query-params) (clj->js init))
             (.then (fn [^js resp]
                      (.then (read-body resp as)
                             (fn [body]
                               {:status  (.-status resp)
                                :headers (response-headers (.-headers resp))
                                :body    body
                                :uri     (.-url resp)}))))
             (.finally (fn [] (when timer (js/clearTimeout timer)))))))

     (defrecord FetchTransport []
       Transport
       (execute [_ _]
         (throw (js/Error. "supabase: synchronous execute is not available on ClojureScript; use execute-async")))
       (execute-async [_ request]
         (fetch-request request)))

     (defn fetch-transport
       "Creates a [[Transport]] backed by `js/fetch`. ClojureScript only.

       Works in browsers and in Node.js >= 18 (global fetch). Timeouts are
       enforced with an `AbortController`."
       []
       (->FetchTransport))))

;; ---------------------------------------------------------------------------
;; Resolution
;; ---------------------------------------------------------------------------

(def default-transport
  "Shared default transport used when none is configured on the
  request or client. Hato on the JVM, `js/fetch` on ClojureScript."
  #?(:clj (hato-transport)
     :cljs (fetch-transport)))

(defn resolve-transport
  "Picks the transport for a request: explicit request `:transport`
  wins, then client `:transport`, then [[default-transport]]."
  [req]
  (or (:transport req)
      (get-in req [:client :transport])
      default-transport))
