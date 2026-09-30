(ns supabase.auth.mfa-test
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [supabase.auth.mfa :as mfa]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]))

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

(deftest enroll-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"id\":\"f1\",\"factor_type\":\"totp\",\"totp\":{\"secret\":\"ABC\"}}"}))]
           (-> (mfa/enroll (test-client {:transport t}) "at"
                           {:factor-type "totp" :friendly-name "app"})
               (.then (fn [resp]
                        (is (= "f1" (get-in resp [:body :id])))
                        (is (= "{\"factor_type\":\"totp\",\"friendly_name\":\"app\"}"
                               (:body @seen)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest challenge-and-verify-test
  (async done
         (let [calls (atom [])
               t (fake-transport
                  (fn [request]
                    (swap! calls conj (:url request))
                    (if (str/includes? (:url request) "challenge")
                      {:status 200 :headers {} :body "{\"id\":\"c1\"}"}
                      {:status 200 :headers {}
                       :body "{\"access_token\":\"at2\",\"aal\":\"aal2\"}"})))]
           (-> (mfa/challenge-and-verify (test-client {:transport t}) "at" "f1" "123456")
               (.then (fn [resp]
                        (is (= "aal2" (get-in resp [:body :aal])))
                        (is (= 2 (count @calls)))
                        (is (str/includes? (second @calls) "/factors/f1/verify"))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest list-factors-test
  (async done
         (let [t (fake-transport
                  (fn [_] {:status 200 :headers {}
                           :body "{\"factors\":[{\"factor_type\":\"totp\",\"status\":\"verified\"},{\"factor_type\":\"phone\",\"status\":\"unverified\"}]}"}))]
           (-> (mfa/list-factors (test-client {:transport t}) "at")
               (.then (fn [resp]
                        (is (= 2 (count (get-in resp [:body :all]))))
                        (is (= 1 (count (get-in resp [:body :totp]))))
                        (is (= 0 (count (get-in resp [:body :phone]))))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest recovery-codes-status-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"id\":\"rc1\",\"type\":\"recovery_code\",\"total\":8,\"remaining\":8}"}))]
           (-> (mfa/get-recovery-codes-status (test-client {:transport t}) "at")
               (.then (fn [resp]
                        (is (= 8 (get-in resp [:body :remaining])))
                        (is (str/includes? (:url @seen) "/factors/recovery-codes"))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest validation-anomaly-is-a-promise-test
  (async done
         (-> (mfa/enroll (test-client) "at" {})
             (.then (fn [resp]
                      (is (error/anomaly? resp))
                      (done)))
             (.catch (fn [e] (is false (str e)) (done))))))
