(ns supabase.postgrest-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.postgrest :as pg]
            [supabase.postgrest.encode :as enc]))

(def base-url "https://abc123.supabase.co")
(def test-client (client/make-client base-url "anon-key"))
(def db-url (str base-url "/rest/v1"))

(def ^:private captured (atom nil))

(defn- run-with-capture
  ([f] (run-with-capture f {:status 200 :body {:ok true} :headers {}}))
  ([f response]
   (reset! captured nil)
   (with-redefs [http/execute (fn [req]
                                (reset! captured req)
                                response)]
     [(f) @captured])))

(defn- parse-body [req] (json/read-value (:body req)))

;; ---------------------------------------------------------------------------
;; from / invalid client
;; ---------------------------------------------------------------------------

(deftest from-invalid-client-is-anomaly
  (is (error/anomaly? (pg/from {} "users"))))

(deftest from-sets-database-url
  (let [req (pg/from test-client "users")]
    (is (= :postgrest (:service req)))
    (is (= (str db-url "/users") (:url req)))))

;; ---------------------------------------------------------------------------
;; select
;; ---------------------------------------------------------------------------

(deftest select-star-uses-head-by-default
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/select "*")
                      (pg/execute)))]
    (is (= :head (:method req)))
    (is (= "*" (get-in req [:query "select"])))
    (is (= "count=exact" (get-in req [:headers "prefer"])))
    (is (= "public" (get-in req [:headers "accept-profile"])))))

(deftest select-with-returning-uses-get
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/select [:id :email] {:returning true :count :planned})
                      (pg/execute)))]
    (is (= :get (:method req)))
    (is (= "id,email" (get-in req [:query "select"])))
    (is (= "count=planned" (get-in req [:headers "prefer"])))))

;; ---------------------------------------------------------------------------
;; filter operators
;; ---------------------------------------------------------------------------

(deftest eq-filter-encodes-query
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/select "*")
                      (pg/eq "id" 1)
                      (pg/execute)))]
    (is (= "eq.1" (get-in req [:query "id"])))))

(deftest neq-lt-gt-lte-gte
  (doseq [[f expected] [[pg/neq "neq.x"] [pg/lt "lt.x"] [pg/gt "gt.x"]
                        [pg/lte "lte.x"] [pg/gte "gte.x"]]]
    (let [[_ req] (run-with-capture
                   #(-> (pg/from test-client "t")
                        (pg/select "*")
                        (f "c" "x")
                        (pg/execute)))]
      (is (= expected (get-in req [:query "c"]))))))

(deftest like-ilike
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/like "name" "%john%")
                      (pg/execute)))]
    (is (= "like.%john%" (get-in req [:query "name"])))))

(deftest like-all-of
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/like-all-of "name" ["a" "b"])
                      (pg/execute)))]
    (is (= "like(all).{a,b}" (get-in req [:query "name"])))))

(deftest is-null-and-boolean
  (let [[_ req-nil] (run-with-capture
                     #(-> (pg/from test-client "t") (pg/select "*") (pg/is "x" nil) (pg/execute)))
        [_ req-bool] (run-with-capture
                      #(-> (pg/from test-client "t") (pg/select "*") (pg/is "x" true) (pg/execute)))]
    (is (= "is.null" (get-in req-nil [:query "x"])))
    (is (= "is.true" (get-in req-bool [:query "x"])))))

(deftest within-list
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/within "status" ["a" "b" "c"])
                      (pg/execute)))]
    (is (= "in.(a,b,c)" (get-in req [:query "status"])))))

(deftest contains-array-and-map
  (let [[_ req-arr] (run-with-capture
                     #(-> (pg/from test-client "t") (pg/select "*") (pg/contains "tags" ["a" "b"]) (pg/execute)))
        [_ req-map] (run-with-capture
                     #(-> (pg/from test-client "t") (pg/select "*") (pg/contains "meta" {:k "v"}) (pg/execute)))]
    (is (= "cs.{a,b}" (get-in req-arr [:query "tags"])))
    (is (= "cs.{\"k\":\"v\"}" (get-in req-map [:query "meta"])))))

(deftest overlaps-list
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/overlaps "tags" ["a" "b"])
                      (pg/execute)))]
    (is (= "ov.{a,b}" (get-in req [:query "tags"])))))

