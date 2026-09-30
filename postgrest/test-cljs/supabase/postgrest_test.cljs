(ns supabase.postgrest-test
  (:require [cljs.test :refer [deftest is testing async]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]
            [supabase.postgrest :as pg]
            [supabase.postgrest.encode :as enc]))

(defn- fake-transport
  "Transport that replays `handler` responses. `handler` receives the
  transport request map and returns a response map."
  [handler]
  (reify transport/Transport
    (execute [_ _]
      (throw (js/Error. "sync execute unavailable in tests")))
    (execute-async [_ request]
      (js/Promise.resolve (handler request)))))

(defn- test-client
  ([] (test-client {}))
  ([opts]
   (apply client/make-client "https://abc.supabase.co" "key"
          (mapcat identity opts))))

(deftest builder-test
  (let [req (-> (pg/from (test-client) "users")
                (pg/select "*")
                (pg/eq "active" true)
                (pg/order "created_at")
                (pg/limit 10))]
    (testing "resolves the REST URL"
      (is (= "https://abc.supabase.co/rest/v1/users" (:url req))))
    (testing "accumulates query params"
      (is (= "*" (get-in req [:query "select"])))
      (is (= "eq.true" (get-in req [:query "active"])))
      (is (= "created_at.desc.nullslast" (get-in req [:query "order"])))
      (is (= "10" (get-in req [:query "limit"]))))
    (testing "HEAD select by default (count only)"
      (is (= :head (:method req))))))

(deftest execute-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "application/json"}
                     :body "[{\"id\":1}]"}))]
           (-> (-> (pg/from (test-client {:transport t}) "users")
                   (pg/select "*")
                   (pg/execute))
               (.then (fn [resp]
                        (is (= 200 (:status resp)))
                        (is (= [{:id 1}] (:body resp)))
                        (is (= "public" (get-in @seen [:headers "accept-profile"])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest execute-error-enrichment-test
  (async done
         (let [t (fake-transport
                  (fn [_]
                    {:status 406
                     :headers {}
                     :body "{\"code\":\"PGRST116\",\"message\":\"0 or >1 rows\",\"hint\":\"Use limit\"}"}))]
           (-> (-> (pg/from (test-client {:transport t}) "users")
                   (pg/select "*")
                   (pg/single)
                   (pg/execute))
               (.then (fn [resp]
                        (is (error/anomaly? resp))
                        (is (= :database-error (:supabase/code resp)))
                        (is (= "PGRST116" (:postgrest/code resp)))
                        (is (= "Use limit" (:postgrest/hint resp)))
                        (is (= :cognitect.anomalies/not-found
                               (:cognitect.anomalies/category resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest get-openapi-spec-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "application/openapi+json"}
                     :body "{\"openapi\":\"3.0.0\",\"paths\":{}}"}))]
           (-> (pg/get-openapi-spec (test-client {:transport t})
                                    {:schema "billing"})
               (.then (fn [resp]
                        (is (= "3.0.0" (get-in resp [:body :openapi])))
                        (is (= "application/openapi+json"
                               (get-in @seen [:headers "accept"])))
                        (is (= "billing" (get-in @seen [:headers "accept-profile"])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest encode-test
  (testing "pg-array quotes special elements"
    (is (= "{a,b,c}" (enc/pg-array ["a" "b" "c"])))
    (is (= "{NULL}" (enc/pg-array [nil]))))
  (testing "->iso accepts js/Date"
    (is (= "2026-01-02T03:04:05.000Z"
           (enc/->iso (js/Date. "2026-01-02T03:04:05.000Z")))))
  (testing "pg-range with js/Date endpoints"
    (is (= "[2026-01-01T00:00:00.000Z,)"
           (enc/pg-range (js/Date. "2026-01-01T00:00:00.000Z") nil)))))
