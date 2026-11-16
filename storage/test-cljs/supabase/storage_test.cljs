(ns supabase.storage-test
  (:require [cljs.test :refer [deftest is async]]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]
            [supabase.storage :as storage]))

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

(deftest list-buckets-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "application/json"}
                     :body "[{\"id\":\"avatars\",\"name\":\"avatars\"}]"}))]
           (-> (storage/list-buckets (test-client {:transport t}))
               (.then (fn [resp]
                        (is (= 200 (:status resp)))
                        (is (= [{:id "avatars" :name "avatars"}]
                               (:body resp)))
                        (is (= :get (:method @seen)))
                        (is (= "https://abc.supabase.co/storage/v1/bucket"
                               (:url @seen)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest list-buckets-validation-anomaly-test
  (async done
         (let [t (fake-transport
                  (fn [_]
                    (throw (js/Error. "transport should not be called"))))
               result (storage/list-buckets (test-client {:transport t})
                                            {:unknown true})]
           (is (instance? js/Promise result))
           (-> (js/Promise.resolve result)
               (.then (fn [resp]
                        (is (error/anomaly? resp))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))
