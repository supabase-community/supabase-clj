(ns supabase.postgrest.query
  "Query verbs (select/insert/upsert/update/delete) + rpc + aggregations."
  (:refer-clojure :exclude [count max min update])
  (:require [clojure.string :as str]
            [supabase.core.http :as http]
            [supabase.postgrest.specs :as specs]))

;; ---------------------------------------------------------------------------
;; SELECT
;; ---------------------------------------------------------------------------

(defn- select-string [columns]
  (cond
    (= "*" columns) "*"
    (sequential? columns) (str/join "," (map name columns))
    :else (str columns)))

(defn embed
  "Builds an embedded-resource string for use inside a `select` vector
  (resource embedding / joins across foreign keys).

  `relation` is the related table or FK relationship name. `cols` is `\"*\"`
  or a vector of column names (each may itself be an `embed` string for
  nested embeds).

  Opts:
    :as    — alias the embed (`alias:relation(...)`)
    :hint  — disambiguate the FK relationship for ambiguous embeds
             (`relation!hint(...)`)
    :inner — boolean; INNER join — drop parent rows with no match
             (`relation!inner(...)`)
    :left  — boolean; explicit LEFT join (`relation!left(...)`)

      (embed \"messages\" [:id :content] {:inner true})
      ;; => \"messages!inner(id,content)\"
      (embed \"users\" [:name] {:as \"author\" :hint \"author_id\"})
      ;; => \"author:users!author_id(name)\""
  ([relation cols] (embed relation cols {}))
  ([relation cols opts]
   (let [col-str (cond
                   (= "*" cols)       "*"
                   (sequential? cols) (str/join "," (map name cols))
                   :else              (str cols))
         hint (when-let [h (:hint opts)] (str "!" (name h)))
         join (cond (:inner opts) "!inner" (:left opts) "!left" :else "")
         prefix (when-let [a (:as opts)] (str (name a) ":"))]
     (str prefix (name relation) hint join "(" col-str ")"))))

(defn select
  "Builds a SELECT.

  `columns` is `\"*\"`, a string, or a vector of strings/keywords.
  Opts:
    :count     — :exact | :planned | :estimated (default :exact)
    :returning — boolean; when true, performs GET; when false, HEAD
                 (default false — only headers + count are returned)"
  ([req columns] (select req columns {}))
  ([req columns opts]
   (or (specs/ensure-valid specs/SelectOpts opts)
       (let [count-alg (or (:count opts) :exact)
             returning? (boolean (:returning opts))
             method (if returning? :get :head)]
         (-> req
             (http/with-method method)
             (http/with-query {"select" (select-string columns)})
             (http/with-headers {"prefer" (str "count=" (name count-alg))}))))))

;; ---------------------------------------------------------------------------
;; INSERT / UPSERT / UPDATE / DELETE
;; ---------------------------------------------------------------------------

(defn- prefer-string [parts]
  (->> parts (remove nil?) (str/join ",")))

(defn insert
  "Inserts `data` (map or vector of maps)."
  ([req data] (insert req data {}))
  ([req data opts]
   (or (specs/ensure-valid specs/MutationOpts opts)
       (let [on-conflict (:on-conflict opts)
             returning (or (:returning opts) :representation)
             count-alg (or (:count opts) :exact)
             prefer (prefer-string
                     [(str "return=" (name returning))
                      (str "count=" (name count-alg))
                      (when on-conflict (str "on_conflict=" on-conflict))
                      (when on-conflict "resolution=merge-duplicates")])]
         (cond-> (-> req
                     (http/with-method :post)
                     (http/with-headers {"prefer" prefer})
                     (http/with-body data))
           on-conflict (http/with-query {"on_conflict" on-conflict}))))))

(defn upsert
  "Upserts `data`. Defaults to `resolution=merge-duplicates`."
  ([req data] (upsert req data {}))
  ([req data opts]
   (or (specs/ensure-valid specs/MutationOpts opts)
       (let [on-conflict (:on-conflict opts)
             returning (or (:returning opts) :representation)
             count-alg (or (:count opts) :exact)
             prefer (prefer-string
                     ["resolution=merge-duplicates"
                      (str "return=" (name returning))
                      (str "count=" (name count-alg))
                      (when on-conflict (str "on_conflict=" on-conflict))])]
         (cond-> (-> req
                     (http/with-method :post)
                     (http/with-headers {"prefer" prefer})
                     (http/with-body data))
           on-conflict (http/with-query {"on_conflict" on-conflict}))))))

(defn update
  "Updates rows with `data`. Filter the rows with `eq`/`gt`/... before
  calling, or every row matching the relation will be updated."
  ([req data] (update req data {}))
  ([req data opts]
   (or (specs/ensure-valid specs/MutationOpts opts)
       (let [returning (or (:returning opts) :representation)
             count-alg (or (:count opts) :exact)
             prefer (prefer-string
                     [(str "return=" (name returning))
                      (str "count=" (name count-alg))])]
         (-> req
             (http/with-method :patch)
             (http/with-headers {"prefer" prefer})
             (http/with-body data))))))

(defn delete
  "Deletes rows. Filter first or every row in the relation is deleted."
  ([req] (delete req {}))
  ([req opts]
   (or (specs/ensure-valid specs/MutationOpts opts)
       (let [returning (or (:returning opts) :representation)
             count-alg (or (:count opts) :exact)
             prefer (prefer-string
                     [(str "return=" (name returning))
                      (str "count=" (name count-alg))])]
         (-> req
             (http/with-method :delete)
             (http/with-headers {"prefer" prefer}))))))

;; ---------------------------------------------------------------------------
;; RPC
;; ---------------------------------------------------------------------------

(defn- rpc-args->query [args]
  (->> args
       (remove (fn [[_ v]] (nil? v)))
       (map (fn [[k v]]
              [(name k)
               (if (sequential? v) (str "{" (str/join "," v) "}") (str v))]))
       (into {})))

(defn rpc
  "Calls a database function.

  Opts:
    :head  — boolean; when true, send HEAD (no body returned)
    :get   — boolean; when true, send GET (args become query params)
    :count — :exact | :planned | :estimated

  Returns a request builder. Pipe through filters or `execute` to run."
  ([client fn-name] (rpc client fn-name {} {}))
  ([client fn-name args] (rpc client fn-name args {}))
  ([client fn-name args opts]
   (or (specs/ensure-valid specs/RpcOpts opts)
       (let [head? (boolean (:head opts))
             get? (boolean (:get opts))
             count-alg (:count opts)
             method (cond head? :head get? :get :else :post)
             base (-> (http/request client)
                      (http/with-service-url :database-url (str "/rpc/" fn-name))
                      (assoc :service :postgrest)
                      (http/with-method method))]
         (cond-> base
           (= :post method)       (http/with-body args)
           (#{:get :head} method) (http/with-query (rpc-args->query args))
           count-alg              (http/with-headers
                                    {"prefer" (str "count=" (name count-alg))}))))))

;; ---------------------------------------------------------------------------
;; Aggregations (used inside `select` vectors)
;; ---------------------------------------------------------------------------

(defn- agg [fn-name column opts]
  (let [col (if (keyword? column) (name column) column)
        base (str col "." fn-name "()")]
    (if-let [as (:as opts)] (str as ":" base) base)))

(defn sum   ([c] (sum c {}))   ([c opts] (or (specs/ensure-valid specs/AggregationOpts opts) (agg "sum"   c opts))))
(defn avg   ([c] (avg c {}))   ([c opts] (or (specs/ensure-valid specs/AggregationOpts opts) (agg "avg"   c opts))))
(defn min   ([c] (min c {}))   ([c opts] (or (specs/ensure-valid specs/AggregationOpts opts) (agg "min"   c opts))))
(defn max   ([c] (max c {}))   ([c opts] (or (specs/ensure-valid specs/AggregationOpts opts) (agg "max"   c opts))))
(defn count ([c] (count c {})) ([c opts] (or (specs/ensure-valid specs/AggregationOpts opts) (agg "count" c opts))))
