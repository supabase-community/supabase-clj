(ns supabase.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth :as auth]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key")
(def test-client (client/make-client base-url api-key))

(def ^:private captured (atom nil))

(defn- capture-execute
  "Stub for `http/execute` — captures the built request and returns a fake
  successful response."
  [req]
  (reset! captured req)
  {:status 200 :body {:ok true} :headers {}})

(defn- run-with-capture [f]
  (reset! captured nil)
  (with-redefs [http/execute capture-execute]
    (let [result (f)]
      [result @captured])))

(defn- parse-body [req] (json/read-value (:body req)))

;; ---------------------------------------------------------------------------
;; ensure-client / ensure-valid short-circuit
;; ---------------------------------------------------------------------------

(deftest get-user-invalid-client-test
  (testing "returns anomaly when client is invalid"
    (let [result (auth/get-user {} "token")]
      (is (error/anomaly? result))
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result))))))

(deftest sign-up-invalid-client-test
  (is (error/anomaly? (auth/sign-up {} {:email "a@b.com" :password "p"}))))

(deftest sign-up-invalid-credentials-test
  (testing "missing password"
    (is (error/anomaly? (auth/sign-up test-client {:email "a@b.com"}))))
  (testing "missing email and phone"
    (is (error/anomaly? (auth/sign-up test-client {:password "p"})))))

(deftest sign-in-with-password-invalid-credentials-test
  (testing "missing password"
    (is (error/anomaly? (auth/sign-in-with-password test-client {:email "a@b.com"}))))
  (testing "missing email and phone"
    (is (error/anomaly? (auth/sign-in-with-password test-client {:password "p"})))))

(deftest sign-in-with-id-token-invalid-credentials-test
  (testing "unsupported provider"
    (is (error/anomaly? (auth/sign-in-with-id-token test-client {:provider "github" :token "x"}))))
  (testing "missing token"
    (is (error/anomaly? (auth/sign-in-with-id-token test-client {:provider "google"})))))

(deftest sign-in-with-web3-invalid-credentials-test
  (testing "unsupported chain"
    (is (error/anomaly? (auth/sign-in-with-web3 test-client {:chain "bitcoin" :message "m" :signature "s"}))))
  (testing "missing signature"
    (is (error/anomaly? (auth/sign-in-with-web3 test-client {:chain "ethereum" :message "m"})))))

(deftest sign-in-with-otp-invalid-credentials-test
  (testing "missing email and phone"
    (is (error/anomaly? (auth/sign-in-with-otp test-client {})))))

(deftest sign-in-with-sso-invalid-credentials-test
  (testing "missing provider-id and domain"
    (is (error/anomaly? (auth/sign-in-with-sso test-client {})))))

(deftest sign-in-with-oauth-invalid-credentials-test
  (testing "unsupported provider"
    (is (error/anomaly? (auth/sign-in-with-oauth test-client {:provider "myspace"})))))

;; ---------------------------------------------------------------------------
;; get-user — valid request shape
;; ---------------------------------------------------------------------------

