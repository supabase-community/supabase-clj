(ns supabase.auth.admin.custom-providers-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.admin.custom-providers :as providers]
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

(def valid-params
  {:provider-type "oidc"
   :identifier "acme"
   :name "Acme SSO"
   :client-id "cid"
   :client-secret "csecret"
   :discovery-url "https://sso.acme.com/.well-known/openid-configuration"})

(deftest list-providers-test
  (testing "no filter"
    (let [[_ req] (run-with-capture #(providers/list-providers test-client))]
      (is (= :get (:method req)))
      (is (= (str base-url "/auth/v1/admin/custom-providers") (:url req)))))
  (testing "type filter"
    (let [[_ req] (run-with-capture #(providers/list-providers test-client {:type "oidc"}))]
      (is (= "oidc" (get-in req [:query "type"])))))
  (testing "invalid type"
    (is (error/anomaly? (providers/list-providers test-client {:type "saml"})))))

(deftest create-provider-test
  (let [[_ req] (run-with-capture #(providers/create-provider test-client valid-params))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/admin/custom-providers") (:url req)))
    (is (= "oidc" (get body "provider_type")))
    (is (= "acme" (get body "identifier")))
    (is (= "csecret" (get body "client_secret")))
    (is (= "https://sso.acme.com/.well-known/openid-configuration"
           (get body "discovery_url")))))

(deftest create-provider-validation-test
  (testing "required fields"
    (is (error/anomaly? (providers/create-provider test-client (dissoc valid-params :name)))))
  (testing "provider-type restricted"
    (is (error/anomaly?
         (providers/create-provider test-client (assoc valid-params :provider-type "saml"))))))

(deftest get-provider-test
  (let [[_ req] (run-with-capture #(providers/get-provider test-client "acme"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/custom-providers/acme") (:url req)))))

(deftest update-provider-test
  (let [[_ req] (run-with-capture
                 #(providers/update-provider test-client "acme" {:enabled false}))
        body (parse-body req)]
    (is (= :put (:method req)))
    (is (= (str base-url "/auth/v1/admin/custom-providers/acme") (:url req)))
    (is (= false (get body "enabled")))))

(deftest update-provider-validation-test
  (testing "at least one attribute"
    (is (error/anomaly? (providers/update-provider test-client "acme" {}))))
  (testing "identifier immutable"
    (is (error/anomaly? (providers/update-provider test-client "acme" {:identifier "other"})))))

(deftest delete-provider-test
  (let [[_ req] (run-with-capture #(providers/delete-provider test-client "acme"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/admin/custom-providers/acme") (:url req)))))

(deftest providers-invalid-client-test
  (is (error/anomaly? (providers/list-providers {})))
  (is (error/anomaly? (providers/get-provider {} "acme"))))
