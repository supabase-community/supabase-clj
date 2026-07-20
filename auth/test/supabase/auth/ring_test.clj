(ns supabase.auth.ring-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.auth :as auth]
            [supabase.auth.ring :as auth.ring]
            [supabase.core.client :as client]))

(def test-client (client/make-client "https://abc123.supabase.co" "anon-key"))

(defn- echo-handler [request]
  {:status 200 :body request})

(defn- request-with-token [token]
  {:request-method :get
   :uri "/"
   :headers {"authorization" (str "Bearer " token)}})

(def valid-claims {:claims {:sub "user-1" :role "authenticated"}
                   :header {:alg "ES256"}})

(def anomaly {:cognitect.anomalies/category :cognitect.anomalies/forbidden})

(deftest bearer-token-test
  (testing "extracts the token"
    (is (= "tok" (auth.ring/bearer-token (request-with-token "tok")))))
  (testing "scheme is case-insensitive"
    (is (= "tok" (auth.ring/bearer-token {:headers {"authorization" "bearer tok"}}))))
  (testing "other schemes and absent header"
    (is (nil? (auth.ring/bearer-token {:headers {"authorization" "Basic dXNlcg=="}})))
    (is (nil? (auth.ring/bearer-token {:headers {}})))))

(deftest wrap-authentication-valid-token-test
  (with-redefs [auth/get-claims (fn [_ _] valid-claims)]
    (let [app (auth.ring/wrap-authentication echo-handler test-client)
          {:keys [body]} (app (request-with-token "tok"))]
      (is (= {:sub "user-1" :role "authenticated"} (:supabase/claims body)))
      (is (= "tok" (:supabase/token body))))))

(deftest wrap-authentication-optional-test
  (testing "no token passes through without claims"
    (let [app (auth.ring/wrap-authentication echo-handler test-client)
          {:keys [status body]} (app {:headers {}})]
      (is (= 200 status))
      (is (not (contains? body :supabase/claims)))))
  (testing "invalid token passes through without claims"
    (with-redefs [auth/get-claims (fn [_ _] anomaly)]
      (let [app (auth.ring/wrap-authentication echo-handler test-client)
            {:keys [status body]} (app (request-with-token "bad"))]
        (is (= 200 status))
        (is (not (contains? body :supabase/claims)))))))

(deftest wrap-authentication-required-test
  (testing "no token rejected"
    (let [app (auth.ring/wrap-authentication echo-handler test-client {:required? true})]
      (is (= 401 (:status (app {:headers {}}))))))
  (testing "invalid token rejected"
    (with-redefs [auth/get-claims (fn [_ _] anomaly)]
      (let [app (auth.ring/wrap-authentication echo-handler test-client {:required? true})]
        (is (= 401 (:status (app (request-with-token "bad"))))))))
  (testing "custom rejection response"
    (let [app (auth.ring/wrap-authentication
               echo-handler test-client
               {:required? true
                :on-unauthenticated (fn [_] {:status 302 :headers {"location" "/login"} :body ""})})
          resp (app {:headers {}})]
      (is (= 302 (:status resp)))
      (is (= "/login" (get-in resp [:headers "location"]))))))

(deftest wrap-authentication-async-test
  (testing "rejection goes through respond"
    (let [app (auth.ring/wrap-authentication echo-handler test-client {:required? true})
          result (atom nil)]
      (app {:headers {}} #(reset! result %) #(throw %))
      (is (= 401 (:status @result)))))
  (testing "authenticated request reaches the async handler"
    (with-redefs [auth/get-claims (fn [_ _] valid-claims)]
      (let [handler (fn [request respond _] (respond {:status 200 :body request}))
            app (auth.ring/wrap-authentication handler test-client)
            result (atom nil)]
        (app (request-with-token "tok") #(reset! result %) #(throw %))
        (is (= "user-1" (get-in @result [:body :supabase/claims :sub])))))))
