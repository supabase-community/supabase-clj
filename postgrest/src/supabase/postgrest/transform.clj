(ns supabase.postgrest.transform
  "Result-shape transforms: order, limit, range, single, csv, geojson,
  explain, rollback, returning."
  (:refer-clojure :exclude [range])
  (:require [clojure.string :as str]
            [supabase.core.http :as http]
            [supabase.postgrest.specs :as specs]))

;; ---------------------------------------------------------------------------
;; Media-type table
;; ---------------------------------------------------------------------------

(def accept-headers
  {:default      "*/*"
   :csv          "text/csv"
   :json         "application/json"
   :openapi      "application/openapi+json"
   :postgis      "application/geo+json"
   :pgrst-plan   "application/vnd.pgrst.plan+json"
   :pgrst-object "application/vnd.pgrst.object+json"
   :pgrst-array  "application/vnd.pgrst.array+json"})

(defn set-accept
  "Replaces the request's `accept` header with the value mapped to
  `media-type` (a keyword from `accept-headers`)."
  [req media-type]
  (let [header (get accept-headers media-type (:default accept-headers))]
    (http/with-headers req {"accept" header})))

;; ---------------------------------------------------------------------------
;; order / limit / range
;; ---------------------------------------------------------------------------

(defn order
  "Order results by `column`. Multiple calls stack.

  Opts:
    :asc          — boolean (default false → desc, matching elixir port)
    :null-first   — boolean (default false → nullslast)
    :foreign-table — scope order to a related table"
  ([req column] (order req column {}))
  ([req column opts]
   (or (specs/ensure-valid specs/OrderOpts opts)
       (let [direction (if (:asc opts) "asc" "desc")
             nulls (if (:null-first opts) "nullsfirst" "nullslast")
             foreign (:foreign-table opts)
             k (if foreign (str foreign ".order") "order")
             value (str column "." direction "." nulls)]
         (http/merge-query-param req k value ",")))))

(defn limit
  "Limit results to `n`. Optionally scope to a foreign table."
  ([req n] (limit req n {}))
  ([req n opts]
   (or (specs/ensure-valid specs/ForeignTableOpts opts)
       (let [foreign (:foreign-table opts)
             k (if foreign (str foreign ".limit") "limit")]
         (http/with-query req {k (str n)})))))

(defn range
  "0-based inclusive range. `(range req 1 3)` returns rows 2, 3, 4."
  ([req from to] (range req from to {}))
  ([req from to opts]
   (or (specs/ensure-valid specs/ForeignTableOpts opts)
       (let [foreign (:foreign-table opts)
             offset-k (if foreign (str foreign ".offset") "offset")
             limit-k  (if foreign (str foreign ".limit") "limit")]
         (-> req
             (http/with-query {offset-k (str from)})
             (http/with-query {limit-k (str (inc (- to from)))}))))))

;; ---------------------------------------------------------------------------
;; single / maybe-single / csv / geojson
;; ---------------------------------------------------------------------------

(defn single
  "Return data as a single object instead of an array. Errors if rows != 1."
  [req]
  (set-accept req :pgrst-object))

(defn maybe-single
  "Like `single` but tolerates 0-row results. GET requests use json; other
  methods use pgrst-object."
  [req]
  (if (= :get (:method req))
    (set-accept req :json)
    (set-accept req :pgrst-object)))

(defn csv
  "Return data as a CSV string."
  [req]
  (http/with-headers req {"accept" "text/csv"}))

(defn geojson
  "Return data as a GeoJSON object."
  [req]
  (http/with-headers req {"accept" "application/geo+json"}))

;; ---------------------------------------------------------------------------
;; explain
;; ---------------------------------------------------------------------------

(def ^:private explain-defaults
  {:analyze false :verbose false :settings false :buffers false :wal false})

(defn explain
  "Return the EXPLAIN plan for the query. Requires `db_plan_enabled`
  on the Supabase project."
  ([req] (explain req {}))
  ([req opts]
   (or (specs/ensure-valid specs/ExplainOpts opts)
       (let [format (let [f (or (:format opts) :text)]
                      (if (#{:json :text} f) (str "+" (name f) ";") "+text;"))
             merged (merge explain-defaults (dissoc opts :format))
             flags (->> merged
                        (filter (fn [[_ v]] v))
                        (map (fn [[k _]] (name k)))
                        (str/join "|"))
             opts-str (str "options:" flags)
             current-accept (or (get-in req [:headers "accept"]) "application/json")
             for-mediatype (str "for=" current-accept)
             plan (str "application/vnd.pgrst.plan" format ";"
                       for-mediatype ";" opts-str)]
         (http/with-headers req {"accept" plan})))))

;; ---------------------------------------------------------------------------
;; rollback / returning
;; ---------------------------------------------------------------------------

(defn rollback
  "Run the query without committing. Data still returned."
  [req]
  (let [existing (get-in req [:headers "prefer"])
        new-prefer (if existing
                     (str existing ",tx=rollback")
                     "tx=rollback")]
    (http/with-headers req {"prefer" new-prefer})))

(defn returning
  "Selects which columns are returned for INSERT/UPDATE/UPSERT/DELETE.

  `columns` is `[]` (all columns), a vector of names, or `\"*\"`."
  ([req] (returning req []))
  ([req columns]
   (let [col-name (fn [c] (if (keyword? c) (name c) (str/trim (str c))))
         cols-str (cond
                    (= "*" columns) "*"
                    (or (nil? columns) (and (sequential? columns) (empty? columns))) "*"
                    (sequential? columns) (str/join "," (map col-name columns))
                    :else (str columns))
         existing-prefer (get-in req [:headers "prefer"])
         new-prefer (if existing-prefer
                      (str existing-prefer ",return=representation")
                      "return=representation")]
     (-> req
         (http/with-query {"select" cols-str})
         (http/with-headers {"prefer" new-prefer})))))