(deftest range-operators
  (doseq [[f expected] [[pg/range-lt "sl.5"]
                        [pg/range-gt "sr.5"]
                        [pg/range-gte "nxl.5"]
                        [pg/range-lte "nxr.5"]
                        [pg/range-adjacent "adj.5"]]]
    (let [[_ req] (run-with-capture
                   #(-> (pg/from test-client "t")
                        (pg/select "*")
                        (f "c" 5)
                        (pg/execute)))]
      (is (= expected (get-in req [:query "c"]))))))

(deftest text-search-with-type-and-config
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/text-search "body" "elixir" {:type :plain :config "english"})
                      (pg/execute)))]
    (is (= "plfts(english).elixir" (get-in req [:query "body"])))))

;; ---------------------------------------------------------------------------
;; composite filters
;; ---------------------------------------------------------------------------

(deftest all-of-dsl
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/all-of [[:gt "age" 18] [:eq "status" "active"]])
                      (pg/execute)))]
    (is (= "(age.gt.18,status.eq.active)" (get-in req [:query "and"])))))

(deftest any-of-with-nested-and
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/any-of [[:gt "age" 18]
                                  [:and [[:lt "salary" 5000]
                                         [:eq "role" "junior"]]]])
                      (pg/execute)))]
    (is (= "(age.gt.18,and(salary.lt.5000,role.eq.junior))"
           (get-in req [:query "or"])))))

(deftest all-of-foreign-table
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/all-of [[:gt "age" 18]] {:foreign-table "profile"})
                      (pg/execute)))]
    (is (= "(age.gt.18)" (get-in req [:query "profile.and"])))))

(deftest negate-filter
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/negate "x" :eq 1)
                      (pg/execute)))]
    (is (= "not.eq.1" (get-in req [:query "x"])))))

(deftest match-shorthand
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/match {"a" 1 "b" 2})
                      (pg/execute)))]
    (is (= "eq.1" (get-in req [:query "a"])))
    (is (= "eq.2" (get-in req [:query "b"])))))

;; ---------------------------------------------------------------------------
;; insert / upsert / update / delete
;; ---------------------------------------------------------------------------

(deftest insert-default-prefer
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/insert {:email "a@b.com"})
                      (pg/execute)))]
    (is (= :post (:method req)))
    (is (= "public" (get-in req [:headers "content-profile"])))
    (is (= "return=representation,count=exact" (get-in req [:headers "prefer"])))
    (is (= {"email" "a@b.com"} (parse-body req)))))

(deftest insert-on-conflict
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/insert {:email "a"} {:on-conflict "email"})
                      (pg/execute)))]
    (is (= "email" (get-in req [:query "on_conflict"])))
    (is (re-find #"on_conflict=email" (get-in req [:headers "prefer"])))
    (is (re-find #"resolution=merge-duplicates" (get-in req [:headers "prefer"])))))

(deftest upsert-prefer
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/upsert {:email "a"} {:on-conflict "email"})
                      (pg/execute)))]
    (is (= :post (:method req)))
    (is (re-find #"resolution=merge-duplicates" (get-in req [:headers "prefer"])))))

(deftest update-uses-patch
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/eq "id" 1)
                      (pg/update {:name "Jane"})
                      (pg/execute)))]
    (is (= :patch (:method req)))
    (is (= "eq.1" (get-in req [:query "id"])))
    (is (= {"name" "Jane"} (parse-body req)))))

(deftest delete-uses-delete
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "users")
                      (pg/eq "id" 1)
                      (pg/delete)
                      (pg/execute)))]
    (is (= :delete (:method req)))
    (is (= "eq.1" (get-in req [:query "id"])))))

;; ---------------------------------------------------------------------------
;; rpc
;; ---------------------------------------------------------------------------

