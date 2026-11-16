(ns supabase.storage-specs-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [supabase.core.error :as error]
            [supabase.storage.specs :as specs])
  (:import (java.io ByteArrayInputStream File)))

(deftest upload-body-platform-types-test
  (testing "accepts all JVM upload body types"
    (is (nil? (specs/ensure-valid specs/UploadBody "hello")))
    (is (nil? (specs/ensure-valid specs/UploadBody (byte-array [1 2]))))
    (is (nil? (specs/ensure-valid specs/UploadBody
                                  (ByteArrayInputStream. (byte-array [1])))))
    (is (nil? (specs/ensure-valid specs/UploadBody (File. "upload.txt")))))
  (testing "rejects values outside the upload body contract"
    (let [anomaly (specs/ensure-valid specs/UploadBody 123)]
      (is (error/anomaly? anomaly))
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category anomaly)))
      (is (= "Invalid input" (:cognitect.anomalies/message anomaly)))
      (is (= :storage (:supabase/service anomaly)))
      (is (= "must be byte-array, InputStream, File, or string"
             (get-in (m/form (get-in anomaly [:malli/explanation :schema]))
                     [1 :error/message])))
      (is (some? (:malli/explanation anomaly))))))

(deftest download-response-as-platform-types-test
  (testing "JVM keeps byte-array and stream response validation"
    (is (true? (m/validate specs/DownloadOpts {})))
    (is (true? (m/validate specs/DownloadOpts {:response-as :byte-array})))
    (is (true? (m/validate specs/DownloadOpts {:response-as :stream})))
    (is (false? (m/validate specs/DownloadOpts {:response-as :json})))))
