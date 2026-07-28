(ns supabase.storage.analytics-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.storage.analytics :as analytics]))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key")
(def test-client (client/make-client base-url api-key))
(def storage-url (str base-url "/storage/v1"))

(def ^:private captured (atom nil))

(defn- run-with-capture
  ([f] (run-with-capture f {:status 200 :body {:ok true} :headers {}}))
  ([f response]
   (reset! captured nil)
   (with-redefs [http/execute (fn [req]
                                (reset! captured req)
                                response)]
     [(f) @captured])))

(defn- parse-body [req] (json/read-value (:body req)))

;; ---------------------------------------------------------------------------
;; Bucket ops — invalid input
;; ---------------------------------------------------------------------------

(deftest create-bucket-invalid-client-test
  (is (error/anomaly? (analytics/create-bucket {} "analytics-data"))))

(deftest list-buckets-invalid-client-test
  (is (error/anomaly? (analytics/list-buckets {}))))

(deftest list-buckets-invalid-opts-test
  (testing "rejects unknown keys"
    (is (error/anomaly? (analytics/list-buckets test-client {:bogus 1}))))
  (testing "rejects unknown sort columns"
    (is (error/anomaly?
         (analytics/list-buckets test-client {:sort-column :size}))))
  (testing "rejects unknown sort orders"
    (is (error/anomaly?
         (analytics/list-buckets test-client {:sort-order :up})))))

(deftest delete-bucket-invalid-client-test
  (is (error/anomaly? (analytics/delete-bucket {} "analytics-data"))))

;; ---------------------------------------------------------------------------
;; Bucket ops — request shape
;; ---------------------------------------------------------------------------

(deftest create-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(analytics/create-bucket test-client "analytics-data"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/iceberg/bucket") (:url req)))
    (is (= "analytics-data" (get body "name")))))

(deftest list-buckets-request-test
  (let [[_ req] (run-with-capture #(analytics/list-buckets test-client))]
    (is (= :get (:method req)))
    (is (= (str storage-url "/iceberg/bucket") (:url req)))
    (is (= {} (:query req)))))

(deftest list-buckets-opts-request-test
  (let [[_ req] (run-with-capture
                 #(analytics/list-buckets test-client
                                          {:limit 10
                                           :offset 5
                                           :sort-column :created-at
                                           :sort-order :desc
                                           :search "data"}))]
    (is (= {"limit" "10"
            "offset" "5"
            "sortColumn" "created_at"
            "sortOrder" "desc"
            "search" "data"}
           (:query req)))))

(deftest list-buckets-partial-opts-request-test
  (let [[_ req] (run-with-capture
                 #(analytics/list-buckets test-client {:sort-column :name}))]
    (is (= {"sortColumn" "name"} (:query req)))))

(deftest delete-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(analytics/delete-bucket test-client "analytics-data"))]
    (is (= :delete (:method req)))
    (is (= (str storage-url "/iceberg/bucket/analytics-data") (:url req)))))

;; ---------------------------------------------------------------------------
;; catalog-info — pure
;; ---------------------------------------------------------------------------

(deftest catalog-info-test
  (let [info (analytics/catalog-info test-client)]
    (is (= (str storage-url "/iceberg") (:url info)))
    (is (= {"authorization" (str "Bearer " api-key)
            "apikey" api-key}
           (:headers info)))))

(deftest catalog-info-invalid-client-test
  (is (error/anomaly? (analytics/catalog-info {}))))

;; ---------------------------------------------------------------------------
;; Error parser
;; ---------------------------------------------------------------------------

(deftest requests-carry-error-parser-test
  (testing "analytics ops install the storage error parser"
    (let [[_ req] (run-with-capture
                   #(analytics/list-buckets test-client))]
      (is (fn? (:error-parser req))))
    (let [[_ req] (run-with-capture
                   #(analytics/delete-bucket test-client "analytics-data"))]
      (is (fn? (:error-parser req))))))

(deftest error-passthrough-test
  (let [resp (error/from-http-response 404 {:message "Bucket not found"} :storage)
        [result _] (run-with-capture
                    #(analytics/delete-bucket test-client "nope")
                    resp)]
    (is (error/anomaly? result))))
