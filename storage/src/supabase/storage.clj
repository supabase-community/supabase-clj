(ns supabase.storage
  "Object storage against Supabase Storage.

  Provides bucket CRUD plus per-bucket file operations (list, remove, move,
  copy, info, exists?, public/signed URLs, upload, download). Per-bucket
  ops take a storage instance returned by `from`.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.storage :as storage])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (storage/list-buckets c)
      (storage/create-bucket c \"avatars\" {:public true})

      (def s (storage/from c \"avatars\"))
      (storage/upload s \"profile.png\" my-bytes
                      {:content-type \"image/png\" :upsert true})
      (storage/download s \"profile.png\")
      (storage/get-public-url s \"profile.png\")

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. See https://supabase.com/docs/reference/javascript/storage-api"
  (:refer-clojure :exclude [remove update])
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.storage.specs :as specs])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(def ^:private bucket-uri "/bucket")

(defn- bucket-path
  ([] bucket-uri)
  ([id] (str bucket-uri "/" id)))

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

(defn- normalize-bucket-attrs
  "Translates kebab-case keys to the snake_case names the Storage API
  expects, dropping nils."
  [attrs]
  (cond-> {}
    (contains? attrs :public)             (assoc :public (:public attrs))
    (contains? attrs :file-size-limit)    (assoc :file_size_limit (:file-size-limit attrs))
    (contains? attrs :allowed-mime-types) (assoc :allowed_mime_types (:allowed-mime-types attrs))
    (contains? attrs :type)               (assoc :type (let [t (:type attrs)]
                                                         (if (keyword? t)
                                                           (-> t name str/upper-case)
                                                           t)))))

(defn- clean-path
  "Strips leading/trailing slashes and collapses runs of `/`."
  [path]
  (-> path
      (str/replace #"^/+|/+$" "")
      (str/replace #"/+" "/")))

(defn- kw->str [v]
  (if (keyword? v) (name v) (str v)))

(defn- transform->query
  "Renders image transform options into a URL query string (no leading
  `?`). Width/height are included only when present; resize, quality,
  and format always carry their defaults, mirroring the Storage API.

  Returns nil when `transform` is nil/empty."
  [transform]
  (when (seq transform)
    (let [params (cond-> []
                   (:width transform)   (conj ["width" (str (:width transform))])
                   (:height transform)  (conj ["height" (str (:height transform))])
                   true                 (conj ["resize" (kw->str (or (:resize transform) "cover"))])
                   true                 (conj ["quality" (str (or (:quality transform) 80))])
                   true                 (conj ["format" (or (:format transform) "origin")]))
          enc (fn [^String s] (URLEncoder/encode s StandardCharsets/UTF_8))]
      (->> params
           (map (fn [[k v]] (str (enc k) "=" (enc v))))
           (str/join "&")))))

(defn- append-query
  "Appends `query` (a `k=v&...` string) to `url`, choosing `?`/`&`."
  [url query]
  (if (seq query)
    (str url (if (str/includes? url "?") "&" "?") query)
    url))

(defn- metadata->header
  "Encodes a metadata map as the base64 JSON the Storage API expects in
  the `x-metadata` header. Returns nil for empty metadata."
  [metadata]
  (when (seq metadata)
    (let [json-bytes (.getBytes (json/write-value-as-string metadata) StandardCharsets/UTF_8)]
      (.encodeToString (Base64/getEncoder) json-bytes))))

(defn- ok-response?
  "True when `resp` is a successful HTTP response map (status < 400)."
  [resp]
  (and (map? resp) (integer? (:status resp)) (< (:status resp) 400)))

(defn- storage-error-parser
  "Maps a Storage error response to an anomaly, preferring the API's
  `message` field for the human-readable text. Wired via
  `http/with-error-parser`."
  [status body _headers _service]
  (let [base (error/from-http-response status body :storage)]
    (if-let [msg (and (map? body) (:message body))]
      (assoc base :cognitect.anomalies/message msg)
      base)))

(defn- with-storage-errors
  "Installs the Storage error parser on a request."
  [req]
  (http/with-error-parser req storage-error-parser))

;; ---------------------------------------------------------------------------
;; Bucket operations
;; ---------------------------------------------------------------------------

(defn list-buckets
  "Lists all buckets in the project."
  [client]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :storage-url (bucket-path))
          (with-storage-errors)
          (http/execute))))

(defn get-bucket
  "Retrieves a bucket by its `id`."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :storage-url (bucket-path id))
          (with-storage-errors)
          (http/execute))))

(defn create-bucket
  "Creates a new bucket with `id` and the given `attrs`.

  ## Attributes (all optional)

  * `:public` — boolean visibility flag (default false)
  * `:file-size-limit` — max file size in bytes
  * `:allowed-mime-types` — vector of allowed MIME types or wildcards
  * `:type` — `\"STANDARD\"` (default) or `\"ANALYTICS\"`"
  ([client id] (create-bucket client id {}))
  ([client id attrs]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/BucketCreate attrs)
       (let [body (assoc (normalize-bucket-attrs attrs)
                         :id id
                         :name (or (:name attrs) id))]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :storage-url (bucket-path))
             (with-storage-errors)
             (http/with-body body)
             (http/execute))))))

