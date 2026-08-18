(ns supabase.core.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.core.transport :as transport])
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent CompletableFuture)))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key-123")
(def test-client (client/make-client base-url api-key))

;; ---------------------------------------------------------------------------
;; request — initialization
;; ---------------------------------------------------------------------------

(deftest request-test
  (testing "initializes with :get method"
    (let [req (http/request test-client)]
      (is (= :get (:method req)))))

  (testing "url starts as nil"
    (let [req (http/request test-client)]
      (is (nil? (:url req)))))

  (testing "body starts as nil"
    (let [req (http/request test-client)]
      (is (nil? (:body req)))))

  (testing "service starts as nil"
    (let [req (http/request test-client)]
      (is (nil? (:service req)))))

  (testing "includes authorization header with Bearer token"
    (let [req (http/request test-client)
          auth-header (get-in req [:headers "authorization"])]
      (is (= (str "Bearer " api-key) auth-header))))

  (testing "includes apikey header"
    (let [req (http/request test-client)]
      (is (= api-key (get-in req [:headers "apikey"])))))

  (testing "includes client global headers"
    (let [req (http/request test-client)]
      (is (some? (get-in req [:headers "x-client-info"])))))

  (testing "holds reference to client"
    (let [req (http/request test-client)]
      (is (= test-client (:client req)))))

  (testing "query starts as empty map"
    (let [req (http/request test-client)]
      (is (= {} (:query req))))))

;; ---------------------------------------------------------------------------
;; request — with custom access token
;; ---------------------------------------------------------------------------

(deftest request-custom-token-test
  (testing "uses updated access token in authorization header"
    (let [custom-client (client/update-access-token test-client "custom-jwt")
          req (http/request custom-client)]
      (is (= "Bearer custom-jwt" (get-in req [:headers "authorization"]))))))

;; ---------------------------------------------------------------------------
;; with-service-url
;; ---------------------------------------------------------------------------

(deftest with-service-url-test
  (testing "resolves auth URL and appends path"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :auth-url "/token"))]
      (is (= (str base-url "/auth/v1/token") (:url req)))
      (is (= :auth (:service req)))))

  (testing "resolves storage URL and appends path"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :storage-url "/bucket/photos"))]
      (is (= (str base-url "/storage/v1/bucket/photos") (:url req)))
      (is (= :storage (:service req)))))

  (testing "resolves functions URL"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :functions-url "/hello"))]
      (is (= (str base-url "/functions/v1/hello") (:url req)))
      (is (= :functions (:service req)))))

  (testing "resolves database URL"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :database-url "/users"))]
      (is (= (str base-url "/rest/v1/users") (:url req)))
      (is (= :database (:service req)))))

  (testing "resolves realtime URL"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :realtime-url "/channel"))]
      (is (= (str base-url "/realtime/v1/channel") (:url req)))
      (is (= :realtime (:service req))))))

;; ---------------------------------------------------------------------------
;; with-method
;; ---------------------------------------------------------------------------

(deftest with-method-test
  (testing "sets method to :post"
    (let [req (-> (http/request test-client)
                  (http/with-method :post))]
      (is (= :post (:method req)))))

  (testing "sets method to :put"
    (is (= :put (:method (-> (http/request test-client) (http/with-method :put))))))

  (testing "sets method to :patch"
    (is (= :patch (:method (-> (http/request test-client) (http/with-method :patch))))))

  (testing "sets method to :delete"
    (is (= :delete (:method (-> (http/request test-client) (http/with-method :delete))))))

  (testing "overrides previous method"
    (let [req (-> (http/request test-client)
                  (http/with-method :post)
                  (http/with-method :put))]
      (is (= :put (:method req))))))

;; ---------------------------------------------------------------------------
;; with-body
;; ---------------------------------------------------------------------------

