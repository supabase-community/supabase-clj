(ns supabase.core.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.core.retry :as retry])
  (:import (java.net ConnectException SocketException)
           (java.net.http HttpTimeoutException)
           (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter)))

;; ---------------------------------------------------------------------------
;; transient-status?
;; ---------------------------------------------------------------------------

(deftest transient-status?-test
  (testing "returns true for retryable statuses"
    (doseq [status [429 502 503 504]]
      (is (true? (retry/transient-status? status))
          (str "status " status " should be transient"))))

  (testing "returns false for non-retryable statuses"
    (doseq [status [400 401 404 500 501]]
      (is (false? (retry/transient-status? status))
          (str "status " status " should not be transient")))))

;; ---------------------------------------------------------------------------
;; transient-exception?
;; ---------------------------------------------------------------------------

(deftest transient-exception?-test
  (testing "returns true for transient transport exceptions"
    (is (true? (retry/transient-exception? (ConnectException. "Connection refused"))))
    (is (true? (retry/transient-exception? (HttpTimeoutException. "request timed out"))))
    (is (true? (retry/transient-exception? (SocketException. "Connection reset by peer")))))

  (testing "returns true when a transient cause is wrapped"
    (let [ex (ex-info "request failed" {:status 503} (ConnectException. "Connection refused"))]
      (is (true? (retry/transient-exception? ex))))
    (let [ex (Exception. "outer" (SocketException. "Broken pipe"))]
      (is (true? (retry/transient-exception? ex)))))

  (testing "returns false for non-transient exceptions"
    (is (false? (retry/transient-exception? (ex-info "boom" {:some :data}))))
    (is (false? (retry/transient-exception? (SocketException. "Socket closed"))))))

;; ---------------------------------------------------------------------------
;; retry-after-ms
;; ---------------------------------------------------------------------------

(deftest retry-after-ms-test
  (testing "parses delay-seconds"
    (is (= 3000 (retry/retry-after-ms {"retry-after" "3"})))
    (is (= 0 (retry/retry-after-ms {"retry-after" "0"}))))

  (testing "parses an HTTP-date in the future"
    (let [date (.format DateTimeFormatter/RFC_1123_DATE_TIME
                        (.plusSeconds (ZonedDateTime/now) 60))
          ms   (retry/retry-after-ms {"retry-after" date})]
      (is (some? ms))
      (is (pos? ms))
      (is (<= ms 60000))))

  (testing "returns nil when the header is absent"
    (is (nil? (retry/retry-after-ms {})))
    (is (nil? (retry/retry-after-ms {"content-type" "application/json"}))))

  (testing "returns nil for unparseable values"
    (is (nil? (retry/retry-after-ms {"retry-after" "soon"}))))

  (testing "clamps negative delay-seconds to 0"
    (is (= 0 (retry/retry-after-ms {"retry-after" "-5"})))))

;; ---------------------------------------------------------------------------
;; backoff-ms
;; ---------------------------------------------------------------------------

(deftest backoff-ms-test
  (testing "attempt 1 stays within [0, initial-delay-ms]"
    (dotimes [_ 100]
      (let [ms (retry/backoff-ms 1 {:initial-delay-ms 200 :max-delay-ms 5000 :multiplier 2.0})]
        (is (<= 0 ms 200)))))

  (testing "cap grows with the attempt"
    (dotimes [_ 100]
      (let [ms (retry/backoff-ms 3 {:initial-delay-ms 200 :max-delay-ms 5000 :multiplier 2.0})]
        (is (<= 0 ms 800)))))

  (testing "cap never exceeds max-delay-ms for large attempts"
    (dotimes [_ 100]
      (let [ms (retry/backoff-ms 50 {:initial-delay-ms 200 :max-delay-ms 5000 :multiplier 2.0})]
        (is (<= 0 ms 5000)))))

  (testing "missing option keys fall back to defaults"
    (dotimes [_ 100]
      (let [ms (retry/backoff-ms 1)]
        (is (<= 0 ms 200))))))

;; ---------------------------------------------------------------------------
;; next-delay-ms
;; ---------------------------------------------------------------------------

(deftest next-delay-ms-test
  (testing "retry-after wins over computed backoff"
    (is (= 3000 (retry/next-delay-ms 1 {"retry-after" "3"}))))

  (testing "falls back to backoff when no retry-after is present"
    (dotimes [_ 100]
      (let [ms (retry/next-delay-ms 1 {} {:initial-delay-ms 200 :max-delay-ms 5000 :multiplier 2.0})]
        (is (<= 0 ms 200))))))

;; ---------------------------------------------------------------------------
;; merge-opts
;; ---------------------------------------------------------------------------

(deftest merge-opts-test
  (testing "nil client and nil request disables retries"
    (is (nil? (retry/merge-opts nil nil))))

  (testing "map client with nil request merges over defaults"
    (let [opts (retry/merge-opts {:max-attempts 5} nil)]
      (is (= 5 (:max-attempts opts)))
      (is (= 200 (:initial-delay-ms opts)))
      (is (= 5000 (:max-delay-ms opts)))
      (is (= 2.0 (:multiplier opts)))))

  (testing "false request disables retries"
    (is (nil? (retry/merge-opts {:max-attempts 5} false))))

  (testing "integer request overrides max-attempts"
    (let [opts (retry/merge-opts {:max-attempts 2} 5)]
      (is (= 5 (:max-attempts opts)))))

  (testing "true client resolves to defaults"
    (is (= retry/default-opts (retry/merge-opts true nil))))

  (testing "integer client resolves to defaults with that max-attempts"
    (let [opts (retry/merge-opts 7 nil)]
      (is (= 7 (:max-attempts opts)))
      (is (= 200 (:initial-delay-ms opts)))))

  (testing "request map merges over client map"
    (let [opts (retry/merge-opts {:max-attempts 5 :multiplier 3.0} {:max-delay-ms 1000})]
      (is (= 5 (:max-attempts opts)))
      (is (= 3.0 (:multiplier opts)))
      (is (= 1000 (:max-delay-ms opts)))
      (is (= 200 (:initial-delay-ms opts))))))