(deftest rpc-default-is-post
  (let [[_ req] (run-with-capture
                 #(-> (pg/rpc test-client "ping" {:n 1}) (pg/execute)))]
    (is (= :post (:method req)))
    (is (= (str db-url "/rpc/ping") (:url req)))
    (is (= {"n" 1} (parse-body req)))))

(deftest rpc-get-puts-args-in-query
  (let [[_ req] (run-with-capture
                 #(-> (pg/rpc test-client "f" {:a 1 :b [2 3]} {:get true})
                      (pg/execute)))]
    (is (= :get (:method req)))
    (is (= "1" (get-in req [:query "a"])))
    (is (= "{2,3}" (get-in req [:query "b"])))))

(deftest rpc-head-no-body
  (let [[_ req] (run-with-capture
                 #(-> (pg/rpc test-client "f" {} {:head true})
                      (pg/execute)))]
    (is (= :head (:method req)))))

(deftest rpc-count-header
  (let [[_ req] (run-with-capture
                 #(-> (pg/rpc test-client "f" {} {:count :exact})
                      (pg/execute)))]
    (is (= "count=exact" (get-in req [:headers "prefer"])))))

;; ---------------------------------------------------------------------------
;; transforms
;; ---------------------------------------------------------------------------

(deftest order-default-is-desc
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/order "created_at")
                      (pg/execute)))]
    (is (= "created_at.desc.nullslast" (get-in req [:query "order"])))))

(deftest order-stacks
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/order "id" {:asc true})
                      (pg/order "name" {:asc false :null-first true})
                      (pg/execute)))]
    (is (= "id.asc.nullslast,name.desc.nullsfirst"
           (get-in req [:query "order"])))))

(deftest limit-and-range
  (let [[_ req-limit] (run-with-capture
                       #(-> (pg/from test-client "t") (pg/select "*") (pg/limit 10) (pg/execute)))
        [_ req-range] (run-with-capture
                       #(-> (pg/from test-client "t") (pg/select "*") (pg/range 0 9) (pg/execute)))]
    (is (= "10" (get-in req-limit [:query "limit"])))
    (is (= "0" (get-in req-range [:query "offset"])))
    (is (= "10" (get-in req-range [:query "limit"])))))

(deftest csv-accept-header
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t") (pg/select "*") (pg/csv) (pg/execute)))]
    (is (= "text/csv" (get-in req [:headers "accept"])))))

(deftest single-accept
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t") (pg/select "*") (pg/single) (pg/execute)))]
    (is (= "application/vnd.pgrst.object+json"
           (get-in req [:headers "accept"])))))

(deftest explain-header-formed
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/select "*")
                      (pg/explain {:analyze true :format :json})
                      (pg/execute)))
        accept (get-in req [:headers "accept"])]
    (is (re-find #"application/vnd\.pgrst\.plan\+json;" accept))
    (is (re-find #"options:analyze" accept))))

(deftest rollback-merges-prefer
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/insert {:a 1})
                      (pg/rollback)
                      (pg/execute)))]
    (is (re-find #"tx=rollback" (get-in req [:headers "prefer"])))
    (is (re-find #"return=representation" (get-in req [:headers "prefer"])))))

(deftest returning-sets-select-and-prefer
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/insert {:a 1})
                      (pg/returning [:id :name])
                      (pg/execute)))]
    (is (= "id,name" (get-in req [:query "select"])))
    (is (re-find #"return=representation" (get-in req [:headers "prefer"])))))

;; ---------------------------------------------------------------------------
;; schema + media type
;; ---------------------------------------------------------------------------

(deftest schema-overrides-profile-header
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/schema "private")
                      (pg/select "*")
                      (pg/execute)))]
    (is (= "private" (get-in req [:headers "accept-profile"])))))

(deftest mutation-uses-content-profile
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/schema "private")
                      (pg/insert {:a 1})
                      (pg/execute)))]
    (is (= "private" (get-in req [:headers "content-profile"])))))

(deftest with-custom-media-type-csv
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "t")
                      (pg/with-custom-media-type :csv)
                      (pg/select "*")
                      (pg/execute)))]
    (is (= "text/csv" (get-in req [:headers "accept"])))))

;; ---------------------------------------------------------------------------
;; aggregations
;; ---------------------------------------------------------------------------

