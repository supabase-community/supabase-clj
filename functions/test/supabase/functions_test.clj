(ns supabase.functions-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]
            [supabase.functions :as fns])
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent CompletableFuture)))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key")
(def test-client (client/make-client base-url api-key))

(defn stub-transport
  "Captures the last request and returns the supplied response."
  [response-or-fn]
  (let [captured (atom nil)
        t (reify transport/Transport
            (execute [_ req]
              (reset! captured req)
              (if (fn? response-or-fn) (response-or-fn req) response-or-fn))
            (execute-async [_ req]
              (reset! captured req)
              (CompletableFuture/completedFuture
               (if (fn? response-or-fn) (response-or-fn req) response-or-fn))))]
    [captured t]))

(defn with-transport
  "Returns a client with the stub transport injected."
  [c t]
  (assoc c :transport t))

;; ---------------------------------------------------------------------------
;; URL + method + headers
;; ---------------------------------------------------------------------------

(deftest invoke-builds-correct-request-test
  (testing "POSTs to /functions/v1/<name> by default"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {"content-type" "application/json"}})
          c (with-transport test-client t)]
      (fns/invoke c "hello")
      (is (= :post (:method @captured)))
      (is (= (str base-url "/functions/v1/hello") (:url @captured)))
      (is (= "Bearer test-api-key" (get-in @captured [:headers "authorization"])))))

  (testing "honours :method override"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:method :get})
      (is (= :get (:method @captured)))))

  (testing "merges custom :headers"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:headers {"x-trace" "abc"}})
      (is (= "abc" (get-in @captured [:headers "x-trace"]))))))

;; ---------------------------------------------------------------------------
;; Regions
;; ---------------------------------------------------------------------------

(deftest region-header-test
  (testing "named region sets x-region"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:region :us-east-1})
      (is (= "us-east-1" (get-in @captured [:headers "x-region"])))))

  (testing ":any region does not set x-region"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:region :any})
      (is (not (contains? (:headers @captured) "x-region")))))

  (testing "no region key does not set x-region"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x")
      (is (not (contains? (:headers @captured) "x-region"))))))

;; ---------------------------------------------------------------------------
;; Body encoding
;; ---------------------------------------------------------------------------

(deftest body-encoding-test
  (testing "map body → JSON"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:body {:name "world"}})
      (is (= "application/json" (get-in @captured [:headers "content-type"])))
      (is (= {"name" "world"} (json/read-value (:body @captured))))))

  (testing "printable string body → text/plain"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:body "hi"})
      (is (= "text/plain" (get-in @captured [:headers "content-type"])))
      (is (= "hi" (:body @captured)))))

  (testing "byte-array body → application/octet-stream"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})
          payload (byte-array [1 2 3])]
      (fns/invoke (with-transport test-client t) "x" {:body payload})
      (is (= "application/octet-stream" (get-in @captured [:headers "content-type"])))
      (is (identical? payload (:body @captured)))))

  (testing "explicit content-type wins"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x"
                  {:body {:a 1} :headers {"content-type" "application/cbor"}})
      (is (= "application/cbor" (get-in @captured [:headers "content-type"]))))))

;; ---------------------------------------------------------------------------
;; Response decoding
;; ---------------------------------------------------------------------------

(deftest response-decoding-test
  (testing ":auto with json content-type → decoded map"
    (let [[_ t] (stub-transport {:status 200
                                 :body "{\"ok\":true}"
                                 :headers {"content-type" "application/json"}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (= {:ok true} (:body res)))))

  (testing ":auto with text content-type → raw string"
    (let [[_ t] (stub-transport {:status 200
                                 :body "hello"
                                 :headers {"content-type" "text/plain"}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (= "hello" (:body res)))))

  (testing ":json forces JSON decode regardless of content-type"
    (let [[_ t] (stub-transport {:status 200
                                 :body "{\"a\":1}"
                                 :headers {"content-type" "text/plain"}})
          res (fns/invoke (with-transport test-client t) "x" {:response-as :json})]
      (is (= {:a 1} (:body res)))))

  (testing ":text keeps raw string regardless of content-type"
    (let [[_ t] (stub-transport {:status 200
                                 :body "{\"a\":1}"
                                 :headers {"content-type" "application/json"}})
          res (fns/invoke (with-transport test-client t) "x" {:response-as :text})]
      (is (= "{\"a\":1}" (:body res)))))

  (testing ":byte-array returns bytes untouched"
    (let [bytes (byte-array [1 2 3])
          [_ t] (stub-transport {:status 200 :body bytes :headers {}})
          res (fns/invoke (with-transport test-client t) "x" {:response-as :byte-array})]
      (is (identical? bytes (:body res)))))

  (testing ":stream returns InputStream untouched"
    (let [stream (ByteArrayInputStream. (.getBytes "raw"))
          [_ t] (stub-transport {:status 200 :body stream :headers {}})
          res (fns/invoke (with-transport test-client t) "x" {:response-as :stream})]
      (is (identical? stream (:body res))))))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(deftest error-mapping-test
  (testing "404 → :functions-not-found"
    (let [[_ t] (stub-transport {:status 404 :body "{\"err\":\"x\"}" :headers {}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (error/anomaly? res))
      (is (= :functions-not-found (:supabase/code res)))
      (is (= :functions (:supabase/service res)))))

  (testing "502 → :functions-relay-error"
    (let [[_ t] (stub-transport {:status 502 :body "" :headers {}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (= :functions-relay-error (:supabase/code res)))))

  (testing "504 → :functions-relay-error"
    (let [[_ t] (stub-transport {:status 504 :body "" :headers {}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (= :functions-relay-error (:supabase/code res)))))

  (testing "generic non-2xx → :functions-http-error"
    (let [[_ t] (stub-transport {:status 400 :body "{\"err\":\"bad\"}" :headers {}})
          res (fns/invoke (with-transport test-client t) "x")]
      (is (= :functions-http-error (:supabase/code res)))))

  (testing "transport exception → fault anomaly"
    (let [t (reify transport/Transport
              (execute [_ _] (throw (RuntimeException. "boom")))
              (execute-async [_ _]
                (doto (CompletableFuture.) (.completeExceptionally (RuntimeException. "boom")))))
          res (fns/invoke (with-transport test-client t) "x")]
      (is (error/anomaly? res))
      (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category res))))))

;; ---------------------------------------------------------------------------
;; Per-call access token + update-auth
;; ---------------------------------------------------------------------------

(deftest access-token-override-test
  (testing ":access-token swaps Authorization for one call"
    (let [[captured t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:access-token "user-jwt"})
      (is (= "Bearer user-jwt" (get-in @captured [:headers "authorization"])))))

  (testing "original client is unchanged"
    (let [[_ t] (stub-transport {:status 200 :body "{}" :headers {}})]
      (fns/invoke (with-transport test-client t) "x" {:access-token "user-jwt"})
      (is (= api-key (:access-token test-client))))))

(deftest update-auth-test
  (testing "returns a new client with the swapped token"
    (let [c2 (fns/update-auth test-client "another")]
      (is (= "another" (:access-token c2)))
      (is (= api-key (:access-token test-client))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest validation-test
  (testing "invalid client returns anomaly"
    (is (error/anomaly? (fns/invoke {} "x"))))

  (testing "invalid opts returns anomaly"
    (let [res (fns/invoke test-client "x" {:method :wrong})]
      (is (error/anomaly? res))
      (is (= :functions (:supabase/service res))))))
