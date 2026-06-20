# io.github.supabase-community/postgrest

PostgREST integration for Supabase. Idiomatic thread-first query builder
over the REST API — SELECT / INSERT / UPSERT / UPDATE / DELETE, the full
operator set, filter composition, ordering, ranges, full-text search,
aggregations, RPC, EXPLAIN.

## Installation

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version "1.0.0"}
  io.github.supabase-community/postgrest {:mvn/version "1.1.0"}}} ;; x-release-please-version
```

## Quick Start

```clojure
(require '[supabase.core.client :as client]
         '[supabase.postgrest :as pg])

(def c (client/make-client "https://abc.supabase.co" "anon-key"))

;; SELECT
(-> (pg/from c "users")
    (pg/select "*")
    (pg/eq "active" true)
    (pg/order "created_at")
    (pg/limit 10)
    (pg/execute))
;; => {:status 200 :body [...] :headers {...}}  OR an anomaly

;; INSERT
(-> (pg/from c "users")
    (pg/insert {:email "a@b.com"})
    (pg/execute))

;; UPDATE (filter first)
(-> (pg/from c "users")
    (pg/eq "id" 42)
    (pg/update {:name "Jane"})
    (pg/execute))

;; DELETE
(-> (pg/from c "users")
    (pg/eq "id" 42)
    (pg/delete)
    (pg/execute))

;; UPSERT with conflict target
(-> (pg/from c "users")
    (pg/upsert {:email "a@b.com" :name "A"} {:on-conflict "email"})
    (pg/execute))

;; RPC (POST by default; pass {:get true} or {:head true} to switch)
(-> (pg/rpc c "search_users" {:q "alice"})
    (pg/execute))
```

## Filters

```clojure
(pg/eq req "col" 1)        (pg/neq req "col" 1)
(pg/gt req "col" 1)        (pg/gte req "col" 1)
(pg/lt req "col" 1)        (pg/lte req "col" 1)
(pg/like req "col" "%a%")  (pg/ilike req "col" "%a%")
(pg/like-all-of req "col" ["a" "b"])
(pg/is req "deleted_at" nil)
(pg/within req "status" ["a" "b"])           ;; PostgREST `IN`
(pg/contains req "tags" ["urgent"])           ;; jsonb / array
(pg/contained-by req "tags" ["a" "b" "c"])
(pg/overlaps req "tags" ["a" "b"])
(pg/range-lt req "age" 30)
(pg/range-adjacent req "scheduled" "...")
(pg/text-search req "body" "elixir" {:type :plain :config "english"})
(pg/match req {"a" 1 "b" 2})                  ;; multi-eq
(pg/negate req "x" :eq 1)                     ;; NOT eq

;; Composite (DSL accepts nested :and / :or / :not)
(pg/all-of req [[:gt "age" 18] [:eq "status" "active"]])
(pg/any-of req [[:gt "age" 18]
                [:and [[:lt "salary" 5000]
                       [:eq "role" "junior"]]]])
```

## Transforms

```clojure
(pg/order req "created_at")               ;; default desc, nullslast
(pg/order req "name" {:asc true :null-first true})
(pg/limit req 10)
(pg/range req 0 9)                        ;; 0-based inclusive
(pg/single req)                           ;; expect exactly 1 row
(pg/maybe-single req)                     ;; tolerate 0 or 1
(pg/csv req)                              ;; accept: text/csv
(pg/geojson req)
(pg/explain req {:analyze true :format :json})
(pg/rollback req)                         ;; tx=rollback prefer
(pg/returning req [:id :name])            ;; for INSERT/UPDATE/etc.
```

## Resource embedding (joins)

Embed related tables inside a `select` with `pg/embed`. Add `:inner` to drop
parent rows with no match, or `:hint` to disambiguate an ambiguous foreign key.

```clojure
(-> (pg/from c "threads")
    (pg/select ["id" "subject"
                (pg/embed "messages" [:id :body] {:inner true})
                (pg/embed "users" [:name] {:as "author" :hint "author_id"})]
               {:returning true})
    (pg/execute))
;; select=id,subject,messages!inner(id,body),author:users!author_id(name)
```

## Encoding complex column types

`supabase.postgrest.encode` renders PostgreSQL literals for arrays, ranges,
timestamps, and booleans so you can pass them as filter values:

```clojure
(require '[supabase.postgrest.encode :as enc])

(enc/pg-array ["vip" "weekend"])   ;; => "{vip,weekend}"
(enc/pg-range 1 10)                 ;; => "[1,10)"
(enc/pg-range start end "[]")       ;; instants coerced to ISO-8601
(enc/pg-bool true)                  ;; => "true"

(-> (pg/from c "reservations")
    (pg/eq "during" (enc/pg-range start end))
    (pg/execute))
```

## Aggregations

```clojure
(-> (pg/from c "orders")
    (pg/select [(pg/sum "amount" {:as "total"})
                (pg/avg "amount" {:as "average"})
                (pg/count "*")])
    (pg/execute))
```

## Schema override + custom media types

```clojure
(-> (pg/from c "secrets")
    (pg/schema "private")                 ;; per-call schema profile
    (pg/select "*")
    (pg/execute))

(-> (pg/from c "users")
    (pg/with-custom-media-type :pgrst-array)
    (pg/select "*")
    (pg/execute))
```

## Async & cancellation

`execute-async` returns a cancellable `CompletableFuture`:

```clojure
(def fut (-> (pg/from c "big_table") (pg/select "*") (pg/execute-async)))

@fut                  ;; blocks for the enriched result / anomaly

(future-cancel fut)   ;; aborts the in-flight HTTP request
```

Wire it to a core.async channel or any external signal — e.g.
`(go (when (<! cancel-ch) (future-cancel fut)))`.

## Error handling

Anomaly maps follow `cognitect/anomalies` plus PostgREST decoration:

```clojure
(def result (-> (pg/from c "users") (pg/select "*") (pg/execute)))

(supabase.core.error/anomaly? result)
;; => true for any non-2xx

(:postgrest/message result) ;; PostgREST error message
(:postgrest/hint result)    ;; PostgREST error hint, when present
(:postgrest/code result)    ;; PostgREST / SQLSTATE error code
(:postgrest/details result) ;; PostgREST error details
```

Well-known codes refine `:cognitect.anomalies/category`: `PGRST116` →
`:not-found`, `PGRST301` / `42501` → `:forbidden`.

## License

MIT