(deftest aggregations-build-select-strings
  (is (= "amount.sum()" (pg/sum "amount")))
  (is (= "avg_amount:amount.avg()" (pg/avg "amount" {:as "avg_amount"})))
  (is (= "amount.min()" (pg/min "amount")))
  (is (= "amount.max()" (pg/max "amount")))
  (is (= "amount.count()" (pg/count "amount"))))

;; ---------------------------------------------------------------------------
;; error enrichment
;; ---------------------------------------------------------------------------

(deftest error-body-fields-enrich-anomaly
  (let [[result _] (run-with-capture
                    #(-> (pg/from test-client "t") (pg/select "*") (pg/execute))
                    (error/from-http-response 400
                                              {:message "boom" :hint "try X"
                                               :code "PGRST123" :details "row 5"}
                                              :postgrest))]
    (is (error/anomaly? result))
    (is (= :database-error (:supabase/code result)))
    (is (= "boom" (:cognitect.anomalies/message result)))
    (is (= "boom" (:postgrest/message result)))
    (is (= "try X" (:postgrest/hint result)))
    (is (= "PGRST123" (:postgrest/code result)))
    (is (= "row 5" (:postgrest/details result)))))

(deftest error-known-code-refines-category
  (testing "PGRST116 -> not-found"
    (let [[result _] (run-with-capture
                      #(-> (pg/from test-client "t") (pg/single) (pg/execute))
                      (error/from-http-response 406
                                                {:message "no rows" :code "PGRST116"}
                                                :postgrest))]
      (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category result)))
      (is (= "PGRST116" (:postgrest/code result)))))
  (testing "42501 -> forbidden"
    (let [[result _] (run-with-capture
                      #(-> (pg/from test-client "t") (pg/select "*") (pg/execute))
                      (error/from-http-response 400
                                                {:message "denied" :code "42501"}
                                                :postgrest))]
      (is (= :cognitect.anomalies/forbidden (:cognitect.anomalies/category result))))))

;; ---------------------------------------------------------------------------
;; embed (resource embedding / join hints)
;; ---------------------------------------------------------------------------

(deftest embed-builds-select-strings
  (is (= "messages(id,content)" (pg/embed "messages" [:id :content])))
  (is (= "messages!inner(id,content)" (pg/embed "messages" [:id :content] {:inner true})))
  (is (= "messages!left(*)" (pg/embed "messages" "*" {:left true})))
  (is (= "author:users!author_id(name)"
         (pg/embed "users" [:name] {:as "author" :hint "author_id"}))))

(deftest embed-inside-select
  (let [[_ req] (run-with-capture
                 #(-> (pg/from test-client "threads")
                      (pg/select ["id" (pg/embed "messages" [:body] {:inner true})]
                                 {:returning true})
                      (pg/execute)))]
    (is (= "id,messages!inner(body)" (get-in req [:query "select"])))))

;; ---------------------------------------------------------------------------
;; encode (type coercion helpers)
;; ---------------------------------------------------------------------------

(deftest encode-helpers
  (is (= "{a,b,c}" (enc/pg-array ["a" "b" "c"])))
  (is (= "{1,2,NULL}" (enc/pg-array [1 2 nil])))
  (is (= "{\"a,b\",c}" (enc/pg-array ["a,b" "c"]))
      "elements with commas are quoted")
  (is (= "[1,10)" (enc/pg-range 1 10)))
  (is (= "[1,10]" (enc/pg-range 1 10 "[]")))
  (is (= "(,5)" (enc/pg-range nil 5 "()")) "unbounded low")
  (is (= "true" (enc/pg-bool true)))
  (is (= "false" (enc/pg-bool false)))
  (is (= "2026-06-20T00:00:00Z"
         (enc/->iso (java.time.Instant/parse "2026-06-20T00:00:00Z")))))

(deftest encode-range-with-instants
  (let [lo (java.time.Instant/parse "2026-01-01T00:00:00Z")
        hi (java.time.Instant/parse "2026-02-01T00:00:00Z")]
    (is (= "[2026-01-01T00:00:00Z,2026-02-01T00:00:00Z)" (enc/pg-range lo hi)))))
