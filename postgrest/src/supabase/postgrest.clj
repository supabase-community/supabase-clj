(ns supabase.postgrest
  "Idiomatic Clojure builder for the PostgREST API.

  Builds a request map via thread-first, then `execute` runs it through
  `supabase.core.http` and returns either a response or an anomaly.

  ## Quick start

      (require '[supabase.core.client :as sc]
               '[supabase.postgrest :as pg])

      (def client (sc/make-client \"https://abc.supabase.co\" \"anon-key\"))

      ;; SELECT
      (-> (pg/from client \"users\")
          (pg/select \"*\")
          (pg/eq \"active\" true)
          (pg/order \"created_at\")
          (pg/limit 10)
          (pg/execute))

      ;; INSERT
      (-> (pg/from client \"users\")
          (pg/insert {:email \"a@b.com\"})
          (pg/execute))

      ;; UPDATE filtered
      (-> (pg/from client \"users\")
          (pg/eq \"id\" 42)
          (pg/update {:name \"Jane\"})
          (pg/execute))

      ;; DELETE filtered
      (-> (pg/from client \"users\")
          (pg/eq \"id\" 42)
          (pg/delete)
          (pg/execute))

      ;; RPC
      (-> (pg/rpc client \"my_function\" {:arg 1})
          (pg/execute))

  See sub-namespaces for the full API:
    - `supabase.postgrest.filters`  — eq / gt / like / contains / and / or / not / ...
    - `supabase.postgrest.query`    — select / embed / insert / upsert / update / delete / rpc / aggregations
    - `supabase.postgrest.transform`— order / limit / range / single / csv / explain / returning / ...
    - `supabase.postgrest.encode`   — pg-array / pg-range / pg-bool / ->iso (type coercion helpers)"
  (:refer-clojure :exclude [count filter min max range update])
  (:require [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.postgrest.error :as pg-error]
            [supabase.postgrest.filters :as filters]
            [supabase.postgrest.query :as query]
            [supabase.postgrest.transform :as transform]))

;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn from
  "Starts a builder bound to `table`. Returns a request map you can
  thread through filters / verbs / transforms before calling `execute`.
  Returns an anomaly if the client is invalid."
  [c table]
  (or (client/ensure-client c)
      (-> (http/request c)
          (http/with-service-url :database-url (str "/" table))
          (assoc :service :postgrest))))

(defn schema
  "Override the schema used for THIS request only. Defaults to the
  client's `:db :schema`."
  [req new-schema]
  (assoc-in req [:client :db :schema] new-schema))

(defn with-custom-media-type
  "Override the accept header via a media-type keyword.

  Valid keys: `:default`, `:csv`, `:json`, `:openapi`, `:postgis`,
  `:pgrst-plan`, `:pgrst-object`, `:pgrst-array`."
  [req media-type]
  (transform/set-accept req media-type))

(defn- apply-profile-header
  "Adds the `accept-profile` (GET/HEAD) or `content-profile` (mutations)
  header for the schema."
  [req]
  (let [schema (get-in req [:client :db :schema] "public")
        k (if (#{:get :head} (:method req)) "accept-profile" "content-profile")]
    (http/with-headers req {k schema})))

(defn execute
  "Runs the built request. Returns `{:status :body :headers}` or an
  anomaly enriched with PostgREST error metadata."
  [req]
  (if (error/anomaly? req)
    req
    (-> req apply-profile-header http/execute pg-error/enrich)))

;; ---------------------------------------------------------------------------
;; Re-exports — flat surface so callers `(pg/eq req col val)` etc.
;; ---------------------------------------------------------------------------

;; Filters
(def eq            filters/eq)
(def neq           filters/neq)
(def gt            filters/gt)
(def gte           filters/gte)
(def lt            filters/lt)
(def lte           filters/lte)
(def like          filters/like)
(def ilike         filters/ilike)
(def like-all-of   filters/like-all-of)
(def like-any-of   filters/like-any-of)
(def ilike-all-of  filters/ilike-all-of)
(def ilike-any-of  filters/ilike-any-of)
(def is            filters/is)
(def within        filters/within)
(def contains      filters/contains)
(def contained-by  filters/contained-by)
(def overlaps      filters/overlaps)
(def range-lt      filters/range-lt)
(def range-gt      filters/range-gt)
(def range-gte     filters/range-gte)
(def range-lte     filters/range-lte)
(def range-adjacent filters/range-adjacent)
(def text-search   filters/text-search)
(def all-of        filters/all-of)
(def any-of        filters/any-of)
(def negate        filters/negate)
(def filter        filters/filter)
(def match         filters/match)

;; Query verbs
(def embed   query/embed)
(def select  query/select)
(def insert  query/insert)
(def upsert  query/upsert)
(def update  query/update)
(def delete  query/delete)
(def rpc     query/rpc)

;; Aggregations
(def sum   query/sum)
(def avg   query/avg)
(def min   query/min)
(def max   query/max)
(def count query/count)

;; Transforms
(def order        transform/order)
(def limit        transform/limit)
(def range        transform/range)
(def single       transform/single)
(def maybe-single transform/maybe-single)
(def csv          transform/csv)
(def geojson      transform/geojson)
(def explain      transform/explain)
(def rollback     transform/rollback)
(def returning    transform/returning)
