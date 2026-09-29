(ns supabase.core.client-test
  (:require [cljs.test :refer [deftest is testing]]
            [supabase.core.client :as client]))

(deftest make-client-test
  (testing "derives service URLs and defaults"
    (let [c (client/make-client "https://abc.supabase.co" "key")]
      (is (= "https://abc.supabase.co/auth/v1" (:auth-url c)))
      (is (= "https://abc.supabase.co/rest/v1" (:database-url c)))
      (is (= "https://abc.supabase.co/storage/v1" (:storage-url c)))
      (is (= "key" (:access-token c)))
      (is (= "public" (get-in c [:db :schema])))
      (is (= "sb-abc-auth-token" (get-in c [:auth :storage-key])))))
  (testing "invalid input returns an anomaly"
    (is (= :cognitect.anomalies/incorrect
           (:cognitect.anomalies/category (client/make-client nil nil))))))

(deftest transform-storage-url-test
  (testing "official domain is rewritten"
    (is (= "https://abc.storage.supabase.co/storage/v1"
           (client/transform-storage-url "https://abc.supabase.co/storage/v1"))))
  (testing "custom domain untouched"
    (is (= "https://custom.example.com/storage/v1"
           (client/transform-storage-url "https://custom.example.com/storage/v1"))))
  (testing "already-storage subdomain untouched"
    (is (= "https://abc.storage.supabase.co/storage/v1"
           (client/transform-storage-url "https://abc.storage.supabase.co/storage/v1"))))
  (testing "port preserved"
    (is (= "https://abc.storage.supabase.co:8443/storage/v1"
           (client/transform-storage-url "https://abc.supabase.co:8443/storage/v1")))))

(deftest update-access-token-test
  (let [c (client/make-client "https://abc.supabase.co" "key")]
    (is (= "jwt" (:access-token (client/update-access-token c "jwt"))))))

(deftest format-client-info-test
  (is (= "a/1.0; b/2.0" (client/format-client-info {"b" "2.0" "a" "1.0"}))))
