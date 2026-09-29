(ns supabase.core.error-test
  (:require [cljs.test :refer [deftest is testing]]
            [supabase.core.error :as error]))

(deftest anomaly?-test
  (is (true? (error/anomaly? {:cognitect.anomalies/category :cognitect.anomalies/fault})))
  (is (false? (error/anomaly? {:status 200})))
  (is (false? (error/anomaly? nil))))

(deftest from-http-response-test
  (testing "maps status to category and code"
    (let [a (error/from-http-response 404 {:message "nope"} :storage)]
      (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category a)))
      (is (= :not-found (:supabase/code a)))
      (is (= :storage (:supabase/service a)))
      (is (= 404 (:http/status a)))))
  (testing "unknown 4xx defaults to incorrect"
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category (error/from-http-response 499 nil)))))
  (testing "unknown 5xx defaults to fault"
    (is (= :cognitect.anomalies/fault
           (:cognitect.anomalies/category (error/from-http-response 599 nil))))))

(deftest from-exception-test
  (let [a (error/from-exception (js/Error. "boom") :auth)]
    (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category a)))
    (is (= "boom" (:cognitect.anomalies/message a)))
    (is (= :exception (:supabase/code a)))
    (is (= :auth (:supabase/service a)))))

(deftest humanize-code-test
  (is (= "Not Found" (error/humanize-code :not-found))))
