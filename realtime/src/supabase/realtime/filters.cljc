(ns supabase.realtime.filters
  "Fluent builder for postgres_changes `filter` strings.

  Each fn appends one `column=operator.value` condition; conditions are
  joined with commas, which the Realtime server applies as `AND` (OR is not
  supported). Pass the builder straight to `supabase.realtime/on` — it is
  serialized to a string automatically — or call `build` yourself.

  Mirrors realtime-js `RealtimePostgresFilterBuilder`: the operator surface
  is the PostgREST subset the Realtime server evaluates (containment, range
  and full-text operators are intentionally absent), values containing
  reserved characters (`,` `(` `)` `\"` `\\`) or surrounding whitespace are
  double-quoted and escaped the way PostgREST does, and any operator can be
  negated with `not`.

      (require '[supabase.realtime.filters :as f])

      (-> (f/gt \"amount\" 100)
          (f/not \"status\" :in [\"draft\" \"archived\"]))
      ;; builds: amount=gt.100,status=not.in.(draft,archived)"
  (:refer-clojure :exclude [not])
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Value serialization
;; ---------------------------------------------------------------------------

(def ^:private reserved-chars
  "Characters the server reads as condition/list delimiters, plus the quote
  escape characters."
  #{\, \( \) \" \\})

(defn- needs-quoting?
  "True when `s` contains a reserved character or surrounding whitespace."
  [^String s]
  (boolean (or (some reserved-chars s)
               (not= s (str/trim s)))))

(defn- quote-value [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(defn- serialize-scalar
  "Renders a scalar filter value, quoting/escaping when it contains reserved
  characters or surrounding whitespace. nil renders as `null`."
  [v]
  (let [s (if (nil? v) "null" (str v))]
    (if (needs-quoting? s) (quote-value s) s)))

(defn- serialize-is-value
  "Renders an `is` operand: nil, a boolean, or the keywords/strings
  `:null` `:true` `:false` `:unknown`."
  [v]
  (cond
    (nil? v)     "null"
    (keyword? v) (name v)
    :else        (str v)))

(defn- serialize-in-values
  "Renders an `in` operand list: deduped, comma-joined, parenthesized.
  Throws on an empty collection or a nil element (`IN (null)` never matches
  in SQL — use `is`/`not` for null checks)."
  [values]
  (let [vs (if (sequential? values) values [values])]
    (when (empty? vs)
      (throw (ex-info "Realtime `in` filter requires at least one value."
                      {:supabase/service :realtime})))
    (when (some nil? vs)
      (throw (ex-info "Realtime `in` filter does not accept nil values; use `is` for null checks."
                      {:supabase/service :realtime})))
    (str "(" (str/join "," (map serialize-scalar (distinct vs))) ")")))

(defn- serialize
  "Builds the `operator.value` portion of a condition (everything after
  `column=`)."
  [operator value]
  (case operator
    :in (str "in." (serialize-in-values value))
    :is (str "is." (serialize-is-value value))
    (str (name operator) "." (serialize-scalar value))))

;; ---------------------------------------------------------------------------
;; Builder
;; ---------------------------------------------------------------------------

(def ^:private operators
  #{:eq :neq :lt :lte :gt :gte :in :like :ilike :is :match :imatch :isdistinct})

(defn- add-condition
  "Appends `column=[not.]operator.value` to `builder` (a vector of condition
  strings)."
  [builder column operator value negated?]
  (when-not (operators operator)
    (throw (ex-info (str "Unknown realtime filter operator: " operator)
                    {:supabase/service :realtime
                     :realtime/operator operator})))
  (conj (vec builder)
        (str column "=" (when negated? "not.") (serialize operator value))))

(defn- op-fn
  "Builds a public operator fn with arities ([column value]) starting a new
  builder and ([builder column value]) appending to one."
  [operator]
  (fn
    ([column value] (add-condition [] column operator value false))
    ([builder column value] (add-condition builder column operator value false))))

(def eq
  "Rows where `column` equals `value` (`column=eq.value`)."
  (op-fn :eq))

(def neq
  "Rows where `column` does not equal `value` (`column=neq.value`)."
  (op-fn :neq))

(def lt
  "Rows where `column` is less than `value` (`column=lt.value`)."
  (op-fn :lt))

(def lte
  "Rows where `column` is less than or equal to `value` (`column=lte.value`)."
  (op-fn :lte))

(def gt
  "Rows where `column` is greater than `value` (`column=gt.value`)."
  (op-fn :gt))

(def gte
  "Rows where `column` is greater than or equal to `value` (`column=gte.value`)."
  (op-fn :gte))

(def like
  "Rows where `column` matches the case-sensitive `pattern` (`%` wildcards)."
  (op-fn :like))

(def ilike
  "Rows where `column` matches the case-insensitive `pattern`."
  (op-fn :ilike))

(def match
  "Rows where `column` matches the POSIX regex `pattern` (`~`)."
  (op-fn :match))

(def imatch
  "Rows where `column` matches the case-insensitive POSIX regex `pattern` (`~*`)."
  (op-fn :imatch))

(def isdistinct
  "Rows where `column` is distinct from `value` — NULL-safe inequality
  (`IS DISTINCT FROM`)."
  (op-fn :isdistinct))

(defn in
  "Rows where `column` is one of `values` (`column=in.(a,b,c)`). Duplicates
  are removed; at least one value is required; nil elements are rejected
  (use `is` for null checks)."
  ([column values] (add-condition [] column :in values false))
  ([builder column values] (add-condition builder column :in values false)))

(defn is
  "Rows where `column` `IS` the given value (`column=is.null`). Accepts nil,
  a boolean, or `:null`/`:true`/`:false`/`:unknown` (keywords or strings)."
  ([column value] (add-condition [] column :is value false))
  ([builder column value] (add-condition builder column :is value false)))

(defn not
  "Negates any operator with the `not.` prefix (`column=not.operator.value`).

      (f/not \"status\" :in [\"draft\" \"archived\"])
      ;; builds: status=not.in.(draft,archived)

      (f/not \"deleted_at\" :is nil)
      ;; builds: deleted_at=not.is.null"
  ([column operator value] (add-condition [] column operator value true))
  ([builder column operator value] (add-condition builder column operator value true)))

(defn build
  "Serializes `builder` into the comma-separated (AND) filter string. An
  empty builder serializes to `\"\"`, which the server treats as no filter."
  [builder]
  (str/join "," builder))
