(ns supabase.auth.admin.oauth-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.admin.oauth :as oauth]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def base-url "https://abc123.supabase.co")
(def test-client (client/make-client base-url "service-role-key"))

(def ^:private captured (atom nil))

(defn- capture-execute [req]
  (reset! captured req)
  {:status 200 :body {:ok true} :headers {}})

(defn- run-with-capture [f]
  (reset! captured nil)
  (with-redefs [http/execute capture-execute]
    [(f) @captured]))

(defn- parse-body [req] (json/read-value (:body req)))

(deftest list-clients-test
  (testing "no pagination"
    (let [[_ req] (run-with-capture #(oauth/list-clients test-client))]
      (is (= :get (:method req)))
      (is (= (str base-url "/auth/v1/admin/oauth/clients") (:url req)))))
  (testing "with pagination"
    (let [[_ req] (run-with-capture #(oauth/list-clients test-client {:page 2 :per-page 50}))]
      (is (= 2 (get-in req [:query "page"])))
      (is (= 50 (get-in req [:query "per_page"]))))))

(deftest create-client-test
  (let [[_ req] (run-with-capture
                 #(oauth/create-client test-client
                                       {:client-name "my-app"
                                        :redirect-uris ["https://app/cb"]
                                        :grant-types ["authorization_code"]}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/admin/oauth/clients") (:url req)))
    (is (= "my-app" (get body "client_name")))
    (is (= ["https://app/cb"] (get body "redirect_uris")))
    (is (= ["authorization_code"] (get body "grant_types")))))

(deftest create-client-validation-test
  (testing "client-name required"
    (is (error/anomaly? (oauth/create-client test-client {:redirect-uris ["https://app/cb"]}))))
  (testing "redirect-uris must be non-empty"
    (is (error/anomaly? (oauth/create-client test-client {:client-name "x" :redirect-uris []})))))

(deftest get-client-test
  (let [[_ req] (run-with-capture #(oauth/get-client test-client "c-1"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/oauth/clients/c-1") (:url req)))))

(deftest update-client-test
  (let [[_ req] (run-with-capture
                 #(oauth/update-client test-client "c-1" {:client-name "renamed"}))
        body (parse-body req)]
    (is (= :put (:method req)))
    (is (= (str base-url "/auth/v1/admin/oauth/clients/c-1") (:url req)))
    (is (= "renamed" (get body "client_name")))))

(deftest update-client-validation-test
  (is (error/anomaly? (oauth/update-client test-client "c-1" {}))))

(deftest delete-client-test
  (let [[_ req] (run-with-capture #(oauth/delete-client test-client "c-1"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/admin/oauth/clients/c-1") (:url req)))))

(deftest regenerate-client-secret-test
  (let [[_ req] (run-with-capture #(oauth/regenerate-client-secret test-client "c-1"))]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/admin/oauth/clients/c-1/regenerate_secret") (:url req)))))

(deftest oauth-invalid-client-test
  (is (error/anomaly? (oauth/list-clients {})))
  (is (error/anomaly? (oauth/get-client {} "c-1"))))