(deftest with-body-test
  (testing "encodes map body as JSON string"
    (let [req (-> (http/request test-client)
                  (http/with-body {:email "test@example.com"}))]
      (is (string? (:body req)))
      (let [parsed (json/read-value (:body req))]
        (is (= "test@example.com" (get parsed "email"))))))

  (testing "sets content-type to application/json for map bodies"
    (let [req (-> (http/request test-client)
                  (http/with-body {:foo "bar"}))]
      (is (= "application/json" (get-in req [:headers "content-type"])))))

  (testing "passes string body through as-is"
    (let [req (-> (http/request test-client)
                  (http/with-body "raw body"))]
      (is (= "raw body" (:body req)))))

  (testing "does not set content-type for string bodies"
    (let [req (-> (http/request test-client)
                  (http/with-body "raw body"))]
      ;; should not override existing content-type, but also not auto-set
      (is (not= "application/json" (get-in req [:headers "content-type"])))))

  (testing "passes nil body through"
    (let [req (-> (http/request test-client)
                  (http/with-body nil))]
      (is (nil? (:body req))))))

;; ---------------------------------------------------------------------------
;; with-headers
;; ---------------------------------------------------------------------------

(deftest with-headers-test
  (testing "merges new headers"
    (let [req (-> (http/request test-client)
                  (http/with-headers {"prefer" "return=representation"}))]
      (is (= "return=representation" (get-in req [:headers "prefer"])))))

  (testing "preserves existing headers"
    (let [req (-> (http/request test-client)
                  (http/with-headers {"prefer" "return=representation"}))]
      (is (some? (get-in req [:headers "authorization"])))
      (is (some? (get-in req [:headers "apikey"])))))

  (testing "later headers override earlier ones"
    (let [req (-> (http/request test-client)
                  (http/with-headers {"x-custom" "first"})
                  (http/with-headers {"x-custom" "second"}))]
      (is (= "second" (get-in req [:headers "x-custom"])))))

  (testing "can add multiple headers at once"
    (let [req (-> (http/request test-client)
                  (http/with-headers {"x-one" "1" "x-two" "2"}))]
      (is (= "1" (get-in req [:headers "x-one"])))
      (is (= "2" (get-in req [:headers "x-two"]))))))

;; ---------------------------------------------------------------------------
;; with-query
;; ---------------------------------------------------------------------------

(deftest with-query-test
  (testing "sets query parameters"
    (let [req (-> (http/request test-client)
                  (http/with-query {"select" "*" "order" "id.asc"}))]
      (is (= "*" (get-in req [:query "select"])))
      (is (= "id.asc" (get-in req [:query "order"])))))

  (testing "merges query parameters"
    (let [req (-> (http/request test-client)
                  (http/with-query {"select" "*"})
                  (http/with-query {"order" "id.asc"}))]
      (is (= "*" (get-in req [:query "select"])))
      (is (= "id.asc" (get-in req [:query "order"])))))

  (testing "later params override earlier ones"
    (let [req (-> (http/request test-client)
                  (http/with-query {"limit" "10"})
                  (http/with-query {"limit" "20"}))]
      (is (= "20" (get-in req [:query "limit"]))))))

;; ---------------------------------------------------------------------------
;; Full request pipeline (no execution)
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; with-multipart
;; ---------------------------------------------------------------------------

(deftest with-multipart-test
  (testing "attaches multipart parts and clears body"
    (let [parts [{:name "file" :content (byte-array [1 2 3])
                  :content-type "image/png" :filename "a.png"}]
          req (-> (http/request test-client)
                  (http/with-body {:ignored true})
                  (http/with-multipart parts))]
      (is (= parts (:multipart req)))
      (is (nil? (:body req))))))

;; ---------------------------------------------------------------------------
;; with-response-as / with-decoder / with-error-parser
;; ---------------------------------------------------------------------------

(deftest response-knobs-test
  (testing "with-response-as stores keyword"
    (is (= :stream (:response-as (-> (http/request test-client)
                                     (http/with-response-as :stream))))))
  (testing "with-decoder stores fn"
    (is (fn? (:decoder (-> (http/request test-client)
                           (http/with-decoder identity))))))
  (testing "with-error-parser stores fn"
    (is (fn? (:error-parser (-> (http/request test-client)
                                (http/with-error-parser (fn [_ _ _ _])))))))
  (testing "with-timeout stores ms"
    (is (= 5000 (:timeout (-> (http/request test-client)
                              (http/with-timeout 5000))))))
  (testing "with-logging flag"
    (is (true? (:log? (-> (http/request test-client) http/with-logging))))
    (is (false? (:log? (-> (http/request test-client) (http/with-logging false)))))))

