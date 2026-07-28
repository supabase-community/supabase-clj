(ns supabase.storage.analytics
  "Analytics buckets (Iceberg tables) against Supabase Storage.

  Provides analytics bucket CRUD (create, list, delete) plus
  `catalog-info`, which returns the connection details needed to point
  an Iceberg REST catalog client at a bucket. These are project-level
  operations and take the client directly.

  Public alpha: this API is part of a public alpha release and may not
  be available to your account type.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.storage.analytics :as analytics])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (analytics/create-bucket c \"analytics-data\")
      (analytics/list-buckets c {:limit 10 :sort-column :created-at
                                 :sort-order :desc})
      (analytics/catalog-info c)

  Each function returns `{:status :body :headers}` on success or an
  anomaly map on failure. Endpoints live under `{storage-url}/iceberg`.
  See https://supabase.com/docs/reference/javascript/storage-analytics-api"
  (:require [supabase.core.client :as client]
            [supabase.core.http :as http]
            [supabase.storage :as storage]
            [supabase.storage.specs :as specs]))

(def ^:private iceberg-uri "/iceberg")

(defn- bucket-path
  ([] (str iceberg-uri "/bucket"))
  ([name] (str iceberg-uri "/bucket/" name)))

(def ^:private sort-column-names
  {:name       "name"
   :created-at "created_at"
   :updated-at "updated_at"})

(defn- list-buckets-query
  "Translates kebab-case list options into the query params the
  analytics API expects, including only keys that were given."
  [opts]
  (cond-> {}
    (contains? opts :limit)       (assoc "limit" (str (:limit opts)))
    (contains? opts :offset)      (assoc "offset" (str (:offset opts)))
    (contains? opts :sort-column) (assoc "sortColumn" (sort-column-names (:sort-column opts)))
    (contains? opts :sort-order)  (assoc "sortOrder" (name (:sort-order opts)))
    (contains? opts :search)      (assoc "search" (:search opts))))

(defn create-bucket
  "Creates an analytics bucket named `name`. Analytics buckets are
  optimized for analytical queries via Apache Iceberg tables.

  Public alpha: this API is part of a public alpha release and may not
  be available to your account type."
  [client name]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :storage-url (bucket-path))
          (storage/with-storage-errors)
          (http/with-body {:name name})
          (http/execute))))

(defn list-buckets
  "Lists the analytics buckets in the project.

  ## Options

  * `:limit` — max buckets to return
  * `:offset` — number of buckets to skip
  * `:sort-column` — `:name`, `:created-at`, or `:updated-at`
  * `:sort-order` — `:asc` or `:desc`
  * `:search` — substring filter on bucket names

  Public alpha: this API is part of a public alpha release and may not
  be available to your account type."
  ([client] (list-buckets client {}))
  ([client opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ListAnalyticsBucketsOpts opts)
       (-> (http/request client)
           (http/with-method :get)
           (http/with-service-url :storage-url (bucket-path))
           (storage/with-storage-errors)
           (http/with-query (list-buckets-query opts))
           (http/execute)))))

(defn delete-bucket
  "Deletes the analytics bucket `name`. The bucket must be empty first.

  Public alpha: this API is part of a public alpha release and may not
  be available to your account type."
  [client name]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :storage-url (bucket-path name))
          (storage/with-storage-errors)
          (http/execute))))

(defn catalog-info
  "Returns everything needed to point an Iceberg REST catalog client at
  this project's analytics buckets: the catalog base URL and the auth
  headers (`authorization` and `apikey`).

  This is the analog of the JS client's `from`/catalog accessor, minus
  bundling a catalog library: pass the returned `:url` and `:headers` to
  whatever Iceberg REST catalog client you use. The bucket name maps to
  the Iceberg warehouse parameter.

  Pure: performs no I/O."
  [client]
  (or (client/ensure-client client)
      {:url (str (:storage-url client) iceberg-uri)
       :headers {"authorization" (str "Bearer " (:access-token client))
                 "apikey" (:api-key client)}}))