(defn update-bucket
  "Updates the bucket identified by `id` with `attrs`.

  Same attributes as `create-bucket` (without `:id`)."
  [client id attrs]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/BucketUpdate attrs)
      (-> (http/request client)
          (http/with-method :put)
          (http/with-service-url :storage-url (bucket-path id))
          (with-storage-errors)
          (http/with-body (normalize-bucket-attrs attrs))
          (http/execute))))

(defn empty-bucket
  "Removes every object from the bucket without deleting it."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :storage-url (str (bucket-path id) "/empty"))
          (with-storage-errors)
          (http/execute))))

(defn delete-bucket
  "Deletes the bucket and every object inside it."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :storage-url (bucket-path id))
          (with-storage-errors)
          (http/execute))))

;; ---------------------------------------------------------------------------
;; Storage instance
;; ---------------------------------------------------------------------------

(defn from
  "Returns a storage instance bound to `bucket-id`. Pass it as the first
  argument to file operations.

      (def s (from client \"avatars\"))
      (list-files s)"
  [client bucket-id]
  (let [s {:client client :bucket-id bucket-id}]
    (or (specs/ensure-storage s) s)))

;; ---------------------------------------------------------------------------
;; File operations
;; ---------------------------------------------------------------------------

(defn- search-options-body [opts]
  (cond-> {}
    (contains? opts :limit)   (assoc :limit (:limit opts))
    (contains? opts :offset)  (assoc :offset (:offset opts))
    (contains? opts :search)  (assoc :search (:search opts))
    (contains? opts :sort-by) (assoc :sortBy (snake-keys (:sort-by opts)))))

(defn list-files
  "Lists files in the bucket, optionally filtered by `prefix`.

  ## Options

  * `:limit` — max results (default server-side: 100)
  * `:offset` — pagination offset
  * `:sort-by` — `{:column \"name\" :order \"asc\"}`
  * `:search` — substring filter"
  ([s] (list-files s nil {}))
  ([s prefix] (list-files s prefix {}))
  ([s prefix opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/SearchOptions opts)
       (let [{:keys [client bucket-id]} s
             body (assoc (search-options-body opts) :prefix (or prefix ""))]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :storage-url (str "/object/list/" bucket-id))
             (with-storage-errors)
             (http/with-body body)
             (http/execute))))))

(defn- list-v2-body [opts]
  (cond-> {}
    (contains? opts :limit)          (assoc :limit (:limit opts))
    (contains? opts :cursor)         (assoc :cursor (:cursor opts))
    (contains? opts :with-delimiter) (assoc :with_delimiter (:with-delimiter opts))))

