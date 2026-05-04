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
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [supabase.core.client :as client]
            [supabase.core.http :as http]
            [supabase.storage.specs :as specs]))

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
          (http/execute))))

(defn get-bucket
  "Retrieves a bucket by its `id`."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :storage-url (bucket-path id))
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
          (http/with-body (normalize-bucket-attrs attrs))
          (http/execute))))

(defn empty-bucket
  "Removes every object from the bucket without deleting it."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :storage-url (str (bucket-path id) "/empty"))
          (http/execute))))

(defn delete-bucket
  "Deletes the bucket and every object inside it."
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :storage-url (bucket-path id))
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
            (http/execute)))))

(defn exists?
  "Returns true if the object exists, false otherwise. Errors other than
  not-found are still surfaced as false — call `info` if you need detail."
  [s path]
  (if-let [bad (specs/ensure-storage s)]
    (do bad false)
    (let [{:keys [client bucket-id]} s
          resp (-> (http/request client)
                   (http/with-method :head)
                   (http/with-service-url :storage-url
                     (str "/object/" bucket-id "/" (clean-path path)))
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
    a string sets a custom download filename."
  ([s path] (get-public-url s path {}))
  ([s path opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/PublicUrlOpts opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)
             base (str (:storage-url client) "/object/public/" bucket-id "/" cleaned)]
         (append-download base (:download opts))))))

(defn create-signed-url
  "Creates a time-limited signed download URL for `path`.

  ## Options

  * `:expires-in` — TTL in seconds (required)
  * `:download` — see `get-public-url`"
  [s path opts]
  (or (specs/ensure-storage s)
      (specs/ensure-valid specs/SignedUrlOpts opts)
      (let [{:keys [client bucket-id]} s
            cleaned (clean-path path)
            resp (-> (http/request client)
                     (http/with-method :post)
                     (http/with-service-url :storage-url
                       (str "/object/sign/" bucket-id "/" cleaned))
                     (http/with-body {:expiresIn (:expires-in opts)})
                     (http/execute))]
        (if (and (map? resp) (integer? (:status resp)) (< (:status resp) 400))
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

(defn upload
  "Uploads `body` (bytes / InputStream / File / string) to `path` in the
  bucket.

  ## Options

  * `:content-type` — defaults to `\"text/plain;charset=UTF-8\"`
  * `:cache-control` — number of seconds (default `\"3600\"`)
  * `:upsert` — overwrite if the object exists (default false)
  * `:headers` — extra HTTP headers"
  ([s path body] (upload s path body {}))
  ([s path body opts]
   (or (specs/ensure-storage s)
       (specs/ensure-valid specs/UploadBody body)
       (specs/ensure-valid specs/FileOptions opts)
       (let [{:keys [client bucket-id]} s
             cleaned (clean-path path)
             content-type (or (:content-type opts) "text/plain;charset=UTF-8")
             cache-control (or (:cache-control opts) "3600")
             upsert (boolean (:upsert opts))
             headers (cond-> {"content-type" content-type
                              "cache-control" (str "max-age=" cache-control)
                              "x-upsert" (str upsert)}
                       (:headers opts) (merge (:headers opts)))]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :storage-url
               (str "/object/" bucket-id "/" cleaned))
             (http/with-headers headers)
             (http/with-body body)
             (http/execute))))))

(defn download
  "Downloads the raw bytes of the object at `path` from a private bucket.

  Returns `{:status :body :headers}` where `:body` is a byte array."
  [s path]
  (or (specs/ensure-storage s)
      (let [{:keys [client bucket-id]} s
            cleaned (clean-path path)]
        (-> (http/request client)
            (http/with-method :get)
            (http/with-service-url :storage-url
              (str "/object/authenticated/" bucket-id "/" cleaned))
            (http/with-response-as :byte-array)
            (http/execute)))))
