(ns supabase.auth.passkey-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.passkey :as passkey]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def base-url "https://abc123.supabase.co")
(def test-client (client/make-client base-url "anon-key"))
(def token "user-access-token")

(def ^:private captured (atom nil))

(defn- run-with-capture
  ([f] (run-with-capture f {:status 200 :body {:ok true} :headers {}}))
  ([f response]
   (reset! captured nil)
   (with-redefs [http/execute (fn [req] (reset! captured req) response)]
     [(f) @captured])))

(defn- parse-body [req] (json/read-value (:body req)))

;; ---------------------------------------------------------------------------
;; two-step registration
;; ---------------------------------------------------------------------------

(deftest start-registration-test
  (let [[resp req] (run-with-capture
                    #(passkey/start-registration test-client token)
                    {:status 200 :headers {}
                     :body {:challenge_id "ch-1"
                            :options {:rp {:name "acme"} :challenge "AA"}
                            :expires_at 3600}})]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/passkeys/registration/options") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (testing "empty JSON body, like auth-js"
      (is (= {} (parse-body req))))
    (is (= "ch-1" (get-in resp [:body :challenge_id])))))

(deftest verify-registration-test
  (let [credential {:id "cred-1" :rawId "cred-1"
                    :response {:clientDataJSON "cdj" :attestationObject "ao"}
                    :type "public-key"}
        [_ req] (run-with-capture
                 #(passkey/verify-registration test-client token "ch-1" credential)
                 {:status 200 :headers {}
                  :body {:id "pk-1" :friendly_name "laptop" :created_at "2026-01-01"}})]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/passkeys/registration/verify") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (let [body (parse-body req)]
      (is (= "ch-1" (get body "challenge_id")))
      (is (= "cred-1" (get-in body ["credential" "id"])))
      (is (= "ao" (get-in body ["credential" "response" "attestationObject"]))))))

(deftest verify-registration-validation-test
  (testing "challenge-id must be a string"
    (is (error/anomaly? (passkey/verify-registration test-client token nil {}))))
  (testing "invalid client"
    (is (error/anomaly? (passkey/verify-registration {} token "ch-1" {})))))

;; ---------------------------------------------------------------------------
;; two-step authentication
;; ---------------------------------------------------------------------------

(deftest start-authentication-test
  (testing "no captcha token sends an empty gotrue_meta_security"
    (let [[_ req] (run-with-capture #(passkey/start-authentication test-client))]
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/passkeys/authentication/options") (:url req)))
      (testing "no user session exists yet: the client's api key authenticates"
        (is (= "Bearer anon-key" (get-in req [:headers "authorization"]))))
      (is (= {"gotrue_meta_security" {}} (parse-body req)))))
  (testing "captcha token is sent snake_cased"
    (let [[_ req] (run-with-capture
                   #(passkey/start-authentication test-client {:captcha-token "tok"}))]
      (is (= {"gotrue_meta_security" {"captcha_token" "tok"}} (parse-body req)))))
  (testing "invalid params"
    (is (error/anomaly? (passkey/start-authentication test-client {:captcha-token 42})))
    (is (error/anomaly? (passkey/start-authentication {})))))

(deftest verify-authentication-test
  (let [credential {:id "cred-1" :rawId "cred-1"
                    :response {:clientDataJSON "cdj" :authenticatorData "ad"
                               :signature "sig"}
                    :type "public-key"}
        [resp req] (run-with-capture
                    #(passkey/verify-authentication test-client "ch-1" credential)
                    {:status 200 :headers {}
                     :body {:access_token "at" :refresh_token "rt"
                            :expires_in 3600 :token_type "bearer"
                            :user {:id "uid-1"}}})]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/passkeys/authentication/verify") (:url req)))
    (testing "no user session exists yet: the client's api key authenticates"
      (is (= "Bearer anon-key" (get-in req [:headers "authorization"]))))
    (let [body (parse-body req)]
      (is (= "ch-1" (get body "challenge_id")))
      (is (= "sig" (get-in body ["credential" "response" "signature"]))))
    (testing "the fresh session is returned for the caller to adopt"
      (is (= "at" (get-in resp [:body :access_token]))))))

(deftest verify-authentication-validation-test
  (testing "challenge-id must be a string"
    (is (error/anomaly? (passkey/verify-authentication test-client 123 {}))))
  (testing "invalid client"
    (is (error/anomaly? (passkey/verify-authentication {} "ch-1" {})))))

;; ---------------------------------------------------------------------------
;; management
;; ---------------------------------------------------------------------------

(deftest list-test
  (let [[resp req] (run-with-capture
                    #(passkey/list test-client token)
                    {:status 200 :headers {}
                     :body [{:id "pk-1" :friendly_name "laptop"
                             :created_at "2026-01-01" :last_used_at "2026-02-01"}]})]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/passkeys") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (is (= "laptop" (get-in resp [:body 0 :friendly_name])))))

(deftest update-test
  (let [[_ req] (run-with-capture
                 #(passkey/update test-client token "pk-1" "my laptop"))]
    (is (= :patch (:method req)))
    (is (= (str base-url "/auth/v1/passkeys/pk-1") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (is (= "my laptop" (get (parse-body req) "friendly_name")))))

(deftest delete-test
  (let [[_ req] (run-with-capture #(passkey/delete test-client token "pk-1"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/passkeys/pk-1") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))))

(deftest passkey-invalid-client-test
  (is (error/anomaly? (passkey/start-registration {} token)))
  (is (error/anomaly? (passkey/list {} token)))
  (is (error/anomaly? (passkey/update {} token "pk-1" "name")))
  (is (error/anomaly? (passkey/delete {} token "pk-1"))))
