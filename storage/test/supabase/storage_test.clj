(ns supabase.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]
            [supabase.storage :as storage]))

(def base-url "https://abc123.supabase.co")
(def api-key "test-api-key")
(def test-client (client/make-client base-url api-key))
(def storage-url (str base-url "/storage/v1"))

(def ^:private captured (atom nil))

(defn- capture-execute [req]
  (reset! captured req)
  {:status 200 :body {:ok true} :headers {}})

(defn- run-with-capture
  ([f] (run-with-capture f {:status 200 :body {:ok true} :headers {}}))
  ([f response]
   (reset! captured nil)
   (with-redefs [http/execute (fn [req]
                                (reset! captured req)
                                response)]
     [(f) @captured])))

(defn- parse-body [req] (json/read-value (:body req)))

(defn- valid-storage []
  (storage/from test-client "avatars"))

;; ---------------------------------------------------------------------------
;; Bucket ops — invalid client / input
;; ---------------------------------------------------------------------------

(deftest list-buckets-invalid-client-test
  (is (error/anomaly? (storage/list-buckets {}))))

(deftest get-bucket-invalid-client-test
  (is (error/anomaly? (storage/get-bucket {} "id"))))

(deftest create-bucket-invalid-client-test
  (is (error/anomaly? (storage/create-bucket {} "id" {}))))

(deftest create-bucket-invalid-attrs-test
  (testing "rejects unknown keys"
    (is (error/anomaly? (storage/create-bucket test-client "id" {:bogus 1})))))

(deftest update-bucket-invalid-attrs-test
  (is (error/anomaly? (storage/update-bucket test-client "id" {:bogus 1}))))

(deftest empty-bucket-invalid-client-test
  (is (error/anomaly? (storage/empty-bucket {} "id"))))

(deftest delete-bucket-invalid-client-test
  (is (error/anomaly? (storage/delete-bucket {} "id"))))

;; ---------------------------------------------------------------------------
;; Bucket ops — request shape
;; ---------------------------------------------------------------------------

