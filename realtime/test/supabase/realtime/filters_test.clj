(ns supabase.realtime.filters-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.realtime.filters :as f]))

(deftest single-conditions
  (is (= "id=eq.1"        (f/build (f/eq "id" 1))))
  (is (= "id=neq.1"       (f/build (f/neq "id" 1))))
  (is (= "age=lt.30"      (f/build (f/lt "age" 30))))
  (is (= "age=lte.30"     (f/build (f/lte "age" 30))))
  (is (= "amount=gt.100"  (f/build (f/gt "amount" 100))))
  (is (= "amount=gte.100" (f/build (f/gte "amount" 100))))
  (is (= "title=like.%foo%"  (f/build (f/like "title" "%foo%"))))
  (is (= "title=ilike.%foo%" (f/build (f/ilike "title" "%foo%"))))
  (is (= "code=match.^a"  (f/build (f/match "code" "^a"))))
  (is (= "code=imatch.^a" (f/build (f/imatch "code" "^a"))))
  (is (= "id=isdistinct.1" (f/build (f/isdistinct "id" 1)))))

(deftest threading-composes-and
  (is (= "amount=gt.100,status=eq.open"
         (f/build (-> (f/gt "amount" 100)
                      (f/eq "status" "open"))))))

(deftest not-negates-any-operator
  (is (= "status=not.in.(draft,archived)"
         (f/build (f/not "status" :in ["draft" "archived"]))))
  (is (= "deleted_at=not.is.null"
         (f/build (f/not "deleted_at" :is nil))))
  (is (= "active=eq.true,age=not.gte.18"
         (f/build (-> (f/eq "active" true)
                      (f/not "age" :gte 18)))))
  (testing "unknown operator throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (f/not "x" :bogus 1)))))

(deftest in-values
  (testing "joins and dedupes"
    (is (= "status=in.(active,pending)"
           (f/build (f/in "status" ["active" "pending" "active"])))))
  (testing "single value"
    (is (= "id=in.(1)" (f/build (f/in "id" [1])))))
  (testing "empty throws"
    (is (thrown? clojure.lang.ExceptionInfo (f/in "id" []))))
  (testing "nil element throws (IN (null) never matches)"
    (is (thrown? clojure.lang.ExceptionInfo (f/in "id" [1 nil])))))

(deftest is-values
  (is (= "deleted_at=is.null" (f/build (f/is "deleted_at" nil))))
  (is (= "active=is.true"    (f/build (f/is "active" true))))
  (is (= "active=is.false"   (f/build (f/is "active" :false))))
  (is (= "col=is.unknown"    (f/build (f/is "col" "unknown")))))

(deftest value-quoting
  (testing "comma triggers PostgREST-style quoting"
    (is (= "name=eq.\"a,b\"" (f/build (f/eq "name" "a,b")))))
  (testing "parens and quotes are quoted and escaped"
    (is (= "v=eq.\"a(\\\"b\\\")\"" (f/build (f/eq "v" "a(\"b\")")))))
  (testing "backslash is escaped"
    (is (= "v=eq.\"a\\\\b\"" (f/build (f/eq "v" "a\\b")))))
  (testing "surrounding whitespace triggers quoting"
    (is (= "v=eq.\" pad \"" (f/build (f/eq "v" " pad ")))))
  (testing "plain values pass through verbatim"
    (is (= "v=eq.a-b_c.d" (f/build (f/eq "v" "a-b_c.d")))))
  (testing "quoting applies inside in-lists"
    (is (= "s=in.(\"a,b\",c)" (f/build (f/in "s" ["a,b" "c"])))))
  (testing "nil scalar renders as null"
    (is (= "v=eq.null" (f/build (f/eq "v" nil))))))

(deftest empty-builder
  (is (= "" (f/build []))))
