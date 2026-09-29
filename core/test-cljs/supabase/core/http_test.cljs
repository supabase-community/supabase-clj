(ns supabase.core.http-test
  (:require [cljs.test :refer [deftest is testing async]]
            [supabase.core.client :as client]
            [supabase.core.http :as http]
            [supabase.core.transport :as transport]))

(defn- fake-transport
  "Transport that replays `handler` responses. `handler` receives the
  transport request map and returns a response map (or throws)."
  [handler]
  (reify transport/Transport
    (execute [_ _]
      (throw (js/Error. "sync execute unavailable in tests")))
    (execute-async [_ request]
      (try
        (js/Promise.resolve (handler request))
        (catch :default e
          (js/Promise.reject e))))))

(defn- test-client []
  (client/make-client "https://abc.supabase.co" "key"))

(deftest request-building-test
  (testing "pre-populates auth headers"
    (let [req (http/request (test-client))]
      (is (= "Bearer key" (get-in req [:headers "authorization"])))
      (is (= "key" (get-in req [:headers "apikey"])))))
  (testing "with-body JSON-encodes maps"
    (let [req (-> (http/request (test-client))
                  (http/with-body {:a 1}))]
      (is (= "{\"a\":1}" (:body req)))
      (is (= "application/json" (get-in req [:headers "content-type"])))))
  (testing "with-service-url resolves service and URL"
    (let [req (-> (http/request (test-client))
                  (http/with-service-url :auth-url "/signup"))]
      (is (= "https://abc.supabase.co/auth/v1/signup" (:url req)))
      (is (= :auth (:service req))))))

(deftest execute-async-success-test
  (async done
         (let [t (fake-transport (fn [_] {:status 200
                                          :headers {"content-type" "application/json"}
                                          :body "{\"ok\":true}"}))
               req (-> (http/request (test-client))
                       (http/with-service-url :auth-url "/x")
                       (http/with-transport t))]
           (-> (http/execute-async req)
               (.then (fn [resp]
                        (is (= 200 (:status resp)))
                        (is (= {:ok true} (:body resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest execute-async-anomaly-test
  (async done
         (let [t (fake-transport (fn [_] {:status 404 :headers {} :body "{}"}))
               req (-> (http/request (test-client))
                       (http/with-service-url :storage-url "/x")
                       (http/with-transport t))]
           (-> (http/execute-async req)
               (.then (fn [resp]
                        (is (= :cognitect.anomalies/not-found
                               (:cognitect.anomalies/category resp)))
                        (is (= 404 (:http/status resp)))
                        (is (= :storage (:supabase/service resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest execute-async-transport-failure-test
  (async done
         (let [t (fake-transport (fn [_] (throw (js/TypeError. "fetch failed"))))
               req (-> (http/request (test-client))
                       (http/with-service-url :auth-url "/x")
                       (http/with-transport t))]
           (-> (http/execute-async req)
               (.then (fn [resp]
                        (is (= :cognitect.anomalies/fault
                               (:cognitect.anomalies/category resp)))
                        (is (= :exception (:supabase/code resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest execute-async-retry-test
  (async done
         (let [calls (atom 0)
               t (fake-transport (fn [_]
                                   (swap! calls inc)
                                   (if (< @calls 2)
                                     {:status 503 :headers {} :body "{}"}
                                     {:status 200 :headers {} :body "{\"ok\":true}"})))
               req (-> (http/request (test-client))
                       (http/with-service-url :auth-url "/x")
                       (http/with-transport t)
                       (http/with-retries {:max-attempts 3 :initial-delay-ms 1 :max-delay-ms 5}))]
           (-> (http/execute-async req)
               (.then (fn [resp]
                        (is (= 2 @calls))
                        (is (= 200 (:status resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest execute-sync-throws-test
  (is (thrown? js/Error
               (http/execute (http/request (test-client))))))
