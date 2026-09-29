(ns supabase.core.retry-test
  (:require [cljs.test :refer [deftest is testing]]
            [supabase.core.retry :as retry]))

(deftest transient-status?-test
  (doseq [s [429 502 503 504]]
    (is (true? (retry/transient-status? s)) (str s)))
  (doseq [s [400 404 500 501 nil]]
    (is (false? (retry/transient-status? s)) (str s))))

(deftest transient-exception?-test
  (testing "fetch network failure (TypeError) is transient"
    (is (true? (retry/transient-exception? (js/TypeError. "fetch failed")))))
  (testing "abort (timeout) is transient"
    (let [e (js/Error. "aborted")]
      (set! (.-name e) "AbortError")
      (is (true? (retry/transient-exception? e)))))
  (testing "generic errors are not transient"
    (is (false? (retry/transient-exception? (js/Error. "boom"))))))

(deftest retry-after-ms-test
  (testing "delay-seconds"
    (is (= 3000 (retry/retry-after-ms {"retry-after" "3"}))))
  (testing "absent header"
    (is (nil? (retry/retry-after-ms {}))))
  (testing "unparseable value"
    (is (nil? (retry/retry-after-ms {"retry-after" "soon"}))))
  (testing "negative clamps to zero"
    (is (= 0 (retry/retry-after-ms {"retry-after" "-5"})))))

(deftest backoff-ms-test
  (testing "within [0, cap]"
    (dotimes [_ 50]
      (let [d (retry/backoff-ms 1)]
        (is (<= 0 d 200)))))
  (testing "capped at max-delay-ms"
    (dotimes [_ 50]
      (let [d (retry/backoff-ms 10)]
        (is (<= 0 d 5000))))))

(deftest next-delay-ms-test
  (testing "retry-after wins over backoff"
    (is (= 3000 (retry/next-delay-ms 2 {"retry-after" "3"})))))

(deftest merge-opts-test
  (is (nil? (retry/merge-opts nil nil)))
  (is (nil? (retry/merge-opts {:max-attempts 5} false)))
  (is (= retry/default-opts (retry/merge-opts true nil)))
  (is (= 5 (:max-attempts (retry/merge-opts true 5))))
  (is (= 100 (:initial-delay-ms (retry/merge-opts true {:initial-delay-ms 100})))))
