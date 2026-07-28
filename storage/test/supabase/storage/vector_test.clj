(ns supabase.storage.vector-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.storage.vector :as vector]))

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

(defn- valid-vector []
  (vector/from test-client "embeddings"))

(defn- valid-index []
  (vector/index (valid-vector) "docs"))

;; ---------------------------------------------------------------------------
;; from / index — pure
;; ---------------------------------------------------------------------------

(deftest from-test
  (let [v (vector/from test-client "embeddings")]
    (is (= "embeddings" (:vector-bucket-name v)))
    (is (= test-client (:client v)))))

(deftest from-without-bucket-test
  (let [v (vector/from test-client)]
    (is (= test-client (:client v)))
    (is (nil? (:vector-bucket-name v)))))

(deftest from-invalid-client-test
  (is (error/anomaly? (vector/from {} "embeddings"))))

(deftest index-test
  (let [i (vector/index (valid-vector) "docs")]
    (is (= "docs" (:vector-index-name i)))
    (is (= "embeddings" (:vector-bucket-name i)))))

(deftest index-invalid-instance-test
  (is (error/anomaly? (vector/index {} "docs"))))

;; ---------------------------------------------------------------------------
;; Bucket ops — invalid input / request shape
;; ---------------------------------------------------------------------------

(deftest create-bucket-invalid-instance-test
  (is (error/anomaly? (vector/create-bucket {} "embeddings"))))

