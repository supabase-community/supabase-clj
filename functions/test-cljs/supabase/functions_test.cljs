(ns supabase.functions-test
  (:require [cljs.test :refer [deftest is async]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]
            [supabase.functions :as fns]))

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

(deftest invoke-json-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "application/json"}
                     :body "{\"message\":\"Hello, world!\"}"}))]
           (-> (fns/invoke (test-client {:transport t}) "hello"
                           {:body {:name "world"}})
               (.then (fn [resp]
                        (is (= 200 (:status resp)))
                        (is (= {:message "Hello, world!"} (:body resp)))
                        (is (= "{\"name\":\"world\"}" (:body @seen)))
                        (is (= "application/json"
                               (get-in @seen [:headers "content-type"])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest invoke-text-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "text/plain"}
                     :body "pong"}))]
           (-> (fns/invoke (test-client {:transport t}) "ping"
                           {:body "ping"})
               (.then (fn [resp]
                        (is (= "pong" (:body resp)))
                        (is (= "text/plain"
                               (get-in @seen [:headers "content-type"])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest invoke-error-test
  (async done
         (let [t (fake-transport (fn [_] {:status 502 :headers {} :body "{}"}))]
           (-> (fns/invoke (test-client {:transport t}) "boom" {})
               (.then (fn [resp]
                        (is (error/anomaly? resp))
                        (is (= :functions-relay-error (:supabase/code resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest invoke-byte-array-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {} :body (js/Uint8Array. 2)}))]
           (-> (fns/invoke (test-client {:transport t}) "bin"
                           {:response-as :byte-array})
               (.then (fn [resp]
                        (is (instance? js/Uint8Array (:body resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))
