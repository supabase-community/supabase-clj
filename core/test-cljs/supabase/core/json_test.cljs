(ns supabase.core.json-test
  (:require [cljs.test :refer [deftest is testing]]
            [supabase.core.json :as json]))

(deftest write-string-test
  (testing "encodes maps, vectors, and scalars"
    (is (= "{\"a\":1,\"b\":[true,null]}"
           (json/write-string {:a 1 :b [true nil]})))
    (is (= "\"x\"" (json/write-string "x")))))

(deftest read-string-test
  (testing "decodes with keywordized keys"
    (is (= {:a 1 :b {:c true}}
           (json/read-string "{\"a\":1,\"b\":{\"c\":true}}")))))

(deftest read-string-safe-test
  (testing "returns parsed value on valid JSON"
    (is (= {:a 1} (json/read-string-safe "{\"a\":1}"))))
  (testing "returns raw input on malformed JSON"
    (is (= "{nope" (json/read-string-safe "{nope"))))
  (testing "passes through non-strings"
    (is (= 42 (json/read-string-safe 42)))))

(deftest round-trip-test
  (is (= {:a [1 2] :b "ç"} (json/read-string (json/write-string {:a [1 2] :b "ç"})))))
