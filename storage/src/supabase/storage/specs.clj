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
