(ns supabase.storage-specs-test
  (:require [cljs.test :refer [deftest is testing]]
            [supabase.core.error :as error]
            [supabase.storage.specs :as specs]
            [malli.core :as m]))

(deftest upload-body-platform-types-test
  (testing "accepts strings and Uint8Array upload bodies"
    (is (nil? (specs/ensure-valid specs/UploadBody "hello")))
    (is (nil? (specs/ensure-valid specs/UploadBody (js/Uint8Array. 2)))))
  (testing "rejects JVM-only and unrelated values with a Storage anomaly"
    (let [anomaly (specs/ensure-valid specs/UploadBody 123)]
      (is (error/anomaly? anomaly))
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category anomaly)))
      (is (= "Invalid input" (:cognitect.anomalies/message anomaly)))
      (is (= :storage (:supabase/service anomaly)))
      (is (= "must be Uint8Array or string"
             (get-in (m/form (get-in anomaly [:malli/explanation :schema]))
                     [1 :error/message])))
      (is (some? (:malli/explanation anomaly))))))

(deftest download-response-as-platform-types-test
  (testing "CLJS supports the portable byte-array response only"
    (is (true? (m/validate specs/DownloadOpts {})))
    (is (true? (m/validate specs/DownloadOpts {:response-as :byte-array})))
    (is (false? (m/validate specs/DownloadOpts {:response-as :stream})))
    (is (false? (m/validate specs/DownloadOpts {:response-as :json})))))
