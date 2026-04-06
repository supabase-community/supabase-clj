(ns supabase.core.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.core.error :as error]))

;; ---------------------------------------------------------------------------
;; anomaly?
;; ---------------------------------------------------------------------------

(deftest anomaly?-test
  (testing "returns true for maps with anomaly category"
    (is (true? (error/anomaly? {:cognitect.anomalies/category :cognitect.anomalies/incorrect}))))

  (testing "returns true for anomaly maps with extra fields"
    (is (true? (error/anomaly?
                 {:cognitect.anomalies/category :cognitect.anomalies/fault
                  :cognitect.anomalies/message "boom"
                  :supabase/service :auth}))))

  (testing "returns false for nil"
    (is (false? (error/anomaly? nil))))

  (testing "returns false for regular maps"
    (is (false? (error/anomaly? {:status 200 :body {}}))))

  (testing "returns false for non-maps"
    (is (false? (error/anomaly? "error")))
    (is (false? (error/anomaly? 42)))
    (is (false? (error/anomaly? [:cognitect.anomalies/category])))))

;; ---------------------------------------------------------------------------
;; anomaly
;; ---------------------------------------------------------------------------

(deftest anomaly-test
  (testing "creates minimal anomaly with just category"
    (let [a (error/anomaly :cognitect.anomalies/incorrect)]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category a)))
      (is (error/anomaly? a))))

  (testing "creates anomaly with extra fields"
    (let [a (error/anomaly :cognitect.anomalies/forbidden
              {:cognitect.anomalies/message "Access denied"
               :supabase/service :storage})]
      (is (= :cognitect.anomalies/forbidden (:cognitect.anomalies/category a)))
      (is (= "Access denied" (:cognitect.anomalies/message a)))
      (is (= :storage (:supabase/service a))))))

;; ---------------------------------------------------------------------------
;; humanize-code
;; ---------------------------------------------------------------------------

(deftest humanize-code-test
  (testing "converts keyword codes to human-readable strings"
    (is (= "Not Found" (error/humanize-code :not-found)))
    (is (= "Bad Request" (error/humanize-code :bad-request)))
    (is (= "Too Many Requests" (error/humanize-code :too-many-requests))))

  (testing "handles single-word codes"
    (is (= "Unauthorized" (error/humanize-code :unauthorized)))
    (is (= "Forbidden" (error/humanize-code :forbidden)))))

;; ---------------------------------------------------------------------------
;; from-http-response
;; ---------------------------------------------------------------------------

(deftest from-http-response-test
  (testing "maps known status codes to correct categories"
    (let [cases [[400 :cognitect.anomalies/incorrect :bad-request]
                 [401 :cognitect.anomalies/forbidden :unauthorized]
                 [403 :cognitect.anomalies/forbidden :forbidden]
                 [404 :cognitect.anomalies/not-found :not-found]
                 [409 :cognitect.anomalies/conflict :resource-already-exists]
                 [422 :cognitect.anomalies/incorrect :unprocessable-entity]
                 [429 :cognitect.anomalies/busy :too-many-requests]
                 [500 :cognitect.anomalies/fault :server-error]
                 [503 :cognitect.anomalies/unavailable :service-unavailable]]]
      (doseq [[status expected-category expected-code] cases]
        (let [a (error/from-http-response status {:msg "test"})]
          (is (= expected-category (:cognitect.anomalies/category a))
              (str "status " status " should map to " expected-category))
          (is (= expected-code (:supabase/code a))
              (str "status " status " should have code " expected-code))
          (is (= status (:http/status a)))
          (is (= {:msg "test"} (:http/body a)))
          (is (error/anomaly? a))))))

  (testing "includes service when provided"
    (let [a (error/from-http-response 404 {} :storage)]
      (is (= :storage (:supabase/service a)))))

  (testing "omits service when not provided"
    (let [a (error/from-http-response 404 {})]
      (is (nil? (:supabase/service a)))))

  (testing "unknown 4xx defaults to incorrect"
    (let [a (error/from-http-response 418 {:msg "teapot"})]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category a)))
      (is (= :unexpected (:supabase/code a)))))

  (testing "unknown 5xx defaults to fault"
    (let [a (error/from-http-response 502 {:msg "bad gateway"})]
      (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category a)))
      (is (= :unexpected (:supabase/code a)))))

  (testing "message is humanized from code"
    (let [a (error/from-http-response 404 {})]
      (is (= "Not Found" (:cognitect.anomalies/message a))))))

;; ---------------------------------------------------------------------------
;; from-exception
;; ---------------------------------------------------------------------------

(deftest from-exception-test
  (testing "wraps exception as fault anomaly"
    (let [ex (Exception. "something broke")
          a (error/from-exception ex)]
      (is (error/anomaly? a))
      (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category a)))
      (is (= "something broke" (:cognitect.anomalies/message a)))
      (is (= :exception (:supabase/code a)))
      (is (= ex (:exception a)))
      (is (nil? (:supabase/service a)))))

  (testing "includes service when provided"
    (let [a (error/from-exception (Exception. "boom") :auth)]
      (is (= :auth (:supabase/service a))))))
