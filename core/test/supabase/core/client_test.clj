(ns supabase.core.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key-123")

;; ---------------------------------------------------------------------------
;; make-client — basic creation
;; ---------------------------------------------------------------------------

(deftest make-client-basic-test
  (testing "creates a valid client with minimal args"
    (let [c (client/make-client base-url api-key)]
      (is (map? c))
      (is (not (error/anomaly? c)))
      (is (= base-url (:base-url c)))
      (is (= api-key (:api-key c)))))

  (testing "access-token defaults to api-key"
    (let [c (client/make-client base-url api-key)]
      (is (= api-key (:access-token c))))))

;; ---------------------------------------------------------------------------
;; make-client — service URLs
;; ---------------------------------------------------------------------------

(deftest make-client-service-urls-test
  (let [c (client/make-client base-url api-key)]
    (testing "derives auth URL"
      (is (= (str base-url "/auth/v1") (:auth-url c))))

    (testing "derives database URL"
      (is (= (str base-url "/rest/v1") (:database-url c))))

    (testing "derives storage URL"
      (is (= (str base-url "/storage/v1") (:storage-url c))))

    (testing "derives functions URL"
      (is (= (str base-url "/functions/v1") (:functions-url c))))

    (testing "derives realtime URL"
      (is (= (str base-url "/realtime/v1") (:realtime-url c))))))

;; ---------------------------------------------------------------------------
;; make-client — defaults
;; ---------------------------------------------------------------------------

(deftest make-client-defaults-test
  (let [c (client/make-client base-url api-key)]
    (testing "db schema defaults to public"
      (is (= "public" (get-in c [:db :schema]))))

    (testing "auth defaults are applied"
      (is (= true (get-in c [:auth :auto-refresh-token])))
      (is (= false (get-in c [:auth :debug])))
      (is (= true (get-in c [:auth :detect-session-in-url])))
      (is (= "implicit" (get-in c [:auth :flow-type])))
      (is (= true (get-in c [:auth :persist-session]))))

    (testing "storage defaults"
      (is (= false (get-in c [:storage :use-new-hostname]))))

    (testing "global headers include x-client-info"
      (is (string? (get-in c [:global :headers "x-client-info"])))
      (is (re-find #"^supabase-clj/" (get-in c [:global :headers "x-client-info"]))))))

;; ---------------------------------------------------------------------------
;; make-client — storage key
;; ---------------------------------------------------------------------------

(deftest make-client-storage-key-test
  (testing "derives default storage key from host"
    (let [c (client/make-client base-url api-key)]
      (is (= "sb-abc123-auth-token" (get-in c [:auth :storage-key])))))

  (testing "custom storage key is preserved"
    (let [c (client/make-client base-url api-key
                                :auth {:storage-key "custom-key"})]
      (is (= "custom-key" (get-in c [:auth :storage-key]))))))

;; ---------------------------------------------------------------------------
;; make-client — options
;; ---------------------------------------------------------------------------

(deftest make-client-options-test
  (testing "custom db schema"
    (let [c (client/make-client base-url api-key :db {:schema "private"})]
      (is (= "private" (get-in c [:db :schema])))))

  (testing "custom access token"
    (let [c (client/make-client base-url api-key :access-token "custom-token")]
      (is (= "custom-token" (:access-token c)))))

  (testing "custom auth flow type"
    (let [c (client/make-client base-url api-key :auth {:flow-type "pkce"})]
      (is (= "pkce" (get-in c [:auth :flow-type])))))

  (testing "custom headers merge with defaults"
    (let [c (client/make-client base-url api-key
                                :global {:headers {"x-custom" "val"}})]
      (is (= "val" (get-in c [:global :headers "x-custom"])))
      (is (some? (get-in c [:global :headers "x-client-info"]))))))

;; ---------------------------------------------------------------------------
;; make-client — storage hostname transformation
;; ---------------------------------------------------------------------------

(deftest make-client-storage-hostname-test
  (testing "without use-new-hostname, storage URL is normal"
    (let [c (client/make-client base-url api-key)]
      (is (= (str base-url "/storage/v1") (:storage-url c)))))

  (testing "with use-new-hostname, storage URL uses storage subdomain"
    (let [c (client/make-client base-url api-key
                                :storage {:use-new-hostname true})]
      (is (= "https://abc123.storage.supabase.co/storage/v1" (:storage-url c))))))

;; ---------------------------------------------------------------------------
;; transform-storage-url
;; ---------------------------------------------------------------------------

(deftest transform-storage-url-test
  (testing "transforms .supabase.co domains"
    (is (= "https://abc.storage.supabase.co/storage/v1"
           (client/transform-storage-url "https://abc.supabase.co/storage/v1"))))

  (testing "transforms .supabase.in domains"
    (is (= "https://abc.storage.supabase.in/storage/v1"
           (client/transform-storage-url "https://abc.supabase.in/storage/v1"))))

  (testing "transforms .supabase.red domains"
    (is (= "https://abc.storage.supabase.red/storage/v1"
           (client/transform-storage-url "https://abc.supabase.red/storage/v1"))))

  (testing "leaves already-transformed URLs unchanged"
    (is (= "https://abc.storage.supabase.co/storage/v1"
           (client/transform-storage-url "https://abc.storage.supabase.co/storage/v1"))))

  (testing "leaves custom domains unchanged"
    (is (= "https://custom.example.com/storage/v1"
           (client/transform-storage-url "https://custom.example.com/storage/v1"))))

  (testing "leaves localhost unchanged"
    (is (= "http://localhost:54321/storage/v1"
           (client/transform-storage-url "http://localhost:54321/storage/v1"))))

  (testing "returns nil for nil"
    (is (nil? (client/transform-storage-url nil))))

  (testing "returns nil for empty string"
    (is (nil? (client/transform-storage-url "")))))

;; ---------------------------------------------------------------------------
;; update-access-token
;; ---------------------------------------------------------------------------

(deftest update-access-token-test
  (testing "updates access token on valid client"
    (let [c (client/make-client base-url api-key)
          updated (client/update-access-token c "new-token")]
      (is (not (error/anomaly? updated)))
      (is (= "new-token" (:access-token updated)))
      (is (= api-key (:api-key updated)))))

  (testing "returns anomaly for invalid client"
    (let [result (client/update-access-token {:not "a client"} "token")]
      (is (error/anomaly? result))
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result))))))