(defn list-files-v2
  "Lists files using cursor-based pagination (the `list-v2` endpoint).

  Cursor pagination is O(1) regardless of position, unlike the
  offset-based `list-files`.

  ## Options

  * `:limit` — page size (default server-side 100)
  * `:cursor` — pagination cursor from a previous response
  * `:with-delimiter` — group results by folder hierarchy when true"
  ([s] (list-files-v2 s nil {}))
  ([s prefix] (list-files-v2 s prefix {}))
  ([s prefix opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/ListV2Options opts)
       (let [{:keys [client bucket-id]} s
             body (assoc (list-v2-body opts) :prefix (or prefix ""))]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :storage-url (str "/object/list-v2/" bucket-id))
             (with-storage-errors)
             (http/with-body body)
             (http/execute))))))

(defn remove
  "Deletes one or more objects from the bucket.

  `paths` may be a single path string or a vector of paths."
  [s paths]
  (or (specs/ensure-storage s)
      (let [{:keys [client bucket-id]} s
            prefixes (if (coll? paths) (vec paths) [paths])]
        (-> (http/request client)
            (http/with-method :delete)
            (http/with-service-url :storage-url (str "/object/" bucket-id))
            (with-storage-errors)
            (http/with-body {:prefixes prefixes})
            (http/execute)))))

(defn move
  "Moves an object within or across buckets.

  ## Options

  * `:from` — source path (required)
  * `:to` — destination path (required)
  * `:destination-bucket` — target bucket id (optional, same bucket if omitted)"
  [s opts]
  (or (specs/ensure-storage s)
      (specs/ensure-valid specs/MoveCopyOpts opts)
      (let [{:keys [client bucket-id]} s
            {:keys [from to destination-bucket]} opts
            body (cond-> {:bucketId bucket-id
                          :sourceKey from
                          :destinationKey to}
                   destination-bucket (assoc :destinationBucket destination-bucket))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :storage-url "/object/move")
            (with-storage-errors)
            (http/with-body body)
            (http/execute)))))

(defn copy
  "Copies an object within or across buckets. Options match `move`."
  [s opts]
  (or (specs/ensure-storage s)
      (specs/ensure-valid specs/MoveCopyOpts opts)
      (let [{:keys [client bucket-id]} s
            {:keys [from to destination-bucket]} opts
            body (cond-> {:bucketId bucket-id
                          :sourceKey from
                          :destinationKey to}
                   destination-bucket (assoc :destinationBucket destination-bucket))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :storage-url "/object/copy")
            (with-storage-errors)
            (http/with-body body)
            (http/execute)))))

(defn info
  "Retrieves metadata for the object at `path`."
  [s path]
  (or (specs/ensure-storage s)
      (let [{:keys [client bucket-id]} s]
        (-> (http/request client)
            (http/with-method :get)
            (http/with-service-url :storage-url
              (str "/object/info/authenticated/"
                   bucket-id "/" (clean-path path)))
            (with-storage-errors)
            (http/execute)))))

(defn exists?
  "Returns true if the object exists, false otherwise. Errors other than
  not-found are still surfaced as false — call `info` if you need detail."
  [s path]
  (if (specs/ensure-storage s)
    false
    (let [{:keys [client bucket-id]} s
          resp (-> (http/request client)
                   (http/with-method :head)
                   (http/with-service-url :storage-url
                     (str "/object/" bucket-id "/" (clean-path path)))
                   (with-storage-errors)
                   (http/execute))]
      (and (map? resp)
           (integer? (:status resp))
           (< (:status resp) 400)))))

(defn- append-download [url download]
  (if download
    (str url (if (str/includes? url "?") "&" "?")
         "download=" (if (true? download) "" download))
    url))

(defn get-public-url
  "Builds the public download URL for an object. Does not call the API.

  ## Options

  * `:download` — `true` triggers browser download with the object's name;
    a string sets a custom download filename.
  * `:transform` — image transform map; routes through the image render
    endpoint and appends the transform query."
  ([s path] (get-public-url s path {}))
  ([s path opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/PublicUrlOpts opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)
             transform (:transform opts)
             render (if (seq transform) "render/image" "object")
             base (str (:storage-url client) "/" render "/public/" bucket-id "/" cleaned)
             with-dl (append-download base (:download opts))]
         (append-query with-dl (transform->query transform))))))

