(ns supabase.storage.specs
  "Malli schemas for Supabase Storage operation inputs.

  Schemas validate caller arguments only — response bodies are returned as
  plain JSON-decoded maps. See https://supabase.com/docs/reference/javascript/storage-createbucket"
  (:require [malli.core :as m]
            [supabase.core.client :as client]
            [supabase.core.error :as error]))

(def ^:private BucketType
  (m/schema [:enum "STANDARD" "ANALYTICS" :standard :analytics]))

(def ^:private VersioningStatusCreate
  "Versioning status settable at bucket creation. `SUSPENDED` is excluded:
  it only makes sense for a bucket that already had versioning enabled."
  (m/schema [:enum "DISABLED" "ENABLED" :disabled :enabled]))

(def ^:private VersioningStatusUpdate
  "Versioning status settable on an existing bucket. `DISABLED` is
  excluded: there is no transition back once versioning has been touched."
  (m/schema [:enum "ENABLED" "SUSPENDED" :enabled :suspended]))

(def BucketCreate
  "Schema for create-bucket attributes. `id` is passed positionally and is
  not part of this map."
  (m/schema [:map
             {:closed true}
             [:public {:optional true} [:maybe :boolean]]
             [:file-size-limit {:optional true} [:maybe :int]]
             [:allowed-mime-types {:optional true} [:maybe [:vector :string]]]
             [:type {:optional true} [:maybe #'BucketType]]
             [:versioning-status {:optional true} [:maybe #'VersioningStatusCreate]]]))

(def BucketUpdate
  "Schema for update-bucket attributes."
  (m/schema [:map
             {:closed true}
             [:public {:optional true} [:maybe :boolean]]
             [:file-size-limit {:optional true} [:maybe :int]]
             [:allowed-mime-types {:optional true} [:maybe [:vector :string]]]
             [:type {:optional true} [:maybe #'BucketType]]
             [:versioning-status {:optional true} [:maybe #'VersioningStatusUpdate]]]))

(def ^:private BucketSortColumn
  (m/schema [:enum "id" "name" "created_at" "updated_at"
             :id :name :created-at :updated-at]))

(def BucketSortBy
  "Schema for list-buckets `:sort-by`. Column keywords may be kebab-case
  (`:created-at`); they are rendered snake_case on the wire."
  (m/schema [:map
             {:closed true}
             [:column {:optional true} #'BucketSortColumn]
             [:order {:optional true} [:enum "asc" "desc" :asc :desc]]]))

(def ListBucketsOptions
  "Schema for list-buckets options. All fields optional."
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:offset {:optional true} :int]
             [:sort-by {:optional true} #'BucketSortBy]
             [:search {:optional true} :string]]))

(def PurgeCacheOpts
  "Schema for purge-cache and purge-bucket-cache options. Mirroring
  storage-js, `:transformations` may only be true (omit it to purge all
  cached versions)."
  (m/schema [:map
             {:closed true}
             [:transformations {:optional true} [:= true]]]))

;; ---------------------------------------------------------------------------
;; Bucket lifecycle
;; ---------------------------------------------------------------------------

(def ^:private LifecycleRuleStatus
  (m/schema [:enum "Enabled" "Disabled" :enabled :disabled]))

(def ^:private NoncurrentVersionExpiration
  "When to expire noncurrent (previous) object versions. `noncurrent-days`
  is how old a version must be before it can expire;
  `newer-noncurrent-versions` keeps that many of the newest noncurrent
  versions regardless of age (1-100)."
  (m/schema [:map
             {:closed true}
             [:noncurrent-days [:int {:min 1}]]
             [:newer-noncurrent-versions {:optional true} [:int {:min 1 :max 100}]]]))

(def ^:private LifecycleRule
  "One lifecycle rule. Today the only action is
  `:noncurrent-version-expiration`. `:filter` is required and must be `{}`:
  prefix, tag and size filters are rejected by the server. `:id` is
  optional; the server generates one when omitted."
  (m/schema [:map
             {:closed true}
             [:id {:optional true} :string]
             [:status #'LifecycleRuleStatus]
             [:filter [:map {:closed true}]]
             [:noncurrent-version-expiration #'NoncurrentVersionExpiration]]))

(def LifecycleConfiguration
  "Schema for a bucket lifecycle configuration: 1-1000 rules, rule ids
  unique when set."
  (m/schema [:and
             [:map
              {:closed true}
              [:rules [:vector {:min 1 :max 1000} #'LifecycleRule]]]
             [:fn {:error/message "lifecycle rule ids must be unique"}
              (fn [{:keys [rules]}]
                (let [ids (keep :id rules)]
                  (or (empty? ids) (apply distinct? ids))))]]))

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

(def ^:private VersionListing
  "How versioned listings treat noncurrent versions and delete markers."
  (m/schema [:enum "exclude" "include" "only" :exclude :include :only]))

(def SearchOptions
  "Schema for list-files search options. All fields optional.

  * `:noncurrent-versions` — `:exclude` (default), `:include` or `:only`;
    controls whether noncurrent object versions appear
  * `:delete-markers` — `:exclude` (default), `:include` or `:only`
  * `:exact-match` — only objects whose key exactly matches the prefix"
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:offset {:optional true} :int]
             [:sort-by {:optional true} #'SortBy]
             [:search {:optional true} :string]
             [:noncurrent-versions {:optional true} #'VersionListing]
             [:delete-markers {:optional true} #'VersionListing]
             [:exact-match {:optional true} :boolean]]))

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
  * `:with-delimiter` — group by folder hierarchy when true
  * `:noncurrent-versions` — `:exclude` (default), `:include` or `:only`
  * `:delete-markers` — `:exclude` (default), `:include` or `:only`
  * `:exact-match` — only objects whose key exactly matches the prefix"
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:cursor {:optional true} :string]
             [:with-delimiter {:optional true} :boolean]
             [:noncurrent-versions {:optional true} #'VersionListing]
             [:delete-markers {:optional true} #'VersionListing]
             [:exact-match {:optional true} :boolean]]))

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
  * `:cache-nonce` — value for the `cacheNonce` query param (cache busting)
  * `:version-id` — download a specific object version instead of the
    current one (requires bucket versioning)
  * `:headers` — extra request headers"
  (m/schema [:map
             {:closed true}
             [:response-as {:optional true} [:enum :byte-array :stream]]
             [:range {:optional true} [:tuple :int :int]]
             [:transform {:optional true} #'TransformOptions]
             [:cache-nonce {:optional true} :string]
             [:version-id {:optional true} :string]
             [:headers {:optional true} [:map-of :string :string]]]))

(def InfoOpts
  "Schema for info options. `:version-id` retrieves metadata for a specific
  object version instead of the current one."
  (m/schema [:map
             {:closed true}
             [:version-id {:optional true} :string]]))

(def RemovePaths
  "Schema for remove paths: a vector of plain paths (deletes the version
  currently at each path) or `{:path :version-id}` maps (deletes an exact
  version, current or archived)."
  (m/schema [:vector {:min 1}
             [:or :string
              [:map {:closed true}
               [:path :string]
               [:version-id :string]]]]))

(def MoveCopyOpts
  "Schema for move/copy options. `:source-version-id` moves/copies a
  specific version of the source object instead of the current one."
  (m/schema [:map
             {:closed true}
             [:from :string]
             [:to :string]
             [:destination-bucket {:optional true} [:maybe :string]]
             [:source-version-id {:optional true} [:maybe :string]]]))

(def SignedUrlOpts
  "Schema for create-signed-url options. `expires-in` is required (seconds).
  `:version-id` signs a specific object version instead of the current one."
  (m/schema [:map
             {:closed true}
             [:expires-in :int]
             [:download {:optional true} [:or :boolean :string]]
             [:transform {:optional true} #'TransformOptions]
             [:version-id {:optional true} :string]]))

(def SignedUrlsOpts
  "Schema for create-signed-urls options."
  (m/schema [:map
             {:closed true}
             [:expires-in :int]
             [:download {:optional true} [:or :boolean :string]]]))

(def PublicUrlOpts
  "Schema for get-public-url options. `:version-id` returns the URL for a
  specific object version instead of the current one."
  (m/schema [:map
             {:closed true}
             [:download {:optional true} [:or :boolean :string]]
             [:transform {:optional true} #'TransformOptions]
             [:version-id {:optional true} :string]]))

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
;; Analytics buckets
;; ---------------------------------------------------------------------------

(def ListAnalyticsBucketsOpts
  "Schema for analytics list-buckets options. All fields optional."
  (m/schema [:map
             {:closed true}
             [:limit {:optional true} :int]
             [:offset {:optional true} :int]
             [:sort-column {:optional true} [:enum :name :created-at :updated-at]]
             [:sort-order {:optional true} [:enum :asc :desc]]
             [:search {:optional true} :string]]))
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
  "Schema for query-vectors input. `:query-vector` is required. `:top-k`
  accepts 1-10000 (S3 vector buckets; other backends may cap lower)."
  (m/schema [:map
             {:closed true}
             [:query-vector #'VectorData]
             [:top-k {:optional true} [:int {:min 1 :max 10000}]]
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
