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

;; ---------------------------------------------------------------------------
;; verify-otp
;; ---------------------------------------------------------------------------

(deftest verify-otp-request-test
  (let [creds {:type "email" :email "a@b.com" :token "123456"}
        [_ req] (run-with-capture #(auth/verify-otp test-client creds))
        body (parse-body req)]
    (testing "POST /verify"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/verify") (:url req))))
    (testing "body has type/email/token"
      (is (= "email" (get body "type")))
      (is (= "a@b.com" (get body "email")))
      (is (= "123456" (get body "token"))))))

(deftest verify-otp-token-hash-test
  (let [creds {:type "email" :token-hash "hash-abc"
               :options {:redirect-to "https://app/cb"}}
        [_ req] (run-with-capture #(auth/verify-otp test-client creds))
        body (parse-body req)]
    (is (= "hash-abc" (get body "token_hash")))
    (is (not (contains? body "token")))
    (is (= "https://app/cb" (get-in req [:query "redirect_to"])))))

(deftest verify-otp-invalid-test
  (testing "token without email or phone"
    (is (error/anomaly? (auth/verify-otp test-client {:type "email" :token "x"}))))
  (testing "neither token nor token-hash"
    (is (error/anomaly? (auth/verify-otp test-client {:type "email" :email "a@b.com"})))))

;; ---------------------------------------------------------------------------
;; refresh-session / exchange-code-for-session
;; ---------------------------------------------------------------------------

(deftest refresh-session-request-test
  (let [[_ req] (run-with-capture #(auth/refresh-session test-client "refresh-tok"))
        body (parse-body req)]
    (testing "POST /token grant_type=refresh_token"
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/token") (:url req)))
      (is (= "refresh_token" (get-in req [:query "grant_type"]))))
    (is (= "refresh-tok" (get body "refresh_token")))))

(deftest exchange-code-for-session-request-test
  (let [creds {:auth-code "code-1" :code-verifier "verifier-1"}
        [_ req] (run-with-capture #(auth/exchange-code-for-session test-client creds))
        body (parse-body req)]
    (is (= "pkce" (get-in req [:query "grant_type"])))
    (is (= "code-1" (get body "auth_code")))
    (is (= "verifier-1" (get body "code_verifier")))))

(deftest exchange-code-for-session-invalid-test
  (is (error/anomaly? (auth/exchange-code-for-session test-client {:auth-code "x"}))))

;; ---------------------------------------------------------------------------
;; update-user
;; ---------------------------------------------------------------------------

(deftest update-user-request-test
  (let [[_ req] (run-with-capture
                 #(auth/update-user test-client "user-tok"
                                    {:password "new-secret" :data {:k "v"}}))
        body (parse-body req)]
    (testing "PUT /user with the user's token"
      (is (= :put (:method req)))
      (is (= (str base-url "/auth/v1/user") (:url req)))
      (is (= "Bearer user-tok" (get-in req [:headers "authorization"]))))
    (is (= "new-secret" (get body "password")))
    (is (= {"k" "v"} (get body "data")))))

(deftest update-user-empty-invalid-test
  (is (error/anomaly? (auth/update-user test-client "tok" {}))))

;; ---------------------------------------------------------------------------
;; resend
;; ---------------------------------------------------------------------------

(deftest resend-request-test
  (let [creds {:type "signup" :email "a@b.com"
               :options {:email-redirect-to "https://app/cb"}}
        [_ req] (run-with-capture #(auth/resend test-client creds))
        body (parse-body req)]
    (is (= (str base-url "/auth/v1/resend") (:url req)))
    (is (= "signup" (get body "type")))
    (is (= "a@b.com" (get body "email")))
    (is (= "https://app/cb" (get-in req [:query "redirect_to"])))))

(deftest resend-invalid-test
  (is (error/anomaly? (auth/resend test-client {:type "signup"}))))

;; ---------------------------------------------------------------------------
;; reset-password-for-email
;; ---------------------------------------------------------------------------

(deftest reset-password-for-email-request-test
  (let [[_ req] (run-with-capture
                 #(auth/reset-password-for-email test-client "a@b.com"
                                                 {:redirect-to "https://app/reset"}))
        body (parse-body req)]
    (is (= (str base-url "/auth/v1/recover") (:url req)))
    (is (= "a@b.com" (get body "email")))
    (is (= "https://app/reset" (get-in req [:query "redirect_to"])))))

;; ---------------------------------------------------------------------------
;; reauthenticate
;; ---------------------------------------------------------------------------

(deftest reauthenticate-request-test
  (let [[_ req] (run-with-capture #(auth/reauthenticate test-client "user-tok"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/reauthenticate") (:url req)))
    (is (= "Bearer user-tok" (get-in req [:headers "authorization"])))))

;; ---------------------------------------------------------------------------
;; identity linking
;; ---------------------------------------------------------------------------

(deftest link-identity-request-test
  (let [[_ req] (run-with-capture
                 #(auth/link-identity test-client "user-tok"
                                      {:provider "github"
                                       :options {:redirect-to "https://app/cb"}}))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/user/identities/authorize") (:url req)))
    (is (= "Bearer user-tok" (get-in req [:headers "authorization"])))
    (is (= "github" (get-in req [:query "provider"])))
    (is (= "true" (get-in req [:query "skip_http_redirect"])))))

(deftest unlink-identity-request-test
  (let [[_ req] (run-with-capture
                 #(auth/unlink-identity test-client "user-tok" "id-123"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/user/identities/id-123") (:url req)))
    (is (= "Bearer user-tok" (get-in req [:headers "authorization"])))))

(deftest get-user-identities-test
  (with-redefs [http/execute (fn [_] {:status 200
                                      :body {:identities [{:id "i1"} {:id "i2"}]}
                                      :headers {}})]
    (let [resp (auth/get-user-identities test-client "user-tok")]
      (is (= 200 (:status resp)))
      (is (= [{:id "i1"} {:id "i2"}] (:body resp))))))

;; ---------------------------------------------------------------------------
;; server observability
;; ---------------------------------------------------------------------------

(deftest get-server-health-test
  (let [[_ req] (run-with-capture #(auth/get-server-health test-client))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/health") (:url req)))))

(deftest get-server-settings-test
  (let [[_ req] (run-with-capture #(auth/get-server-settings test-client))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/settings") (:url req)))))

;; ---------------------------------------------------------------------------
;; get-claims — HS256 falls back to server verification
;; ---------------------------------------------------------------------------

(defn- b64url [^String s]
  (.encodeToString (java.util.Base64/getUrlEncoder) (.getBytes s "UTF-8")))

(defn- fake-jwt [header payload]
  (str (b64url (json/write-value-as-string header)) "."
       (b64url (json/write-value-as-string payload)) "."
       (b64url "sig")))

(deftest get-claims-hs256-fallback-test
  (let [token (fake-jwt {:alg "HS256" :typ "JWT"} {:sub "user-1" :role "authenticated"})]
    (with-redefs [http/execute (fn [_] {:status 200 :body {:id "user-1"} :headers {}})]
      (let [result (auth/get-claims test-client token)]
        (is (= "user-1" (get-in result [:claims :sub])))
        (is (= "HS256" (get-in result [:header :alg])))))))

(deftest get-claims-malformed-test
  (is (error/anomaly? (auth/get-claims test-client "not-a-jwt"))))