(deftest create-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(vector/create-bucket (vector/from test-client) "embeddings"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/CreateVectorBucket") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))))

(deftest get-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(vector/get-bucket (vector/from test-client) "embeddings"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/GetVectorBucket") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))))

(deftest list-buckets-request-test
  (let [[_ req] (run-with-capture
                 #(vector/list-buckets (vector/from test-client)
                                       {:prefix "emb" :max-results 10
                                        :next-token "tok"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/ListVectorBuckets") (:url req)))
    (is (= "emb" (get body "prefix")))
    (is (= 10 (get body "maxResults")))
    (is (= "tok" (get body "nextToken")))))

(deftest list-buckets-defaults-test
  (let [[_ req] (run-with-capture
                 #(vector/list-buckets (vector/from test-client)))
        body (parse-body req)]
    (is (= 100 (get body "maxResults")))
    (is (not (contains? body "prefix")))
    (is (not (contains? body "nextToken")))))

(deftest list-buckets-invalid-opts-test
  (is (error/anomaly?
       (vector/list-buckets (vector/from test-client) {:bogus 1}))))

(deftest delete-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(vector/delete-bucket (vector/from test-client) "embeddings"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/DeleteVectorBucket") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))))

;; ---------------------------------------------------------------------------
;; Index ops — scope / invalid input
;; ---------------------------------------------------------------------------

(deftest create-index-requires-bucket-test
  (let [a (vector/create-index (vector/from test-client)
                               {:index-name "docs"
                                :data-type :float32
                                :dimension 384
                                :distance-metric :cosine})]
    (is (error/anomaly? a))
    (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category a)))
    (is (= :storage (:supabase/service a)))))

(deftest get-index-requires-bucket-test
  (is (error/anomaly? (vector/get-index (vector/from test-client) "docs"))))

(deftest list-indexes-requires-bucket-test
  (is (error/anomaly? (vector/list-indexes (vector/from test-client)))))

(deftest delete-index-requires-bucket-test
  (is (error/anomaly? (vector/delete-index (vector/from test-client) "docs"))))

(deftest create-index-invalid-params-test
  (testing "rejects unknown keys"
    (is (error/anomaly?
         (vector/create-index (valid-vector)
                              {:index-name "docs"
                               :data-type :float32
                               :dimension 384
                               :distance-metric :cosine
                               :bogus 1}))))
  (testing "rejects unsupported data types"
    (is (error/anomaly?
         (vector/create-index (valid-vector)
                              {:index-name "docs"
                               :data-type :float64
                               :dimension 384
                               :distance-metric :cosine}))))
  (testing "rejects non-positive dimensions"
    (is (error/anomaly?
         (vector/create-index (valid-vector)
                              {:index-name "docs"
                               :data-type :float32
                               :dimension 0
                               :distance-metric :cosine}))))
  (testing "rejects unknown distance metrics"
    (is (error/anomaly?
         (vector/create-index (valid-vector)
                              {:index-name "docs"
                               :data-type :float32
                               :dimension 384
                               :distance-metric :manhattan})))))

;; ---------------------------------------------------------------------------
;; Index ops — request shape
;; ---------------------------------------------------------------------------

(deftest create-index-request-test
  (let [[_ req] (run-with-capture
                 #(vector/create-index (valid-vector)
                                       {:index-name "docs"
                                        :data-type :float32
                                        :dimension 384
                                        :distance-metric :cosine
                                        :metadata-configuration
                                        {:non-filterable-metadata-keys ["src"]}}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/CreateIndex") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= "float32" (get body "dataType")))
    (is (= 384 (get body "dimension")))
    (is (= "cosine" (get body "distanceMetric")))
    (is (= {"nonFilterableMetadataKeys" ["src"]}
           (get body "metadataConfiguration")))))

(deftest create-index-without-metadata-configuration-test
  (let [[_ req] (run-with-capture
                 #(vector/create-index (valid-vector)
                                       {:index-name "docs"
                                        :data-type :float32
                                        :dimension 384
                                        :distance-metric :euclidean}))
        body (parse-body req)]
    (is (not (contains? body "metadataConfiguration")))))

(deftest get-index-request-test
  (let [[_ req] (run-with-capture #(vector/get-index (valid-vector) "docs"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/GetIndex") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))))

(deftest list-indexes-request-test
  (let [[_ req] (run-with-capture
                 #(vector/list-indexes (valid-vector)
                                       {:prefix "d" :max-results 5}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/ListIndexes") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "d" (get body "prefix")))
    (is (= 5 (get body "maxResults")))))

(deftest delete-index-request-test
  (let [[_ req] (run-with-capture #(vector/delete-index (valid-vector) "docs"))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/DeleteIndex") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))))

;; ---------------------------------------------------------------------------
;; Vector data ops — scope / invalid input
;; ---------------------------------------------------------------------------

(deftest put-vectors-requires-index-test
  (let [a (vector/put-vectors (valid-vector)
                              [{:key "a" :data {:float32 [0.1]}}])]
    (is (error/anomaly? a))
    (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category a)))
    (is (= :storage (:supabase/service a)))))

(deftest get-vectors-requires-index-test
  (is (error/anomaly? (vector/get-vectors (valid-vector) {:keys ["a"]}))))

(deftest list-vectors-requires-index-test
  (is (error/anomaly? (vector/list-vectors (valid-vector)))))

(deftest query-vectors-requires-index-test
  (is (error/anomaly?
       (vector/query-vectors (valid-vector) {:query-vector {:float32 [0.1]}}))))

(deftest delete-vectors-requires-index-test
  (is (error/anomaly? (vector/delete-vectors (valid-vector) ["a"]))))

(deftest put-vectors-invalid-batch-test
  (testing "rejects empty batches"
    (is (error/anomaly? (vector/put-vectors (valid-index) []))))
  (testing "rejects batches over 500"
    (is (error/anomaly?
         (vector/put-vectors (valid-index)
                             (vec (repeat 501 {:key "a" :data {:float32 [0.1]}}))))))
  (testing "rejects malformed entries"
    (is (error/anomaly? (vector/put-vectors (valid-index) [{:key "a"}])))))

(deftest delete-vectors-invalid-batch-test
  (testing "rejects empty key batches"
    (is (error/anomaly? (vector/delete-vectors (valid-index) []))))
  (testing "rejects batches over 500"
    (is (error/anomaly? (vector/delete-vectors (valid-index)
                                               (vec (repeat 501 "a")))))))

(deftest list-vectors-segment-validation-test
  (testing "rejects out-of-range segment counts"
    (is (error/anomaly?
         (vector/list-vectors (valid-index) {:segment-count 0})))
    (is (error/anomaly?
         (vector/list-vectors (valid-index) {:segment-count 17}))))
  (testing "rejects segment-index outside segment-count"
    (is (error/anomaly?
         (vector/list-vectors (valid-index)
                              {:segment-count 2 :segment-index 2}))))
  (testing "accepts segment-index within segment-count"
    (let [[result _] (run-with-capture
                      #(vector/list-vectors (valid-index)
                                            {:segment-count 2 :segment-index 1}))]
      (is (= 200 (:status result))))))

(deftest get-vectors-missing-keys-test
  (is (error/anomaly? (vector/get-vectors (valid-index) {}))))

(deftest query-vectors-missing-query-vector-test
  (is (error/anomaly? (vector/query-vectors (valid-index) {:top-k 5}))))

;; ---------------------------------------------------------------------------
;; Vector data ops — request shape
;; ---------------------------------------------------------------------------

(deftest put-vectors-request-test
  (let [vectors [{:key "doc-1" :data {:float32 [0.1 0.2]} :metadata {"a" 1}}
                 {:key "doc-2" :data {:float32 [0.3 0.4]}}]
        [_ req] (run-with-capture #(vector/put-vectors (valid-index) vectors))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/PutVectors") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= [{"key" "doc-1" "data" {"float32" [0.1 0.2]} "metadata" {"a" 1}}
            {"key" "doc-2" "data" {"float32" [0.3 0.4]}}]
           (get body "vectors")))))

(deftest get-vectors-request-test
  (let [[_ req] (run-with-capture
                 #(vector/get-vectors (valid-index)
                                      {:keys ["a" "b"]
                                       :return-data true
                                       :return-metadata false}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/GetVectors") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= ["a" "b"] (get body "keys")))
    (is (= true (get body "returnData")))
    (is (= false (get body "returnMetadata")))))

(deftest get-vectors-minimal-test
  (let [[_ req] (run-with-capture
                 #(vector/get-vectors (valid-index) {:keys ["a"]}))
        body (parse-body req)]
    (is (not (contains? body "returnData")))
    (is (not (contains? body "returnMetadata")))))

(deftest list-vectors-request-test
  (let [[_ req] (run-with-capture
                 #(vector/list-vectors (valid-index)
                                       {:max-results 50
                                        :next-token "tok"
                                        :return-data true
                                        :return-metadata true
                                        :segment-count 4
                                        :segment-index 1}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/ListVectors") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= 50 (get body "maxResults")))
    (is (= "tok" (get body "nextToken")))
    (is (= true (get body "returnData")))
    (is (= true (get body "returnMetadata")))
    (is (= 4 (get body "segmentCount")))
    (is (= 1 (get body "segmentIndex")))))

(deftest list-vectors-minimal-test
  (let [[_ req] (run-with-capture #(vector/list-vectors (valid-index)))
        body (parse-body req)]
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (not (contains? body "maxResults")))))

(deftest query-vectors-request-test
  (let [[_ req] (run-with-capture
                 #(vector/query-vectors (valid-index)
                                        {:query-vector {:float32 [0.1 0.2]}
                                         :top-k 5
                                         :filter {"kind" "note"}
                                         :return-distance true
                                         :return-metadata true}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/QueryVectors") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= {"float32" [0.1 0.2]} (get body "queryVector")))
    (is (= 5 (get body "topK")))
    (is (= {"kind" "note"} (get body "filter")))
    (is (= true (get body "returnDistance")))
    (is (= true (get body "returnMetadata")))))

(deftest query-vectors-minimal-test
  (let [[_ req] (run-with-capture
                 #(vector/query-vectors (valid-index)
                                        {:query-vector {:float32 [0.1]}}))
        body (parse-body req)]
    (is (not (contains? body "topK")))
    (is (not (contains? body "filter")))))

(deftest delete-vectors-request-test
  (let [[_ req] (run-with-capture
                 #(vector/delete-vectors (valid-index) ["a" "b"]))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/vector/DeleteVectors") (:url req)))
    (is (= "embeddings" (get body "vectorBucketName")))
    (is (= "docs" (get body "indexName")))
    (is (= ["a" "b"] (get body "keys")))))

;; ---------------------------------------------------------------------------
;; Error parser
;; ---------------------------------------------------------------------------

(deftest requests-carry-error-parser-test
  (testing "vector ops install the storage error parser"
    (let [[_ req] (run-with-capture
                   #(vector/list-buckets (vector/from test-client)))]
      (is (fn? (:error-parser req))))
    (let [[_ req] (run-with-capture
                   #(vector/query-vectors (valid-index)
                                          {:query-vector {:float32 [0.1]}}))]
      (is (fn? (:error-parser req))))))

(deftest error-passthrough-test
  (let [resp (error/from-http-response 404 {:message "Bucket not found"} :storage)
        [result _] (run-with-capture
                    #(vector/get-bucket (vector/from test-client) "nope")
                    resp)]
    (is (error/anomaly? result))))
