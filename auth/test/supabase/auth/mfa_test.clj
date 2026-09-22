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

;; ---------------------------------------------------------------------------
;; enroll rollback for failed webauthn registrations
;; ---------------------------------------------------------------------------

(defn- enroll-failure
  "An anomaly as `http/execute` would produce for a rejected enroll."
  []
  {:cognitect.anomalies/category :cognitect.anomalies/incorrect
   :cognitect.anomalies/message "friendly name already in use"
   :supabase/service :auth})

(def ^:private webauthn-factors-response
  {:status 200
   :headers {}
   :body {:id "uid-1"
          :factors [{:id "f-1" :factor_type "webauthn"
                     :friendly_name "laptop" :status "unverified"}
                    {:id "f-2" :factor_type "webauthn"
                     :friendly_name "laptop" :status "verified"}
                    {:id "f-3" :factor_type "totp"
                     :friendly_name "laptop" :status "unverified"}]}})

(deftest enroll-webauthn-failure-unenrolls-stale-factor-test
  (let [reqs (atom [])]
    (with-redefs [http/execute
                  (fn [req]
                    (swap! reqs conj req)
                    (case (:method req)
                      :post (enroll-failure)
                      :get  webauthn-factors-response
                      :delete {:status 200 :body {} :headers {}}))]
      (let [resp (mfa/enroll test-client token {:factor-type "webauthn"
                                                :friendly-name "laptop"})
            deletes (filter #(= :delete (:method %)) @reqs)]
        (is (error/anomaly? resp))
        (is (= "friendly name already in use" (:cognitect.anomalies/message resp)))
        (is (= 3 (count @reqs)))
        (is (= (str base-url "/auth/v1/user") (:url (second @reqs))))
        ;; only the unverified webauthn factor with the same name is removed
        (is (= 1 (count deletes)))
        (is (= (str base-url "/auth/v1/factors/f-1") (:url (first deletes))))))))

(deftest enroll-webauthn-failure-without-leftover-test
  (let [reqs (atom [])]
    (with-redefs [http/execute
                  (fn [req]
                    (swap! reqs conj req)
                    (case (:method req)
                      :post (enroll-failure)
                      :get  {:status 200 :headers {} :body {:id "uid-1" :factors []}}))]
      (let [resp (mfa/enroll test-client token {:factor-type "webauthn"
                                                :friendly-name "laptop"})]
        (is (error/anomaly? resp))
        (is (= 2 (count @reqs)))
        (is (not-any? #(= :delete (:method %)) @reqs))))))

(deftest enroll-cleanup-only-for-webauthn-test
  (testing "totp enroll failures trigger no cleanup"
    (let [reqs (atom [])]
      (with-redefs [http/execute
                    (fn [req] (swap! reqs conj req) (enroll-failure))]
        (is (error/anomaly? (mfa/enroll test-client token {:factor-type "totp"
                                                           :friendly-name "laptop"})))
        (is (= 1 (count @reqs))))))
  (testing "webauthn enroll without a friendly name triggers no cleanup"
    (let [reqs (atom [])]
      (with-redefs [http/execute
                    (fn [req] (swap! reqs conj req) (enroll-failure))]
        (is (error/anomaly? (mfa/enroll test-client token {:factor-type "webauthn"})))
        (is (= 1 (count @reqs))))))
  (testing "successful enroll triggers no cleanup"
    (let [reqs (atom [])]
      (with-redefs [http/execute
                    (fn [req] (swap! reqs conj req)
                      {:status 200 :body {:id "f-9"} :headers {}})]
        (is (= 200 (:status (mfa/enroll test-client token {:factor-type "webauthn"
                                                           :friendly-name "laptop"}))))
        (is (= 1 (count @reqs)))))))

;; ---------------------------------------------------------------------------
;; recovery codes
;; ---------------------------------------------------------------------------

(deftest get-recovery-codes-status-test
  (let [[resp req] (run-with-capture
                    #(mfa/get-recovery-codes-status test-client token)
                    {:status 200 :headers {}
                     :body {:id "f-rc" :type "recovery_code"
                            :total 8 :remaining 5}})]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/factors/recovery-codes") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (is (= 5 (get-in resp [:body :remaining])))))

(deftest generate-recovery-codes-test
  (testing "no body is sent without a friendly name"
    (let [[resp req] (run-with-capture
                      #(mfa/generate-recovery-codes test-client token)
                      {:status 200 :headers {}
                       :body {:id "f-rc" :type "recovery_code"
                              :total 8 :codes ["AAAA-BBBB"]}})]
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/factors/recovery-codes") (:url req)))
      (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
      (is (nil? (:body req)))
      (is (= ["AAAA-BBBB"] (get-in resp [:body :codes])))))
  (testing "friendly name is sent snake_cased"
    (let [[_ req] (run-with-capture
                   #(mfa/generate-recovery-codes test-client token
                                                 {:friendly-name "backup codes"}))]
      (is (= "backup codes" (get (parse-body req) "friendly_name"))))))

(deftest generate-recovery-codes-validation-test
  (is (error/anomaly? (mfa/generate-recovery-codes test-client token {:bogus 1})))
  (is (error/anomaly? (mfa/generate-recovery-codes {} token))))

(deftest verify-recovery-code-test
  (let [[resp req] (run-with-capture
                    #(mfa/verify-recovery-code test-client token
                                               {:code "K4M9-X7QP-2AB8-HT3Z"})
                    {:status 200 :headers {}
                     :body {:access_token "aal2-token" :refresh_token "r-2"
                            :expires_in 3600 :token_type "bearer"}})]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/factors/recovery-codes/verify") (:url req)))
    (is (= (str "Bearer " token) (get-in req [:headers "authorization"])))
    (is (= "K4M9-X7QP-2AB8-HT3Z" (get (parse-body req) "code")))
    (testing "the fresh session is returned for the caller to adopt"
      (is (= "aal2-token" (get-in resp [:body :access_token]))))))

(deftest verify-recovery-code-validation-test
  (is (error/anomaly? (mfa/verify-recovery-code test-client token {})))
  (is (error/anomaly? (mfa/verify-recovery-code test-client token {:code 42})))
  (is (error/anomaly? (mfa/verify-recovery-code {} token {:code "x"}))))
