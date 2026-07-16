(ns supabase.auth.mfa-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.mfa :as mfa]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http])
  (:import (java.util Base64)))

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

(defn- b64url [^String s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes s "UTF-8")))

(defn- make-jwt [payload]
  (str (b64url (json/write-value-as-string {:alg "HS256" :typ "JWT"}))
       "." (b64url (json/write-value-as-string payload))
       "." (b64url "sig")))

(deftest enroll-test
  (let [[_ req] (run-with-capture
                 #(mfa/enroll test-client token {:factor-type "totp"
                                                 :friendly-name "authenticator"
                                                 :issuer "example.com"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/factors") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (is (= "totp" (get body "factor_type")))
    (is (= "authenticator" (get body "friendly_name")))
    (is (= "example.com" (get body "issuer")))))

(deftest enroll-validation-test
  (testing "phone factor requires a phone number"
    (is (error/anomaly? (mfa/enroll test-client token {:factor-type "phone"}))))
  (testing "unknown factor type"
    (is (error/anomaly? (mfa/enroll test-client token {:factor-type "sms"})))))

(deftest challenge-test
  (testing "totp challenge sends an empty body"
    (let [[_ req] (run-with-capture #(mfa/challenge test-client token "f-1"))]
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/factors/f-1/challenge") (:url req)))
      (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
      (is (= {} (parse-body req)))))
  (testing "phone challenge carries the channel"
    (let [[_ req] (run-with-capture
                   #(mfa/challenge test-client token "f-1" {:channel "sms"}))]
      (is (= "sms" (get (parse-body req) "channel")))))
  (testing "invalid channel"
    (is (error/anomaly? (mfa/challenge test-client token "f-1" {:channel "carrier-pigeon"})))))

(deftest verify-test
  (let [[_ req] (run-with-capture
                 #(mfa/verify test-client token "f-1" "ch-1" {:code "123456"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/factors/f-1/verify") (:url req)))
    (is (= "ch-1" (get body "challenge_id")))
    (is (= "123456" (get body "code")))))

(deftest verify-validation-test
  (testing "either code or webauthn required"
    (is (error/anomaly? (mfa/verify test-client token "f-1" "ch-1" {})))))

(deftest unenroll-test
  (let [[_ req] (run-with-capture #(mfa/unenroll test-client token "f-1"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/factors/f-1") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))))

(deftest challenge-and-verify-test
  (let [reqs (atom [])]
    (with-redefs [http/execute
                  (fn [req]
                    (swap! reqs conj req)
                    (if (str/ends-with? (:url req) "/challenge")
                      {:status 200 :body {:id "ch-1"} :headers {}}
                      {:status 200 :body {:access_token "elevated"} :headers {}}))]
      (let [resp (mfa/challenge-and-verify test-client token "f-1" "123456")
            [challenge-req verify-req] @reqs]
        (is (= 2 (count @reqs)))
        (is (str/ends-with? (:url challenge-req) "/factors/f-1/challenge"))
        (is (str/ends-with? (:url verify-req) "/factors/f-1/verify"))
        (is (= "ch-1" (get (parse-body verify-req) "challenge_id")))
        (is (= "elevated" (get-in resp [:body :access_token])))))))

(def ^:private user-with-factors
  {:status 200
   :headers {}
   :body {:id "uid-1"
          :factors [{:id "f-1" :factor_type "totp" :status "verified"}
                    {:id "f-2" :factor_type "totp" :status "unverified"}
                    {:id "f-3" :factor_type "phone" :status "verified"}]}})

(deftest list-factors-test
  (let [[resp req] (run-with-capture
                    #(mfa/list-factors test-client token)
                    user-with-factors)
        {:keys [all totp phone webauthn]} (:body resp)]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/user") (:url req)))
    (is (= 3 (count all)))
    (is (= ["f-1"] (mapv :id totp)))
    (is (= ["f-3"] (mapv :id phone)))
    (is (= [] webauthn))))

(deftest get-authenticator-assurance-level-test
  (testing "verified factor raises next level to aal2"
    (let [jwt (make-jwt {:aal "aal1" :amr [{:method "password" :timestamp 1}]})
          [resp _] (run-with-capture
                    #(mfa/get-authenticator-assurance-level test-client jwt)
                    user-with-factors)]
      (is (= "aal1" (:current-level resp)))
      (is (= "aal2" (:next-level resp)))
      (is (= [{:method "password" :timestamp 1}]
             (:current-authentication-methods resp)))))
  (testing "no verified factors keeps next level at current"
    (let [jwt (make-jwt {:aal "aal1" :amr [{:method "password" :timestamp 1}]})
          [resp _] (run-with-capture
                    #(mfa/get-authenticator-assurance-level test-client jwt)
                    {:status 200 :headers {} :body {:id "uid-1" :factors []}})]
      (is (= "aal1" (:current-level resp)))
      (is (= "aal1" (:next-level resp)))))
  (testing "malformed token"
    (is (error/anomaly?
         (mfa/get-authenticator-assurance-level test-client "not-a-jwt")))))

(deftest mfa-invalid-client-test
  (is (error/anomaly? (mfa/enroll {} token {:factor-type "totp"})))
  (is (error/anomaly? (mfa/unenroll {} token "f-1"))))
