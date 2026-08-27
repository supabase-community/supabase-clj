(ns supabase.auth.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.auth :as auth]
            [supabase.auth.admin :as admin]
            [supabase.auth.admin.custom-providers :as providers]
            [supabase.auth.admin.oauth :as oauth]
            [supabase.auth.errors :as errors]
            [supabase.auth.mfa :as mfa]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def base-url "https://abc123.supabase.co")
(def test-client (client/make-client base-url "anon-key"))

(def ^:private captured (atom nil))

(defn- run-with-capture [f]
  (reset! captured nil)
  (with-redefs [http/execute (fn [req]
                               (reset! captured req)
                               {:status 200 :body {:ok true} :headers {}})]
    [(f) @captured]))

;; ---------------------------------------------------------------------------
;; 5xx message preservation
;; ---------------------------------------------------------------------------

(deftest five-xx-message-preserved-test
  (doseq [[body expected] [[{:msg "database unavailable"} "database unavailable"]
                           [{:message "upstream failed"} "upstream failed"]
                           [{:error_description "gateway exploded"} "gateway exploded"]
                           [{:error "server_error"} "server_error"]]]
    (let [anomaly (errors/auth-error-parser 503 body {} :auth)]
      (is (= expected (:cognitect.anomalies/message anomaly)))
      (is (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category anomaly)))
      (is (= body (:http/body anomaly))))))

(deftest five-xx-without-body-message-test
  (testing "falls back to the status-derived message"
    (let [anomaly (errors/auth-error-parser 500 {} {} :auth)]
      (is (= "Server Error" (:cognitect.anomalies/message anomaly)))))
  (testing "non-string message fields are ignored"
    (let [anomaly (errors/auth-error-parser 500 {:message 42} {} :auth)]
      (is (= "Server Error" (:cognitect.anomalies/message anomaly))))))

(deftest non-5xx-keeps-default-message-test
  (let [anomaly (errors/auth-error-parser 400 {:message "bad credentials"} {} :auth)]
    (is (= "Bad Request" (:cognitect.anomalies/message anomaly)))
    (is (= :bad-request (:supabase/code anomaly)))))

;; ---------------------------------------------------------------------------
;; weak-password parsing
;; ---------------------------------------------------------------------------

(deftest weak-password-coded-test
  (let [anomaly (errors/auth-error-parser 422 {:code "weak_password"
                                               :message "Password is too weak"
                                               :weak_password {:reasons ["length" "pwned"]}}
                                          {} :auth)]
    (is (= :weak-password (:supabase/code anomaly)))
    (is (= "Password is too weak" (:cognitect.anomalies/message anomaly)))
    (is (= ["length" "pwned"] (:auth/weak-password-reasons anomaly)))))

(deftest weak-password-error-code-key-test
  (testing "error_code variant, reasons may be absent"
    (let [anomaly (errors/auth-error-parser 400 {:error_code "weak_password"
                                                 :msg "Weak password"}
                                            {} :auth)]
      (is (= :weak-password (:supabase/code anomaly)))
      (is (= "Weak password" (:cognitect.anomalies/message anomaly)))
      (is (= [] (:auth/weak-password-reasons anomaly)))))
  (testing "coded weak password without a message keeps the default"
    (let [anomaly (errors/auth-error-parser 422 {:code "weak_password"} {} :auth)]
      (is (= :weak-password (:supabase/code anomaly)))
      (is (= "Unprocessable Entity" (:cognitect.anomalies/message anomaly)))
      (is (= [] (:auth/weak-password-reasons anomaly))))))

(deftest weak-password-legacy-shape-test
  (let [anomaly (errors/auth-error-parser 400 {:msg "Password should be at least 6 characters"
                                               :weak_password {:reasons ["length"]}}
                                          {} :auth)]
    (is (= :weak-password (:supabase/code anomaly)))
    (is (= "Password should be at least 6 characters"
           (:cognitect.anomalies/message anomaly)))
    (is (= ["length"] (:auth/weak-password-reasons anomaly)))))

(deftest weak-password-legacy-requires-reasons-test
  (testing "legacy shape without a non-empty string reason list is not tagged"
    (let [anomaly (errors/auth-error-parser 400 {:weak_password {:reasons []}} {} :auth)]
      (is (= :bad-request (:supabase/code anomaly)))
      (is (not (contains? anomaly :auth/weak-password-reasons)))))
  (testing "other error codes are untouched"
    (let [anomaly (errors/auth-error-parser 400 {:error_code "invalid_grant"
                                                 :weak_password {:reasons ["length"]}}
                                            {} :auth)]
      (is (= :bad-request (:supabase/code anomaly)))
      (is (not (contains? anomaly :auth/weak-password-reasons))))))

;; ---------------------------------------------------------------------------
;; wiring - every auth namespace installs the parser
;; ---------------------------------------------------------------------------

(deftest requests-use-auth-error-parser-test
  (doseq [[label f] [["auth"
                      #(auth/sign-in-with-password test-client {:email "a@b.com" :password "p"})]
                     ["mfa"
                      #(mfa/unenroll test-client "user-tok" "f-1")]
                     ["admin"
                      #(admin/list-users test-client)]
                     ["admin custom-providers"
                      #(providers/list-providers test-client)]
                     ["admin oauth"
                      #(oauth/list-clients test-client)]]]
    (let [[_ req] (run-with-capture f)]
      (is (= errors/auth-error-parser (:error-parser req))
          (str label " request is missing the auth error parser")))))
