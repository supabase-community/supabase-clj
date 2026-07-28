(ns supabase.storage.specs
  "Malli schemas for Supabase Storage operation inputs.

  Schemas validate caller arguments only — response bodies are returned as
  plain JSON-decoded maps. See https://supabase.com/docs/reference/javascript/storage-createbucket"
  (:require [malli.core :as m]
            [supabase.core.client :as client]
            [supabase.core.error :as error]))

(def ^:private BucketType
  (m/schema [:enum "STANDARD" "ANALYTICS" :standard :analytics]))

(def BucketCreate
  "Schema for create-bucket attributes. `id` is passed positionally and is
  not part of this map."
  (m/schema [:map
             {:closed true}
             [:public {:optional true} [:maybe :boolean]]
             [:file-size-limit {:optional true} [:maybe :int]]
             [:allowed-mime-types {:optional true} [:maybe [:vector :string]]]
             [:type {:optional true} [:maybe #'BucketType]]]))

(def BucketUpdate
  "Schema for update-bucket attributes."
  (m/schema [:map
             {:closed true}
             [:public {:optional true} [:maybe :boolean]]
             [:file-size-limit {:optional true} [:maybe :int]]
             [:allowed-mime-types {:optional true} [:maybe [:vector :string]]]
             [:type {:optional true} [:maybe #'BucketType]]]))

(def Storage
  "Schema for a storage instance map produced by `from`."
  (m/schema [:map
             {:closed true}
             [:client #'client/Client]
             [:bucket-id :string]]))

(def SortBy
  (m/schema [:map
             {:closed true}
             [:column {:optional true} :string]
             [:order {:optional true} [:enum "asc" "desc"]]]))

(def SearchOptions
  "Schema for list-files search options. All fields optional."
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:offset {:optional true} :int]
             [:sort-by {:optional true} #'SortBy]
             [:search {:optional true} :string]]))

(def FileOptions
  "Schema for upload/update options."
  (m/schema [:map
             {:closed true}
             [:cache-control {:optional true} :string]
             [:content-type {:optional true} :string]
             [:upsert {:optional true} :boolean]
             [:metadata {:optional true} [:map-of :string :string]]
             [:headers {:optional true} [:map-of :string :string]]]))

(def TransformOptions
  "Schema for image transformation options applied on render.

  * `:width` / `:height` — target size in pixels
  * `:resize` — `\"cover\"` (default), `\"contain\"`, `\"fill\"`
  * `:quality` — 20–100 (default 80)
  * `:format` — e.g. `\"origin\"`, `\"webp\"`"
  (m/schema [:map
             {:closed true}
             [:width {:optional true} :int]
             [:height {:optional true} :int]
             [:resize {:optional true} [:enum "cover" "contain" "fill"
                                        :cover :contain :fill]]
             [:quality {:optional true} [:int {:min 20 :max 100}]]
             [:format {:optional true} :string]]))

(def ListV2Options
  "Schema for cursor-based list-v2 pagination options.

  * `:limit` — page size (default server-side 100)
  * `:cursor` — pagination cursor from a previous response
  * `:with-delimiter` — group by folder hierarchy when true"
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:cursor {:optional true} :string]
             [:with-delimiter {:optional true} :boolean]]))

(def SignedUploadOpts
  "Schema for create-signed-upload-url options."
  (m/schema [:map
             {:closed true}
             [:upsert {:optional true} :boolean]]))

(def DownloadOpts
  "Schema for download options.

  * `:response-as` — `:byte-array` (default) or `:stream`
  * `:range` — `[start end]` byte range (inclusive) for partial downloads
  * `:transform` — image transformation options (renders via render/image)
  * `:headers` — extra request headers"
  (m/schema [:map
             {:closed true}
             [:response-as {:optional true} [:enum :byte-array :stream]]
             [:range {:optional true} [:tuple :int :int]]
             [:transform {:optional true} #'TransformOptions]
             [:headers {:optional true} [:map-of :string :string]]]))

(def MoveCopyOpts
  "Schema for move/copy options."
  (m/schema [:map
             {:closed true}
             [:from :string]
             [:to :string]
             [:destination-bucket {:optional true} [:maybe :string]]]))

(def SignedUrlOpts
  "Schema for create-signed-url options. `expires-in` is required (seconds)."
  (m/schema [:map
             {:closed true}
             [:expires-in :int]
             [:download {:optional true} [:or :boolean :string]]
             [:transform {:optional true} #'TransformOptions]]))

(def SignedUrlsOpts
  "Schema for create-signed-urls options."
  (m/schema [:map
             {:closed true}
             [:expires-in :int]
             [:download {:optional true} [:or :boolean :string]]]))

(def PublicUrlOpts
  "Schema for get-public-url options."
  (m/schema [:map
             {:closed true}
             [:download {:optional true} [:or :boolean :string]]
             [:transform {:optional true} #'TransformOptions]]))

(def UploadBody
  "Schema for upload body — bytes, InputStream, File, or string."
  (m/schema [:fn {:error/message "must be byte-array, InputStream, File, or string"}
             (fn [x]
               (or (string? x)
                   (bytes? x)
                   (instance? java.io.InputStream x)
                   (instance? java.io.File x)))]))

(defn ensure-valid
  "Returns nil if `value` matches `schema`, otherwise an anomaly carrying
  the malli explanation."
  [schema value]
  (when-not (m/validate schema value)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid input"
                    :malli/explanation (m/explain schema value)
                    :supabase/service :storage})))

(defn ensure-storage
  "Returns nil if `s` is a valid storage instance, otherwise an anomaly."
  [s]
  (when-not (m/validate Storage s)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid storage instance"
                    :supabase/service :storage})))

;; ---------------------------------------------------------------------------
;; Vector buckets
;; ---------------------------------------------------------------------------

(def VectorStorage
  "Schema for a vector storage instance map produced by
  `supabase.storage.vector/from` and `supabase.storage.vector/index`.
  `:vector-bucket-name` is required by index operations and
  `:vector-index-name` additionally by vector data operations."
  (m/schema [:map
             {:closed true}
             [:client #'client/Client]
             [:vector-bucket-name {:optional true} :string]
             [:vector-index-name {:optional true} :string]]))

(def ListVectorBucketsOpts
  "Schema for vector list-buckets options. All fields optional."
  (m/schema [:map
             {:closed true}
             [:prefix {:optional true} :string]
             [:max-results {:optional true} :int]
             [:next-token {:optional true} :string]]))

(def ListIndexesOpts
  "Schema for vector list-indexes options. Same shape as
  `ListVectorBucketsOpts`."
  ListVectorBucketsOpts)

(def ^:private MetadataConfiguration
  (m/schema [:map
             {:closed true}
             [:non-filterable-metadata-keys {:optional true}
              [:vector :string]]]))

(def CreateIndexParams
  "Schema for vector create-index params. `vector-bucket-name` comes from
  the instance and is not part of this map."
  (m/schema [:map
             {:closed true}
             [:index-name :string]
             [:data-type [:enum :float32]]
             [:dimension [:int {:min 1}]]
             [:distance-metric [:enum :cosine :euclidean :dotproduct]]
             [:metadata-configuration {:optional true}
              #'MetadataConfiguration]]))

(def ^:private VectorData
  (m/schema [:map
             {:closed true}
             [:float32 [:vector number?]]]))

(def ^:private VectorEntry
  (m/schema [:map
             {:closed true}
             [:key :string]
             [:data #'VectorData]
             [:metadata {:optional true} :map]]))

(def VectorBatch
  "Schema for put-vectors input: 1-500 API-shaped vector entries."
  (m/schema [:vector {:min 1 :max 500} #'VectorEntry]))

(def VectorKeys
  "Schema for delete-vectors input: 1-500 vector keys."
  (m/schema [:vector {:min 1 :max 500} :string]))

(def GetVectorsOpts
  "Schema for get-vectors options. `keys` is required."
  (m/schema [:map
             {:closed true}
             [:keys [:vector {:min 1} :string]]
             [:return-data {:optional true} :boolean]
             [:return-metadata {:optional true} :boolean]]))

(def ListVectorsOpts
  "Schema for list-vectors options. All fields optional; when both
  `:segment-count` and `:segment-index` are given, the index must be
  within `[0, segment-count)`."
  (m/schema [:and
             [:map
              {:closed true}
              [:max-results {:optional true} :int]
              [:next-token {:optional true} :string]
              [:return-data {:optional true} :boolean]
              [:return-metadata {:optional true} :boolean]
              [:segment-count {:optional true} [:int {:min 1 :max 16}]]
              [:segment-index {:optional true} [:int {:min 0}]]]
             [:fn {:error/message "segment-index must be between 0 and segment-count - 1"}
              (fn [{:keys [segment-count segment-index]}]
                (or (nil? segment-count)
                    (nil? segment-index)
                    (< segment-index segment-count)))]]))

(def QueryVectorsQuery
  "Schema for query-vectors input. `:query-vector` is required."
  (m/schema [:map
             {:closed true}
             [:query-vector #'VectorData]
             [:top-k {:optional true} :int]
             [:filter {:optional true} :map]
             [:return-distance {:optional true} :boolean]
             [:return-metadata {:optional true} :boolean]]))

(defn ensure-vector-storage
  "Returns nil if `v` is a valid vector storage instance, otherwise an
  anomaly."
  [v]
  (when-not (m/validate VectorStorage v)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid vector storage instance"
                    :supabase/service :storage})))