(defn- transform->body
  "Renders transform options into the body shape the sign endpoint
  expects (snake/camel mix matching the Storage API)."
  [transform]
  (when (seq transform)
    (cond-> {}
      (:width transform)  (assoc :width (:width transform))
      (:height transform) (assoc :height (:height transform))
      true                (assoc :resize (kw->str (or (:resize transform) "cover")))
      true                (assoc :quality (or (:quality transform) 80))
      true                (assoc :format (or (:format transform) "origin")))))

(defn create-signed-url
  "Creates a time-limited signed download URL for `path`.

  ## Options

  * `:expires-in` — TTL in seconds (required)
  * `:download` — see `get-public-url`
  * `:transform` — image transform map applied to the signed asset"
  [s path opts]
  (or (specs/ensure-storage s)
      (specs/ensure-valid specs/SignedUrlOpts opts)
      (let [{:keys [client bucket-id]} s
            cleaned (clean-path path)
            transform (:transform opts)
            body (cond-> {:expiresIn (:expires-in opts)}
                   (seq transform) (assoc :transform (transform->body transform)))
            resp (-> (http/request client)
                     (http/with-method :post)
                     (http/with-service-url :storage-url
                       (str "/object/sign/" bucket-id "/" cleaned))
                     (with-storage-errors)
                     (http/with-body body)
                     (http/execute))]
        (if (ok-response? resp)
          (let [signed (get-in resp [:body :signedURL])
                full (append-download (str (:storage-url client) signed)
                                      (:download opts))]
            (assoc resp :body (assoc (:body resp) :signedURL full)))
          resp))))

(defn create-signed-urls
  "Creates signed URLs for multiple `paths`.

  ## Options

  * `:expires-in` — TTL in seconds (required)
  * `:download` — applied to every returned URL"
  [s paths opts]
  (or (specs/ensure-storage s)
      (specs/ensure-valid specs/SignedUrlsOpts opts)
      (let [{:keys [client bucket-id]} s
            cleaned (mapv clean-path paths)
            resp (-> (http/request client)
                     (http/with-method :post)
                     (http/with-service-url :storage-url (str "/object/sign/" bucket-id))
                     (with-storage-errors)
                     (http/with-body {:expiresIn (:expires-in opts)
                                      :paths cleaned})
                     (http/execute))]
        (if (and (map? resp) (integer? (:status resp)) (< (:status resp) 400)
                 (sequential? (:body resp)))
          (let [dl (:download opts)
                items (mapv (fn [item]
                              (let [signed (:signedURL item)
                                    full (append-download
                                          (str (:storage-url client) signed) dl)]
                                (assoc item :signedURL full)))
                            (:body resp))]
            (assoc resp :body items))
          resp))))

(defn- upload-headers
  "Builds the common header map shared by upload, update, and
  upload-to-signed-url."
  [opts]
  (let [content-type (or (:content-type opts) "text/plain;charset=UTF-8")
        cache-control (or (:cache-control opts) "3600")
        upsert (boolean (:upsert opts))]
    (cond-> {"content-type" content-type
             "cache-control" (str "max-age=" cache-control)
             "x-upsert" (str upsert)}
      (metadata->header (:metadata opts)) (assoc "x-metadata" (metadata->header (:metadata opts)))
      (:headers opts) (merge (:headers opts)))))

(defn upload
  "Uploads `body` (bytes / InputStream / File / string) to `path` in the
  bucket via POST. Fails if the object already exists unless `:upsert`.

  ## Options

  * `:content-type` — defaults to `\"text/plain;charset=UTF-8\"`
  * `:cache-control` — number of seconds (default `\"3600\"`)
  * `:upsert` — overwrite if the object exists (default false)
  * `:metadata` — map of string→string stored as object metadata
  * `:headers` — extra HTTP headers"
  ([s path body] (upload s path body {}))
  ([s path body opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/UploadBody body)
       (specs/ensure-valid specs/FileOptions opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :storage-url
               (str "/object/" bucket-id "/" cleaned))
             (with-storage-errors)
             (http/with-headers (upload-headers opts))
             (http/with-body body)
             (http/execute))))))