;; ---------------------------------------------------------------------------
;; execute via stub Transport
;; ---------------------------------------------------------------------------

(defn stub-transport
  "Creates a transport that captures the last request and returns
  the supplied response (or applies the supplied responder fn)."
  ([resp]
   (let [captured (atom nil)]
     [captured
      (reify transport/Transport
        (execute [_ req] (reset! captured req) (if (fn? resp) (resp req) resp))
        (execute-async [_ req]
          (reset! captured req)
          (CompletableFuture/completedFuture (if (fn? resp) (resp req) resp))))])))

(deftest execute-success-test
  (testing "decodes JSON string response by default"
    (let [[_ t] (stub-transport {:status 200 :body "{\"ok\":true}" :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/health")
                  (http/with-transport t)
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= {:ok true} (:body res)))))

  (testing "passes through stream body without decoding"
    (let [stream (ByteArrayInputStream. (.getBytes "raw"))
          [_ t] (stub-transport {:status 200 :body stream :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :storage-url "/object/x")
                  (http/with-response-as :stream)
                  (http/with-transport t)
                  http/execute)]
      (is (identical? stream (:body res)))))

  (testing "custom decoder is honoured"
    (let [[_ t] (stub-transport {:status 200 :body "raw" :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-decoder str/upper-case)
                  (http/with-transport t)
                  http/execute)]
      (is (= "RAW" (:body res))))))

(deftest execute-error-test
  (testing "maps 404 to anomaly via default error parser"
    (let [[_ t] (stub-transport {:status 404 :body "{\"message\":\"nope\"}" :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/user")
                  (http/with-transport t)
                  http/execute)]
      (is (error/anomaly? res))
      (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category res)))
      (is (= 404 (:http/status res)))
      (is (= :auth (:supabase/service res)))))

  (testing "custom error-parser wins"
    (let [[_ t] (stub-transport {:status 500 :body "boom" :headers {}})
          parser (fn [status _ _ service]
                   {:cognitect.anomalies/category :cognitect.anomalies/fault
                    :supabase/code :custom
                    :http/status status
                    :supabase/service service})
          res (-> (http/request test-client)
                  (http/with-service-url :functions-url "/x")
                  (http/with-error-parser parser)
                  (http/with-transport t)
                  http/execute)]
      (is (= :custom (:supabase/code res)))
      (is (= :functions (:supabase/service res))))))

(deftest execute-bang-test
  (testing "throws ex-info on anomaly with anomaly as ex-data"
    (let [[_ t] (stub-transport {:status 500 :body "{\"err\":\"x\"}" :headers {}})
          req (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t))]
      (try
        (http/execute! req)
        (is false "should have thrown")
        (catch Exception e
          (is (error/anomaly? (ex-data e))))))))

(deftest execute-async-test
  (testing "returns CompletableFuture resolving to decoded body"
    (let [[_ t] (stub-transport {:status 200 :body "{\"async\":true}" :headers {}})
          fut (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute-async)]
      (is (instance? CompletableFuture fut))
      (is (= {:async true} (:body @fut))))))

(deftest execute-async-cancellation-test
  (testing "cancelling the returned future cancels the in-flight request"
    (let [raw (CompletableFuture.)
          t (reify transport/Transport
              (execute [_ _] (throw (UnsupportedOperationException.)))
              (execute-async [_ _] raw))
          fut (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute-async)]
      (is (false? (.isDone raw)))
      (is (true? (future-cancel fut)))
      (is (true? (.isCancelled fut)))
      (is (true? (.isCancelled raw)) "underlying transport future is cancelled"))))

(deftest execute-exception-test
  (testing "exceptions raised by transport become fault anomalies"
    (let [t (reify transport/Transport
              (execute [_ _] (throw (RuntimeException. "boom")))
              (execute-async [_ _]
                (doto (CompletableFuture.)
                  (.completeExceptionally (RuntimeException. "boom")))))
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (error/anomaly? res))
      (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category res)))
      (is (= :exception (:supabase/code res))))))

