(ns supabase.core.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

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
