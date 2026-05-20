(ns supabase.postgrest.filters
  "Filter builders for PostgREST queries.

  Every fn takes a request map (from `supabase.postgrest/from`) and
  returns it with a query-string filter appended.

  ## Composite filters

  `all-of` / `any-of` accept a nested vector DSL:

      [[:gt \"age\" 18]
       [:eq \"status\" \"active\"]
       [:and [[:lt \"salary\" 5000]
              [:eq \"role\" \"junior\"]]]
       [:not [:eq \"deleted\" true]]]"
  (:refer-clojure :exclude [filter])
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [supabase.core.http :as http]))

(def ^:private valid-ops
  #{:eq :gt :gte :lt :lte :neq :like :ilike :match :imatch :in :is
    :isdistinct :fts :plfts :phfts :wfts :cs :cd :ov :sl :sr :nxr :nxl :adj
    :not :and :or :all :any})

(defn- op-name [op] (name op))

;; ---------------------------------------------------------------------------
;; Condition DSL (mirror elixir process_condition)
;; ---------------------------------------------------------------------------

(declare process-condition)

(defn- fts-op? [op] (#{:fts :plfts :phfts :wfts} op))
(defn- mod-op? [op] (#{:eq :like :ilike :gt :gte :lt :lte :match :imatch} op))

(defn- format-value [op v]
  (cond
    (and (= :in op) (sequential? v)) (str "(" (str/join "," v) ")")
    (and (= :is op) (nil? v))        "null"
    (sequential? v)                  (str "[" (str/join "," v) "]")
    :else                            (str v)))

(defn process-condition
  "Recursively encodes a DSL condition to its PostgREST string form."
  [cond-form]
  (let [[op & rest] cond-form]
    (case op
      :not (str "not." (process-condition (first rest)))
      :and (str "and(" (str/join "," (map process-condition (first rest))) ")")
      :or  (str "or(" (str/join "," (map process-condition (first rest))) ")")
      ;; default: [op col value] or [op col value opts]
      (let [[column value opts] rest]
        (cond
          ;; FTS with :lang opt
          (and (fts-op? op) (map? opts) (:lang opts))
          (str column "=" (op-name op) "(" (:lang opts) ")." value)

          ;; mod ops with :all / :any
          (and (mod-op? op) (sequential? value) (map? opts))
          (let [body (cond
                       (:all opts) (str (op-name op) "(all).{" (str/join "," value) "}")
                       (:any opts) (str (op-name op) "(any).{" (str/join "," value) "}")
                       :else (str (op-name op) ".{" (str/join "," value) "}"))]
            (str column "=" body))

          :else
          (str column "." (op-name op) "." (format-value op value)))))))

;; ---------------------------------------------------------------------------
;; Generic filter + match
;; ---------------------------------------------------------------------------

(defn filter
  "Escape hatch — apply a raw PostgREST operator. `column`, `op`, and
  `value` are NOT escaped; sanitize them yourself."
  [req column op value]
  (when-not (contains? valid-ops op)
    (throw (ex-info (str "Invalid PostgREST operator: " op)
                    {:op op :valid valid-ops})))
  (http/with-query req {column (str (op-name op) "." value)}))

(defn match
  "Shorthand for multiple `eq` filters from a map of `column => value`."
  [req query-map]
  (reduce-kv (fn [acc k v] (http/with-query acc {k (str "eq." v)}))
             req
             query-map))

;; ---------------------------------------------------------------------------
;; Logical composites
;; ---------------------------------------------------------------------------

(defn- composite-key [op opts]
  (let [base (op-name op)
        foreign (:foreign-table opts)]
    (if foreign (str foreign "." base) base)))

(defn all-of
  "AND-combines `patterns`. Accepts a raw string or a vector of DSL
  conditions. Optional `:foreign-table` scopes to a related table."
  ([req patterns] (all-of req patterns {}))
  ([req patterns opts]
   (let [body (if (string? patterns)
                patterns
                (str/join "," (map process-condition patterns)))
         k (composite-key :and opts)]
     (http/with-query req {k (str "(" body ")")}))))

(defn any-of
  "OR-combines `patterns`. Accepts a raw string or a vector of DSL
  conditions. Optional `:foreign-table` scopes to a related table."
  ([req patterns] (any-of req patterns {}))
  ([req patterns opts]
   (let [body (if (string? patterns)
                patterns
                (str/join "," (map process-condition patterns)))
         k (composite-key :or opts)]
     (http/with-query req {k (str "(" body ")")}))))

(defn negate
  "NOT(op).value on `column`."
  [req column op value]
  (when-not (contains? valid-ops op)
    (throw (ex-info (str "Invalid PostgREST operator: " op)
                    {:op op :valid valid-ops})))
  (http/with-query req {column (str "not." (op-name op) "." value)}))

;; ---------------------------------------------------------------------------
;; Comparison operators
;; ---------------------------------------------------------------------------

(defn- simple-op [op]
  (fn [req column value]
    (http/with-query req {column (str (op-name op) "." value)})))

(def eq   (simple-op :eq))
(def neq  (simple-op :neq))
(def gt   (simple-op :gt))
(def gte  (simple-op :gte))
(def lt   (simple-op :lt))
(def lte  (simple-op :lte))
(def like (simple-op :like))
(def ilike (simple-op :ilike))

;; ---------------------------------------------------------------------------
;; LIKE / ILIKE multi-pattern
;; ---------------------------------------------------------------------------

(defn- list-op [prefix]
  (fn [req column values]
    (http/with-query req
      {column (str prefix ".{" (str/join "," values) "}")})))

(def like-all-of  (list-op "like(all)"))
(def like-any-of  (list-op "like(any)"))
(def ilike-all-of (list-op "ilike(all)"))
(def ilike-any-of (list-op "ilike(any)"))

;; ---------------------------------------------------------------------------
;; is / in
;; ---------------------------------------------------------------------------

(defn is
  "Match rows where `column` IS `value` (typically nil or a boolean)."
  [req column value]
  (let [v (cond
            (nil? value) "null"
            (boolean? value) (str value)
            :else (str value))]
    (http/with-query req {column (str "is." v)})))

(defn within
  "PostgREST `IN` filter. Renamed from `in` to avoid shadowing tools and
  to keep symmetry with `clojure.core` naming."
  [req column values]
  (let [joined (str/join "," (map str values))]
    (http/with-query req {column (str "in.(" joined ")")})))

;; ---------------------------------------------------------------------------
;; contains / contained-by / overlaps (jsonb / array / range)
;; ---------------------------------------------------------------------------

(defn- encode-jsonb-value [v]
  (cond
    (string? v) v
    (sequential? v) (str "{" (str/join "," v) "}")
    (map? v) (json/write-value-as-string v)
    :else (str v)))

(defn contains
  "Match rows where `column` contains every element in `value`."
  [req column value]
  (http/with-query req {column (str "cs." (encode-jsonb-value value))}))

(defn contained-by
  "Match rows where every element of `column` is contained by `value`."
  [req column value]
  (http/with-query req {column (str "cd." (encode-jsonb-value value))}))

(defn overlaps
  "Match rows where `column` and `value` share at least one element."
  [req column value]
  (let [v (cond
            (string? value)     value
            (sequential? value) (str "{" (str/join "," value) "}")
            :else               (str value))]
    (http/with-query req {column (str "ov." v)})))

;; ---------------------------------------------------------------------------
;; Range operators
;; ---------------------------------------------------------------------------

(def range-lt       (simple-op :sl))
(def range-gt       (simple-op :sr))
(def range-gte      (simple-op :nxl))
(def range-lte      (simple-op :nxr))
(def range-adjacent (simple-op :adj))

;; ---------------------------------------------------------------------------
;; Full-text search
;; ---------------------------------------------------------------------------

(defn- fts-type-code [t]
  (case t :plain "pl" :phrase "ph" :websearch "w" nil ""))

(defn text-search
  "Full-text search on `column` for `query`.

  Opts:
    :type   — :plain | :phrase | :websearch
    :config — text-search config name (e.g. \"english\")"
  ([req column query] (text-search req column query {}))
  ([req column query opts]
   (let [type-code (fts-type-code (:type opts))
         config (when-let [c (:config opts)] (str "(" c ")"))]
     (http/with-query req
       {column (str type-code "fts" (or config "") "." query)}))))