(deftest list-buckets-request-test
  (let [[result req] (run-with-capture #(storage/list-buckets test-client))]
    (is (= 200 (:status result)))
    (is (= :get (:method req)))
    (is (= (str storage-url "/bucket") (:url req)))))

(deftest get-bucket-request-test
  (let [[_ req] (run-with-capture #(storage/get-bucket test-client "avatars"))]
    (is (= :get (:method req)))
    (is (= (str storage-url "/bucket/avatars") (:url req)))))

(deftest create-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(storage/create-bucket test-client "avatars"
                                         {:public true
                                          :file-size-limit 1024
                                          :allowed-mime-types ["image/png"]
                                          :type :standard}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/bucket") (:url req)))
    (is (= "avatars" (get body "id")))
    (is (= "avatars" (get body "name")))
    (is (= true (get body "public")))
    (is (= 1024 (get body "file_size_limit")))
    (is (= ["image/png"] (get body "allowed_mime_types")))
    (is (= "STANDARD" (get body "type")))))

(deftest create-bucket-defaults-test
  (let [[_ req] (run-with-capture #(storage/create-bucket test-client "logs"))
        body (parse-body req)]
    (is (= "logs" (get body "id")))
    (is (= "logs" (get body "name")))
    (is (not (contains? body "public")))))

(deftest update-bucket-request-test
  (let [[_ req] (run-with-capture
                 #(storage/update-bucket test-client "avatars" {:public false}))
        body (parse-body req)]
    (is (= :put (:method req)))
    (is (= (str storage-url "/bucket/avatars") (:url req)))
    (is (= false (get body "public")))
    (is (not (contains? body "id")))))

(deftest empty-bucket-request-test
  (let [[_ req] (run-with-capture #(storage/empty-bucket test-client "avatars"))]
    (is (= :post (:method req)))
    (is (= (str storage-url "/bucket/avatars/empty") (:url req)))))

(deftest delete-bucket-request-test
  (let [[_ req] (run-with-capture #(storage/delete-bucket test-client "avatars"))]
    (is (= :delete (:method req)))
    (is (= (str storage-url "/bucket/avatars") (:url req)))))

;; ---------------------------------------------------------------------------
;; from / clean-path / get-public-url — pure
;; ---------------------------------------------------------------------------

(deftest from-test
  (let [s (storage/from test-client "avatars")]
    (is (= "avatars" (:bucket-id s)))
    (is (= test-client (:client s)))))

(deftest from-invalid-client-test
  (is (error/anomaly? (storage/from {} "avatars"))))

(deftest clean-path-test
  (is (= "a/b/c" (#'supabase.storage/clean-path "/a/b/c/")))
  (is (= "a/b/c" (#'supabase.storage/clean-path "a//b///c")))
  (is (= "a/b" (#'supabase.storage/clean-path "//a/b//"))))

(deftest get-public-url-test
  (let [s (valid-storage)]
    (is (= (str storage-url "/object/public/avatars/profile.png")
           (storage/get-public-url s "profile.png")))
    (is (= (str storage-url "/object/public/avatars/folder/image.png")
           (storage/get-public-url s "/folder/image.png")))))

(deftest get-public-url-with-download-test
  (let [s (valid-storage)
        url (storage/get-public-url s "a.png" {:download true})]
    (is (.contains url "?download="))))

(deftest get-public-url-with-named-download-test
  (let [s (valid-storage)
        url (storage/get-public-url s "a.png" {:download "renamed.png"})]
    (is (.contains url "?download=renamed.png"))))

(deftest get-public-url-no-http-test
  (let [s (valid-storage)
        [_ req] (run-with-capture #(storage/get-public-url s "a.png"))]
    (is (nil? req))))

;; ---------------------------------------------------------------------------
;; File ops — invalid input
;; ---------------------------------------------------------------------------

(deftest list-files-invalid-storage-test
  (is (error/anomaly? (storage/list-files {} nil {}))))

(deftest list-files-invalid-opts-test
  (is (error/anomaly? (storage/list-files (valid-storage) nil {:bogus 1}))))

(deftest move-invalid-opts-test
  (is (error/anomaly? (storage/move (valid-storage) {:to "x"}))))

(deftest copy-invalid-opts-test
  (is (error/anomaly? (storage/copy (valid-storage) {:from "x"}))))

(deftest create-signed-url-missing-expires-test
  (is (error/anomaly? (storage/create-signed-url (valid-storage) "p" {}))))

(deftest upload-invalid-body-test
  (is (error/anomaly? (storage/upload (valid-storage) "p" 12345))))

;; ---------------------------------------------------------------------------
;; File ops — request shape
;; ---------------------------------------------------------------------------

(deftest list-files-request-test
  (let [s (valid-storage)
        [_ req] (run-with-capture
                 #(storage/list-files s "folder"
                                      {:limit 10 :offset 5
                                       :sort-by {:column "name" :order "asc"}
                                       :search "foo"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/object/list/avatars") (:url req)))
    (is (= "folder" (get body "prefix")))
    (is (= 10 (get body "limit")))
    (is (= 5 (get body "offset")))
    (is (= "foo" (get body "search")))
    (is (= {"column" "name" "order" "asc"} (get body "sortBy")))))

(deftest list-files-default-prefix-test
  (let [[_ req] (run-with-capture #(storage/list-files (valid-storage)))
        body (parse-body req)]
    (is (= "" (get body "prefix")))))

(deftest remove-single-path-test
  (let [[_ req] (run-with-capture #(storage/remove (valid-storage) "a.png"))
        body (parse-body req)]
    (is (= :delete (:method req)))
    (is (= (str storage-url "/object/avatars") (:url req)))
    (is (= ["a.png"] (get body "prefixes")))))

(deftest remove-vector-paths-test
  (let [[_ req] (run-with-capture
                 #(storage/remove (valid-storage) ["a.png" "b.png"]))
        body (parse-body req)]
    (is (= ["a.png" "b.png"] (get body "prefixes")))))

(deftest move-request-test
  (let [[_ req] (run-with-capture
                 #(storage/move (valid-storage)
                                {:from "a.png" :to "b.png"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/object/move") (:url req)))
    (is (= "avatars" (get body "bucketId")))
    (is (= "a.png" (get body "sourceKey")))
    (is (= "b.png" (get body "destinationKey")))
    (is (not (contains? body "destinationBucket")))))

(deftest move-cross-bucket-test
  (let [[_ req] (run-with-capture
                 #(storage/move (valid-storage)
                                {:from "a" :to "b" :destination-bucket "logs"}))
        body (parse-body req)]
    (is (= "logs" (get body "destinationBucket")))))

(deftest copy-request-test
  (let [[_ req] (run-with-capture
                 #(storage/copy (valid-storage)
                                {:from "a.png" :to "b.png"}))]
    (is (= (str storage-url "/object/copy") (:url req)))))

(deftest info-request-test
  (let [[_ req] (run-with-capture
                 #(storage/info (valid-storage) "/folder/a.png"))]
    (is (= :get (:method req)))
    (is (= (str storage-url "/object/info/authenticated/avatars/folder/a.png")
           (:url req)))))

(deftest exists?-true-test
  (let [[result req] (run-with-capture
                      #(storage/exists? (valid-storage) "a.png"))]
    (is (true? result))
    (is (= :head (:method req)))
    (is (= (str storage-url "/object/avatars/a.png") (:url req)))))

(deftest exists?-false-test
  (let [[result _] (run-with-capture
                    #(storage/exists? (valid-storage) "a.png")
                    (error/from-http-response 404 nil :storage))]
    (is (false? result))))

(deftest create-signed-url-request-test
  (let [resp {:status 200
              :body {:signedURL "/object/sign/avatars/a.png?token=abc"}
              :headers {}}
        [result req] (run-with-capture
                      #(storage/create-signed-url (valid-storage) "a.png"
                                                  {:expires-in 60})
                      resp)
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str storage-url "/object/sign/avatars/a.png") (:url req)))
    (is (= 60 (get body "expiresIn")))
    (is (= (str storage-url "/object/sign/avatars/a.png?token=abc")
           (get-in result [:body :signedURL])))))

(deftest create-signed-url-with-download-test
  (let [resp {:status 200
              :body {:signedURL "/object/sign/avatars/a.png?token=abc"}
              :headers {}}
        [result _] (run-with-capture
                    #(storage/create-signed-url (valid-storage) "a.png"
                                                {:expires-in 60 :download true})
                    resp)]
    (is (.contains (get-in result [:body :signedURL]) "&download="))))

(deftest create-signed-urls-request-test
  (let [resp {:status 200
              :body [{:path "a.png" :signedURL "/object/sign/avatars/a.png?token=x"}
                     {:path "b.png" :signedURL "/object/sign/avatars/b.png?token=y"}]
              :headers {}}
        [result req] (run-with-capture
                      #(storage/create-signed-urls (valid-storage)
                                                   ["a.png" "b.png"]
                                                   {:expires-in 30})
                      resp)
        body (parse-body req)]
    (is (= (str storage-url "/object/sign/avatars") (:url req)))
    (is (= ["a.png" "b.png"] (get body "paths")))
    (is (= 30 (get body "expiresIn")))
    (is (= (str storage-url "/object/sign/avatars/a.png?token=x")
           (-> result :body first :signedURL)))))

(deftest upload-request-test
  (let [bytes (.getBytes "hello")
        [_ req] (run-with-capture
                 #(storage/upload (valid-storage) "/folder/a.txt" bytes
                                  {:content-type "text/plain"
                                   :upsert true
                                   :cache-control "120"}))]
    (is (= :post (:method req)))
    (is (= (str storage-url "/object/avatars/folder/a.txt") (:url req)))
    (is (= "text/plain" (get-in req [:headers "content-type"])))
    (is (= "max-age=120" (get-in req [:headers "cache-control"])))
    (is (= "true" (get-in req [:headers "x-upsert"])))
    (is (identical? bytes (:body req)))))

(deftest upload-default-headers-test
  (let [[_ req] (run-with-capture
                 #(storage/upload (valid-storage) "a.txt" "hi"))]
    (is (= "text/plain;charset=UTF-8" (get-in req [:headers "content-type"])))
    (is (= "max-age=3600" (get-in req [:headers "cache-control"])))
    (is (= "false" (get-in req [:headers "x-upsert"])))))

(deftest download-request-test
  (let [[_ req] (run-with-capture
                 #(storage/download (valid-storage) "/folder/a.png"))]
    (is (= :get (:method req)))
    (is (= (str storage-url "/object/authenticated/avatars/folder/a.png")
           (:url req)))
    (is (= :byte-array (:response-as req)))))
