(ns supabase.storage.vector
  "Vector buckets, indexes, and vector data against Supabase Storage.

  Provides vector bucket CRUD, per-bucket index operations, and
  per-index vector data operations (put, get, list, query, delete).
  Index ops take an instance returned by `from`; vector data ops take an
  instance further scoped by `index`.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.storage.vector :as vector])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (vector/create-bucket (vector/from c) \"embeddings\")

      (def b (vector/from c \"embeddings\"))
      (vector/create-index b {:index-name \"docs\"
                              :data-type :float32
                              :dimension 384
                              :distance-metric :cosine})

      (def i (vector/index b \"docs\"))
      (vector/put-vectors i [{:key \"doc-1\" :data {:float32 [0.1 0.2]}}])
      (vector/query-vectors i {:query-vector {:float32 [0.1 0.2]} :top-k 5})

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. All endpoints are POSTs under `{storage-url}/vector` with
  camelCase JSON bodies. See
  https://supabase.com/docs/reference/javascript/storage-vector-api"
  (:require [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.storage :as storage]
            [supabase.storage.specs :as specs]))

(defn- vector-post
  "POSTs `body` to the `/vector/<op>` endpoint."
  [client op body]
  (-> (http/request client)
      (http/with-method :post)
      (http/with-service-url :storage-url (str "/vector/" op))
      (storage/with-storage-errors)
      (http/with-body body)
      (http/execute)))

(defn- ensure-bucket-scope
  "Returns nil when `v` carries a `:vector-bucket-name`, otherwise an
  anomaly."
  [v]
  (when-not (:vector-bucket-name v)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Operation requires a vector bucket, scope the instance with (from client \"bucket-name\")"
                    :supabase/service :storage})))

(defn- ensure-index-scope
  "Returns nil when `v` carries a `:vector-index-name`, otherwise an
  anomaly."
  [v]
  (when-not (:vector-index-name v)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Operation requires a vector index, scope the instance with (index v \"index-name\")"
                    :supabase/service :storage})))

;; ---------------------------------------------------------------------------
;; Vector storage instance
;; ---------------------------------------------------------------------------

(defn from
  "Returns a vector storage instance, optionally bound to `bucket-name`.
  Bucket CRUD works on either form; index and vector data operations
  require the bound form.

      (def v (from client \"embeddings\"))
      (def i (index v \"docs\"))"
  ([client] (from client nil))
  ([client bucket-name]
   (let [v (cond-> {:client client}
             bucket-name (assoc :vector-bucket-name bucket-name))]
     (or (specs/ensure-vector-storage v) v))))

(defn index
  "Returns `v` scoped to `index-name`. Pass the result as the first
  argument to vector data operations."
  [v index-name]
  (let [v (assoc v :vector-index-name index-name)]
    (or (specs/ensure-vector-storage v) v)))

;; ---------------------------------------------------------------------------
;; Bucket operations
;; ---------------------------------------------------------------------------

(defn create-bucket
  "Creates a vector bucket named `name`."
  [v name]
  (or (specs/ensure-vector-storage v)
      (vector-post (:client v) "CreateVectorBucket" {:vectorBucketName name})))

(defn get-bucket
  "Retrieves metadata for the vector bucket `name`."
  [v name]
  (or (specs/ensure-vector-storage v)
      (vector-post (:client v) "GetVectorBucket" {:vectorBucketName name})))

(defn- list-options-body [opts]
  (cond-> {:maxResults (or (:max-results opts) 100)}
    (:prefix opts)     (assoc :prefix (:prefix opts))
    (:next-token opts) (assoc :nextToken (:next-token opts))))

(defn list-buckets
  "Lists vector buckets, optionally filtered and paginated.

  ## Options

  * `:prefix` — name prefix filter
  * `:max-results` — page size (default 100)
  * `:next-token` — pagination token from a previous response"
  ([v] (list-buckets v {}))
  ([v opts]
   (or (specs/ensure-vector-storage v)
       (specs/ensure-valid specs/ListVectorBucketsOpts opts)
       (vector-post (:client v) "ListVectorBuckets" (list-options-body opts)))))

(defn delete-bucket
  "Deletes the vector bucket `name`. The bucket must be empty first."
  [v name]
  (or (specs/ensure-vector-storage v)
      (vector-post (:client v) "DeleteVectorBucket" {:vectorBucketName name})))

;; ---------------------------------------------------------------------------
;; Index operations
;; ---------------------------------------------------------------------------

(defn- metadata-configuration-body [mc]
  (when mc
    (cond-> {}
      (:non-filterable-metadata-keys mc)
      (assoc :nonFilterableMetadataKeys (:non-filterable-metadata-keys mc)))))

(defn- create-index-body [bucket-name params]
  (cond-> {:vectorBucketName bucket-name
           :indexName (:index-name params)
           :dataType (name (:data-type params))
           :dimension (:dimension params)
           :distanceMetric (name (:distance-metric params))}
    (:metadata-configuration params)
    (assoc :metadataConfiguration
           (metadata-configuration-body (:metadata-configuration params)))))

(defn create-index
  "Creates a vector index within the instance's bucket.

  ## Params

  * `:index-name` — unique name within the bucket (required)
  * `:data-type` — `:float32` (required, the only supported type)
  * `:dimension` — vector dimensionality, e.g. 384 (required)
  * `:distance-metric` — `:cosine`, `:euclidean`, or `:dotproduct` (required)
  * `:metadata-configuration` — optional map with
    `:non-filterable-metadata-keys`"
  [v params]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (specs/ensure-valid specs/CreateIndexParams params)
      (vector-post (:client v) "CreateIndex"
                   (create-index-body (:vector-bucket-name v) params))))

(defn get-index
  "Retrieves metadata for the index `index-name` in the instance's bucket."
  [v index-name]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (vector-post (:client v) "GetIndex"
                   {:vectorBucketName (:vector-bucket-name v)
                    :indexName index-name})))

(defn list-indexes
  "Lists vector indexes in the instance's bucket. Options match
  `list-buckets`."
  ([v] (list-indexes v {}))
  ([v opts]
   (or (specs/ensure-vector-storage v)
       (ensure-bucket-scope v)
       (specs/ensure-valid specs/ListIndexesOpts opts)
       (vector-post (:client v) "ListIndexes"
                    (assoc (list-options-body opts)
                           :vectorBucketName (:vector-bucket-name v))))))

(defn delete-index
  "Deletes the index `index-name` and all its data from the instance's
  bucket."
  [v index-name]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (vector-post (:client v) "DeleteIndex"
                   {:vectorBucketName (:vector-bucket-name v)
                    :indexName index-name})))

;; ---------------------------------------------------------------------------
;; Vector data operations
;; ---------------------------------------------------------------------------

(defn put-vectors
  "Inserts or updates `vectors` in batch (1-500 per request).

  Each vector is API-shaped and passed through as-is:

      {:key \"doc-1\" :data {:float32 [0.1 0.2 ...]} :metadata {...}}"
  [v vectors]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (ensure-index-scope v)
      (specs/ensure-valid specs/VectorBatch vectors)
      (vector-post (:client v) "PutVectors"
                   {:vectorBucketName (:vector-bucket-name v)
                    :indexName (:vector-index-name v)
                    :vectors vectors})))

(defn get-vectors
  "Retrieves vectors by their `keys` in batch.

  ## Options

  * `:keys` — vector keys to fetch (required)
  * `:return-data` — include vector data in the response
  * `:return-metadata` — include metadata in the response"
  [v opts]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (ensure-index-scope v)
      (specs/ensure-valid specs/GetVectorsOpts opts)
      (vector-post (:client v) "GetVectors"
                   (cond-> {:vectorBucketName (:vector-bucket-name v)
                            :indexName (:vector-index-name v)
                            :keys (:keys opts)}
                     (contains? opts :return-data)
                     (assoc :returnData (:return-data opts))
                     (contains? opts :return-metadata)
                     (assoc :returnMetadata (:return-metadata opts))))))

(defn list-vectors
  "Lists vectors in the instance's index with pagination.

  ## Options

  * `:max-results` — page size
  * `:next-token` — pagination token from a previous response
  * `:return-data` — include vector data in the response
  * `:return-metadata` — include metadata in the response
  * `:segment-count` — parallel scan segment count (1-16)
  * `:segment-index` — segment to return; with `:segment-count` given,
    must be within `[0, segment-count)`"
  ([v] (list-vectors v {}))
  ([v opts]
   (or (specs/ensure-vector-storage v)
       (ensure-bucket-scope v)
       (ensure-index-scope v)
       (specs/ensure-valid specs/ListVectorsOpts opts)
       (vector-post (:client v) "ListVectors"
                    (cond-> {:vectorBucketName (:vector-bucket-name v)
                             :indexName (:vector-index-name v)}
                      (contains? opts :max-results)
                      (assoc :maxResults (:max-results opts))
                      (contains? opts :next-token)
                      (assoc :nextToken (:next-token opts))
                      (contains? opts :return-data)
                      (assoc :returnData (:return-data opts))
                      (contains? opts :return-metadata)
                      (assoc :returnMetadata (:return-metadata opts))
                      (contains? opts :segment-count)
                      (assoc :segmentCount (:segment-count opts))
                      (contains? opts :segment-index)
                      (assoc :segmentIndex (:segment-index opts)))))))

(defn query-vectors
  "Queries for similar vectors using approximate nearest neighbor search.

  ## Query

  * `:query-vector` — `{:float32 [...]}` (required)
  * `:top-k` — number of nearest neighbors to return
  * `:filter` — metadata filter map
  * `:return-distance` — include distances in the response
  * `:return-metadata` — include metadata in the response"
  [v query]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (ensure-index-scope v)
      (specs/ensure-valid specs/QueryVectorsQuery query)
      (vector-post (:client v) "QueryVectors"
                   (cond-> {:vectorBucketName (:vector-bucket-name v)
                            :indexName (:vector-index-name v)
                            :queryVector (:query-vector query)}
                     (contains? query :top-k)
                     (assoc :topK (:top-k query))
                     (contains? query :filter)
                     (assoc :filter (:filter query))
                     (contains? query :return-distance)
                     (assoc :returnDistance (:return-distance query))
                     (contains? query :return-metadata)
                     (assoc :returnMetadata (:return-metadata query))))))

(defn delete-vectors
  "Deletes vectors by their `keys` in batch (1-500 per request)."
  [v keys]
  (or (specs/ensure-vector-storage v)
      (ensure-bucket-scope v)
      (ensure-index-scope v)
      (specs/ensure-valid specs/VectorKeys keys)
      (vector-post (:client v) "DeleteVectors"
                   {:vectorBucketName (:vector-bucket-name v)
                    :indexName (:vector-index-name v)
                    :keys keys})))