(defn update
  "Replaces the object at `path` with `body` via PUT. Unlike `upload`,
  this targets an existing object. Same options as `upload`."
  ([s path body] (update s path body {}))
  ([s path body opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/UploadBody body)
       (specs/ensure-valid specs/FileOptions opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)]
         (-> (http/request client)
             (http/with-method :put)
             (http/with-service-url :storage-url
               (str "/object/" bucket-id "/" cleaned))
             (with-storage-errors)
             (http/with-headers (upload-headers opts))
             (http/with-body body)
             (http/execute))))))

(defn create-signed-upload-url
  "Creates a signed URL that lets a client upload to `path` without
  further authentication. Valid for two hours.

  ## Options

  * `:upsert` — allow overwriting an existing object (default false)

  On success returns `{:status :body :headers}` where `:body` is
  `{:signed-url <url> :token <token> :path <clean-path>}`. The token can
  be fed to `upload-to-signed-url`."
  ([s path] (create-signed-upload-url s path {}))
  ([s path opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/SignedUploadOpts opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)
             upsert (boolean (:upsert opts))
             resp (-> (http/request client)
                      (http/with-method :post)
                      (http/with-service-url :storage-url
                        (str "/object/upload/sign/" bucket-id "/" cleaned))
                      (with-storage-errors)
                      (http/with-headers {"x-upsert" (str upsert)})
                      (http/execute))]
         (if (ok-response? resp)
           (let [relative (get-in resp [:body :url])
                 full (str (:storage-url client) relative)
                 token (second (re-find #"[?&]token=([^&]+)" (or relative "")))]
             (assoc resp :body {:signed-url full
                                :token token
                                :path cleaned}))
           resp)))))

(defn upload-to-signed-url
  "Uploads `body` to a previously created signed upload URL using its
  `token` (see `create-signed-upload-url`). Same options as `upload`."
  ([s path token body] (upload-to-signed-url s path token body {}))
  ([s path token body opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/UploadBody body)
       (specs/ensure-valid specs/FileOptions opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)]
         (-> (http/request client)
             (http/with-method :put)
             (http/with-service-url :storage-url
               (str "/object/upload/sign/" bucket-id "/" cleaned))
             (with-storage-errors)
             (http/with-query {"token" token})
             (http/with-headers (upload-headers opts))
             (http/with-body body)
             (http/execute))))))

(defn- download-path
  "Builds the object download path, switching to the image render
  endpoint when a transform is supplied."
  [bucket-id cleaned transform]
  (if (seq transform)
    (str "/render/image/authenticated/" bucket-id "/" cleaned)
    (str "/object/authenticated/" bucket-id "/" cleaned)))

(defn download
  "Downloads the object at `path` from a private bucket.

  Returns `{:status :body :headers}`. By default `:body` is a byte
  array; pass `:response-as :stream` to receive a `java.io.InputStream`
  (the caller is responsible for closing it).

  ## Options

  * `:response-as` — `:byte-array` (default) or `:stream`
  * `:range` — `[start end]` inclusive byte range for a partial download
  * `:transform` — image transform map (renders via render/image)
  * `:headers` — extra request headers"
  ([s path] (download s path {}))
  ([s path opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/DownloadOpts opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)
             transform (:transform opts)
             as (or (:response-as opts) :byte-array)
             [rstart rend] (:range opts)
             range-header (when (:range opts)
                            {"range" (str "bytes=" rstart "-" rend)})
             query (transform->query transform)]
         (cond-> (http/request client)
           true (http/with-method :get)
           true (http/with-service-url :storage-url
                  (append-query (download-path bucket-id cleaned transform) query))
           true (with-storage-errors)
           true (http/with-response-as as)
           range-header (http/with-headers range-header)
           (:headers opts) (http/with-headers (:headers opts))
           true (http/execute))))))
