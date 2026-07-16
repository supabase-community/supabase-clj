(ns supabase.auth.admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.admin :as admin]
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

(deftest invite-user-by-email-test
  (let [[_ req] (run-with-capture
                 #(admin/invite-user-by-email test-client "new@example.com"
                                              {:data {:role "member"}
                                               :redirect-to "https://app/cb"}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/invite") (:url req)))
    (is (= "new@example.com" (get body "email")))
    (is (= {"role" "member"} (get body "data")))
    (is (= "https://app/cb" (get-in req [:query "redirect_to"])))))

(deftest generate-link-test
  (let [[_ req] (run-with-capture
                 #(admin/generate-link test-client
                                       {:type "signup" :email "a@b.com"
                                        :password "secret"
                                        :options {:redirect-to "https://app/cb"}}))
        body (parse-body req)]
    (is (= (str base-url "/auth/v1/admin/generate_link") (:url req)))
    (is (= "signup" (get body "type")))
    (is (= "a@b.com" (get body "email")))
    (is (= "secret" (get body "password")))
    (is (= "https://app/cb" (get body "redirect_to")))
    (is (not (contains? body "options")))))

(deftest create-user-test
  (let [[_ req] (run-with-capture
                 #(admin/create-user test-client
                                     {:email "a@b.com" :password "secret"
                                      :email-confirm true
                                      :user-metadata {:k "v"}}))
        body (parse-body req)]
    (is (= :post (:method req)))
    (is (= (str base-url "/auth/v1/admin/users") (:url req)))
    (is (= true (get body "email_confirm")))
    (is (= {"k" "v"} (get body "user_metadata")))))

(deftest list-users-test
  (let [[_ req] (run-with-capture #(admin/list-users test-client {:page 2 :per-page 50}))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/users") (:url req)))
    (is (= 2 (get-in req [:query "page"])))
    (is (= 50 (get-in req [:query "per_page"])))))

(deftest get-user-by-id-test
  (let [[_ req] (run-with-capture #(admin/get-user-by-id test-client "uid-1"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1") (:url req)))))

(deftest update-user-by-id-test
  (let [[_ req] (run-with-capture
                 #(admin/update-user-by-id test-client "uid-1" {:role "admin"}))
        body (parse-body req)]
    (is (= :put (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1") (:url req)))
    (is (= "admin" (get body "role")))))

(deftest delete-user-test
  (testing "hard delete by default"
    (let [[_ req] (run-with-capture #(admin/delete-user test-client "uid-1"))
          body (parse-body req)]
      (is (= :delete (:method req)))
      (is (= (str base-url "/auth/v1/admin/users/uid-1") (:url req)))
      (is (= false (get body "should_soft_delete")))))
  (testing "soft delete"
    (let [[_ req] (run-with-capture #(admin/delete-user test-client "uid-1" {:soft? true}))]
      (is (= true (get (parse-body req) "should_soft_delete"))))))

(deftest list-factors-test
  (let [[_ req] (run-with-capture #(admin/list-factors test-client "uid-1"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1/factors") (:url req)))))

(deftest delete-factor-test
  (let [[_ req] (run-with-capture #(admin/delete-factor test-client "uid-1" "f-1"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1/factors/f-1") (:url req)))))

(deftest list-identities-test
  (let [[_ req] (run-with-capture #(admin/list-identities test-client "uid-1"))]
    (is (= :get (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1/identities") (:url req)))))

(deftest delete-identity-test
  (let [[_ req] (run-with-capture #(admin/delete-identity test-client "uid-1" "id-1"))]
    (is (= :delete (:method req)))
    (is (= (str base-url "/auth/v1/admin/users/uid-1/identities/id-1") (:url req)))))

(deftest sign-out-test
  (testing "default global scope"
    (let [[_ req] (run-with-capture #(admin/sign-out test-client "user-tok"))]
      (is (= :post (:method req)))
      (is (= (str base-url "/auth/v1/logout") (:url req)))
      (is (= "Bearer user-tok" (get-in req [:headers "authorization"])))
      (is (= "global" (get-in req [:query "scope"])))))
  (testing "explicit others scope"
    (let [[_ req] (run-with-capture #(admin/sign-out test-client "user-tok" "others"))]
      (is (= "others" (get-in req [:query "scope"])))))
  (testing "invalid scope"
    (is (error/anomaly? (admin/sign-out test-client "user-tok" "everywhere")))))

(deftest admin-invalid-client-test
  (is (error/anomaly? (admin/list-users {})))
  (is (error/anomaly? (admin/create-user {} {:email "a@b.com"}))))
