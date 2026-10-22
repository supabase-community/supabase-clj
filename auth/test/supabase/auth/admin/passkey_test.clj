(ns supabase.auth.admin.passkey-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.auth.admin.passkey :as passkey]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def base-url "https://abc123.supabase.co")
(def test-client (client/make-client base-url "service-role-key"))
(def user-id "00000000-0000-0000-0000-000000000001")
(def passkey-id "00000000-0000-0000-0000-000000000002")

(def ^:private captured (atom nil))

(defn- capture-execute [req]
  (reset! captured req)
  {:status 200 :body {:ok true} :headers {}})

(defn- run-with-capture [f]
  (reset! captured nil)
  (with-redefs [http/execute capture-execute]
    [(f) @captured]))

(deftest list-passkeys-test
  (let [[_ req] (run-with-capture #(passkey/list-passkeys test-client user-id))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/" user-id "/passkeys") (:url req)))))

(deftest delete-passkey-test
  (let [[_ req] (run-with-capture
                 #(passkey/delete-passkey test-client user-id passkey-id))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/" user-id "/passkeys/" passkey-id)
           (:url req)))))

(deftest passkey-args-validation-test
  (testing "ids must be UUIDs, mirroring auth-js"
    (is (error/anomaly? (passkey/list-passkeys test-client "not-a-uuid")))
    (is (error/anomaly? (passkey/delete-passkey test-client user-id "not-a-uuid")))
    (is (error/anomaly? (passkey/delete-passkey test-client "not-a-uuid" passkey-id))))
  (testing "invalid client"
    (is (error/anomaly? (passkey/list-passkeys {} user-id)))
    (is (error/anomaly? (passkey/delete-passkey {} user-id passkey-id)))))