(deftest get-user-request-test
  (let [[result req] (run-with-capture #(auth/get-user test-client "jwt-token"))]
    (testing "returns response"
      (is (= 200 (:status result))))
    (testing "GET /user"
      (is (= :get (:method req)))
      (is (= (str base-url "/auth/v1/user") (:url req))))
    (testing "uses provided access token"
      (is (= "Bearer jwt-token" (get-in req [:headers "authorization"]))))))

;; ---------------------------------------------------------------------------
;; sign-up — valid request shape
;; ---------------------------------------------------------------------------

(deftest sign-up-request-test
  (let [creds {:email "a@b.com" :password "secret"}
        [_ req] (run-with-capture #(auth/sign-up test-client creds))]
    (testing "POST /signup"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/signup") (:url req))))
    (testing "body is JSON with email + password"
      (let [body (parse-body req)]
        (is (= "a@b.com" (get body "email")))
        (is (= "secret" (get body "password")))))))

(deftest sign-up-strips-options-from-body-test
  (let [creds {:email "a@b.com" :password "secret"
               :options {:data {:k "v"} :captcha-token "tok"}}
        [_ req] (run-with-capture #(auth/sign-up test-client creds))
        body (parse-body req)]
    (is (not (contains? body "options")))
    (is (not (contains? body "data")))
    (is (not (contains? body "captcha_token")))))

;; ---------------------------------------------------------------------------
;; sign-in-with-password — valid request shape
;; ---------------------------------------------------------------------------

(deftest sign-in-with-password-request-test
  (let [creds {:email "a@b.com" :password "secret"}
        [_ req] (run-with-capture #(auth/sign-in-with-password test-client creds))]
    (testing "POST /token"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/token") (:url req))))
    (testing "grant_type=password in query"
      (is (= "password" (get-in req [:query "grant_type"]))))
    (testing "body has email + password"
      (let [body (parse-body req)]
        (is (= "a@b.com" (get body "email")))
        (is (= "secret" (get body "password")))))))

(deftest sign-in-with-password-phone-test
  (let [[_ req] (run-with-capture
                 #(auth/sign-in-with-password test-client
                                              {:phone "+15555550100" :password "p"}))
        body (parse-body req)]
    (is (= "+15555550100" (get body "phone")))
    (is (not (contains? body "email")))))

;; ---------------------------------------------------------------------------
;; sign-in-with-id-token
;; ---------------------------------------------------------------------------

(deftest sign-in-with-id-token-request-test
  (let [creds {:provider "google" :token "id-token-jwt" :access-token "at"}
        [_ req] (run-with-capture #(auth/sign-in-with-id-token test-client creds))]
    (testing "POST /token"
      (is (= (str base-url "/auth/v1/token") (:url req))))
    (testing "grant_type=id_token"
      (is (= "id_token" (get-in req [:query "grant_type"]))))
    (testing "renames :token to id_token, snake_cases access-token"
      (let [body (parse-body req)]
        (is (= "google" (get body "provider")))
        (is (= "id-token-jwt" (get body "id_token")))
        (is (= "at" (get body "access_token")))
        (is (not (contains? body "token")))))))

;; ---------------------------------------------------------------------------
;; sign-in-with-web3
;; ---------------------------------------------------------------------------

(deftest sign-in-with-web3-request-test
  (let [creds {:chain "ethereum" :message "siwe message" :signature "0xabc"}
        [_ req] (run-with-capture #(auth/sign-in-with-web3 test-client creds))]
    (is (= "web3" (get-in req [:query "grant_type"])))
    (let [body (parse-body req)]
      (is (= "ethereum" (get body "chain")))
      (is (= "siwe message" (get body "message")))
      (is (= "0xabc" (get body "signature"))))))

;; ---------------------------------------------------------------------------
;; sign-in-with-otp
;; ---------------------------------------------------------------------------

(deftest sign-in-with-otp-request-test
  (let [[_ req] (run-with-capture
                 #(auth/sign-in-with-otp test-client {:email "a@b.com"}))]
    (testing "POST /otp"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/otp") (:url req))))
    (testing "body has email"
      (is (= "a@b.com" (get (parse-body req) "email"))))))

(deftest sign-in-with-otp-options-test
  (let [creds {:phone "+15555550100"
               :options {:channel "sms"
                         :should-create-user false
                         :data {:k "v"}
                         :email-redirect-to "https://app/cb"}}
        [_ req] (run-with-capture #(auth/sign-in-with-otp test-client creds))
        body (parse-body req)]
    (testing "channel + create_user + data on body"
      (is (= "sms" (get body "channel")))
      (is (= false (get body "create_user")))
      (is (= {"k" "v"} (get body "data"))))
    (testing "email-redirect-to goes to query"
      (is (= "https://app/cb" (get-in req [:query "redirect_to"]))))))

;; ---------------------------------------------------------------------------
;; sign-in-with-sso
;; ---------------------------------------------------------------------------

(deftest sign-in-with-sso-with-domain-test
  (let [creds {:domain "example.org"
               :options {:redirect-to "https://app/cb"}}
        [_ req] (run-with-capture #(auth/sign-in-with-sso test-client creds))
        body (parse-body req)]
    (is (= (str base-url "/auth/v1/sso") (:url req)))
    (is (= "example.org" (get body "domain")))
    (is (not (contains? body "provider_id")))
    (is (= "https://app/cb" (get-in req [:query "redirect_to"])))))

(deftest sign-in-with-sso-with-provider-id-test
  (let [creds {:provider-id "sso-1"}
        [_ req] (run-with-capture #(auth/sign-in-with-sso test-client creds))
        body (parse-body req)]
    (is (= "sso-1" (get body "provider_id")))
    (is (not (contains? body "domain")))))

;; ---------------------------------------------------------------------------
;; sign-in-anonymously
;; ---------------------------------------------------------------------------

(deftest sign-in-anonymously-request-test
  (let [[_ req] (run-with-capture #(auth/sign-in-anonymously test-client {}))]
    (testing "POST /signup"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/signup") (:url req))))))

(deftest sign-in-anonymously-with-data-test
  (let [creds {:data {:locale "en-US"} :captcha-token "tok"}
        [_ req] (run-with-capture #(auth/sign-in-anonymously test-client creds))
        body (parse-body req)]
    (is (= {"locale" "en-US"} (get body "data")))
    (is (not (contains? body "captcha_token")))))

;; ---------------------------------------------------------------------------
;; sign-in-with-oauth — URL build, no HTTP
;; ---------------------------------------------------------------------------

(deftest sign-in-with-oauth-no-http-test
  (let [[result req] (run-with-capture
                      #(auth/sign-in-with-oauth test-client {:provider "github"}))]
    (testing "does not call execute"
      (is (nil? req)))
    (testing "returns provider + flow-type + url"
      (is (= "github" (:provider result)))
      (is (= "implicit" (:flow-type result)))
      (is (.startsWith (:url result) (str base-url "/auth/v1/authorize?"))))))

(deftest sign-in-with-oauth-includes-options-test
  (let [creds {:provider "github"
               :options {:redirect-to "https://app/cb"
                         :scopes ["read:user" "repo"]}}
        result (auth/sign-in-with-oauth test-client creds)
        url (:url result)]
    (is (.contains url "provider=github"))
    (is (.contains url "redirect_to=https://app/cb"))
    (is (.contains url "scopes=read:user repo"))))