(deftest full-pipeline-test
  (testing "builds a complete request via threading"
    (let [req (-> (http/request test-client)
                  (http/with-service-url :auth-url "/signup")
                  (http/with-method :post)
                  (http/with-body {:email "new@user.com" :password "secret"})
                  (http/with-headers {"prefer" "return=minimal"})
                  (http/with-query {"redirect_to" "https://app.com"}))]
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/signup") (:url req)))
      (is (= :auth (:service req)))
      (is (string? (:body req)))
      (is (= "application/json" (get-in req [:headers "content-type"])))
      (is (= "return=minimal" (get-in req [:headers "prefer"])))
      (is (= "https://app.com" (get-in req [:query "redirect_to"])))
      (is (some? (get-in req [:headers "authorization"])))
      (is (some? (get-in req [:headers "apikey"]))))))

;; ---------------------------------------------------------------------------
;; retries
;; ---------------------------------------------------------------------------

(defn scripted-transport
  "Transport that yields `scripts` in order (response maps or 0-arg fns,
  which may throw) and counts calls. Async mirrors sync."
  [scripts]
  (let [calls (atom 0)
        remaining (atom scripts)
        next! (fn []
                (swap! calls inc)
                (let [s (first @remaining)]
                  (swap! remaining rest)
                  (if (fn? s) (s) s)))]
    [calls
     (reify transport/Transport
       (execute [_ _] (next!))
       (execute-async [_ _] (CompletableFuture/completedFuture (next!))))]))

(def retry-client
  (client/make-client base-url api-key
                      :retries {:max-attempts 3 :initial-delay-ms 1 :max-delay-ms 2}))

(deftest execute-retry-transient-status-test
  (testing "503 then 200 retries and succeeds"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}
                                         {:status 200 :body "{\"ok\":true}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= {:ok true} (:body res)))
      (is (= 2 @calls))))

  (testing "429 is retried"
    (let [[calls t] (scripted-transport [{:status 429 :body "{}" :headers {}}
                                         {:status 200 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= 2 @calls))))

  (testing "attempts give up at max-attempts and return the anomaly"
    (let [[calls t] (scripted-transport (repeat {:status 503 :body "{}" :headers {}}))
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (error/anomaly? res))
      (is (= 503 (:http/status res)))
      (is (= 3 @calls)))))

(deftest execute-retry-after-test
  (testing "Retry-After header sets the retry delay"
    (let [events (atom [])
          [calls t] (scripted-transport [{:status 429 :body "{}" :headers {"retry-after" "1"}}
                                         {:status 200 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  (http/with-on-event (fn [e] (swap! events conj e)))
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= 2 @calls))
      (let [retry-event (first (filter #(= :request-retry (:event %)) @events))]
        (is (= 1000 (:delay-ms retry-event)))))))

(deftest execute-retry-non-transient-test
  (testing "400 is not retried"
    (let [[calls t] (scripted-transport [{:status 400 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (error/anomaly? res))
      (is (= 1 @calls))))

  (testing "500 is not retried"
    (let [[calls t] (scripted-transport [{:status 500 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (= 500 (:http/status res)))
      (is (= 1 @calls)))))

(deftest execute-retry-overrides-test
  (testing "request :retries false disables client retries"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  (http/with-retries false)
                  http/execute)]
      (is (= 503 (:http/status res)))
      (is (= 1 @calls))))

  (testing "request :retries enables retries when the client has none"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}
                                         {:status 200 :body "{}" :headers {}}])
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  (http/with-retries {:max-attempts 2 :initial-delay-ms 1 :max-delay-ms 2})
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= 2 @calls))))

  (testing "no retries configured means a single attempt"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}])
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (= 503 (:http/status res)))
      (is (= 1 @calls)))))

(deftest execute-retry-non-replayable-test
  (testing "InputStream bodies are never retried"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :storage-url "/object/x")
                  (http/with-method :post)
                  (http/with-body (ByteArrayInputStream. (.getBytes "data")))
                  (http/with-transport t)
                  http/execute)]
      (is (= 503 (:http/status res)))
      (is (= 1 @calls))))

  (testing "multipart payloads are never retried"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :storage-url "/object/x")
                  (http/with-method :post)
                  (http/with-multipart [{:name "file" :content "data"}])
                  (http/with-transport t)
                  http/execute)]
      (is (= 503 (:http/status res)))
      (is (= 1 @calls)))))

(deftest execute-retry-transient-exception-test
  (testing "connection reset is retried, then succeeds"
    (let [[calls t] (scripted-transport [(fn [] (throw (java.net.SocketException. "Connection reset")))
                                         {:status 200 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= 2 @calls))))

  (testing "non-transient exceptions are not retried"
    (let [[calls t] (scripted-transport [(fn [] (throw (RuntimeException. "boom")))])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute)]
      (is (error/anomaly? res))
      (is (= :exception (:supabase/code res)))
      (is (= 1 @calls)))))

(deftest execute-async-retry-test
  (testing "async retries a transient status and resolves"
    (let [[calls t] (scripted-transport [{:status 503 :body "{}" :headers {}}
                                         {:status 200 :body "{\"ok\":true}" :headers {}}])
          fut (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  http/execute-async)]
      (is (= {:ok true} (:body @fut)))
      (is (= 2 @calls)))))

;; ---------------------------------------------------------------------------
;; telemetry
;; ---------------------------------------------------------------------------

(deftest execute-on-event-test
  (testing "emits start and end events for a simple request"
    (let [events (atom [])
          [_ t] (stub-transport {:status 200 :body "{}" :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/health")
                  (http/with-transport t)
                  (http/with-on-event (fn [e] (swap! events conj e)))
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= [:request-start :request-end] (mapv :event @events)))
      (let [end (last @events)]
        (is (= 200 (:status end)))
        (is (= :auth (:service end)))
        (is (= :get (:method end)))
        (is (nat-int? (:elapsed-ms end))))))

  (testing "emits retry events between attempts"
    (let [events (atom [])
          [_ t] (scripted-transport [{:status 503 :body "{}" :headers {}}
                                     {:status 200 :body "{}" :headers {}}])
          res (-> (http/request retry-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  (http/with-on-event (fn [e] (swap! events conj e)))
                  http/execute)]
      (is (= 200 (:status res)))
      (is (= [:request-start :request-retry :request-start :request-end]
             (mapv :event @events)))
      (let [retry-event (second @events)]
        (is (= 1 (:attempt retry-event)))
        (is (nat-int? (:delay-ms retry-event)))
        (is (= 503 (:status retry-event))))))

  (testing "client-level :on-event applies without a request override"
    (let [events (atom [])
          c (client/make-client base-url api-key :on-event (fn [e] (swap! events conj e)))
          [_ t] (stub-transport {:status 200 :body "{}" :headers {}})
          _ (-> (http/request c)
                (http/with-service-url :auth-url "/")
                (http/with-transport t)
                http/execute)]
      (is (= [:request-start :request-end] (mapv :event @events)))))

  (testing "a throwing handler does not break the request"
    (let [_ (stub-transport {:status 200 :body "{}" :headers {}})
          [_ t] (stub-transport {:status 200 :body "{}" :headers {}})
          res (-> (http/request test-client)
                  (http/with-service-url :auth-url "/")
                  (http/with-transport t)
                  (http/with-on-event (fn [_] (throw (RuntimeException. "handler boom"))))
                  http/execute)]
      (is (= 200 (:status res)))))

  (testing "anomaly results emit end events with category and code"
    (let [events (atom [])
          [_ t] (stub-transport {:status 404 :body "{}" :headers {}})
          _ (-> (http/request test-client)
                (http/with-service-url :auth-url "/")
                (http/with-transport t)
                (http/with-on-event (fn [e] (swap! events conj e)))
                http/execute)
          end (last @events)]
      (is (= :request-end (:event end)))
      (is (= :cognitect.anomalies/not-found (:category end)))
      (is (= :not-found (:code end))))))
