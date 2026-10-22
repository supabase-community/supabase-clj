(ns supabase.auth.passkey-test
  (:require [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [supabase.auth.passkey :as passkey]
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
   (apply client/make-client "https://abc.supabase.co" "anon-key"
          (mapcat identity opts))))

(defn- body-json [request] (js->clj (js/JSON.parse (:body request))))

(deftest start-registration-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"challenge_id\":\"c1\",\"options\":{\"rp\":{\"name\":\"acme\"}}}"}))]
           (-> (passkey/start-registration (test-client {:transport t}) "at")
               (.then (fn [resp]
                        (is (= :post (:method @seen)))
                        (is (str/includes? (:url @seen) "/passkeys/registration/options"))
                        (is (= "Bearer at" (get-in @seen [:headers "authorization"])))
                        (is (= "{}" (:body @seen)))
                        (is (= "c1" (get-in resp [:body :challenge_id])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest verify-registration-test
  (async done
         (let [seen (atom nil)
               credential {:id "cred-1" :rawId "cred-1" :type "public-key"
                           :response {:clientDataJSON "cdj" :attestationObject "ao"}}
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"id\":\"pk-1\",\"friendly_name\":\"laptop\"}"}))]
           (-> (passkey/verify-registration (test-client {:transport t}) "at" "c1" credential)
               (.then (fn [resp]
                        (is (str/includes? (:url @seen) "/passkeys/registration/verify"))
                        (is (= "c1" (get (body-json @seen) "challenge_id")))
                        (is (= "ao" (get-in (body-json @seen) ["credential" "response" "attestationObject"])))
                        (is (= "pk-1" (get-in resp [:body :id])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest start-authentication-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"challenge_id\":\"c2\",\"options\":{\"challenge\":\"AA\"}}"}))]
           (-> (passkey/start-authentication (test-client {:transport t}))
               (.then (fn [resp]
                        (is (str/includes? (:url @seen) "/passkeys/authentication/options"))
                        ;; no session exists yet: the api key authenticates
                        (is (= "Bearer anon-key" (get-in @seen [:headers "authorization"])))
                        (is (= {"gotrue_meta_security" {}} (body-json @seen)))
                        (is (= "c2" (get-in resp [:body :challenge_id])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest verify-authentication-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"access_token\":\"at1\",\"refresh_token\":\"rt1\",\"user\":{\"id\":\"u1\"}}"}))]
           (-> (passkey/verify-authentication (test-client {:transport t}) "c2" {:id "cred-1"})
               (.then (fn [resp]
                        (is (str/includes? (:url @seen) "/passkeys/authentication/verify"))
                        (is (= "Bearer anon-key" (get-in @seen [:headers "authorization"])))
                        (is (= "c2" (get (body-json @seen) "challenge_id")))
                        (is (= "at1" (get-in resp [:body :access_token])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest management-test
  (async done
         (let [seen (atom [])
               t (fake-transport
                  (fn [request]
                    (swap! seen conj request)
                    (if (= :delete (:method request))
                      {:status 204 :headers {} :body ""}
                      {:status 200 :headers {}
                       :body (if (= :patch (:method request))
                               "{\"id\":\"pk-1\",\"friendly_name\":\"renamed\"}"
                               "[{\"id\":\"pk-1\",\"friendly_name\":\"laptop\"}]")})))
               c (test-client {:transport t})]
           (-> (passkey/list c "at")
               (.then (fn [resp]
                        (is (str/ends-with? (:url (first @seen)) "/passkeys"))
                        (is (= "Bearer at" (get-in (first @seen) [:headers "authorization"])))
                        (is (= "laptop" (get-in resp [:body 0 :friendly_name])))
                        (passkey/update c "at" "pk-1" "renamed")))
               (.then (fn [_]
                        (is (= :patch (:method (second @seen))))
                        (is (= "renamed" (get (body-json (second @seen)) "friendly_name")))
                        (passkey/delete c "at" "pk-1")))
               (.then (fn [_]
                        (is (= :delete (:method (nth @seen 2))))
                        (is (str/ends-with? (:url (nth @seen 2)) "/passkeys/pk-1"))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest validation-anomaly-is-a-promise-test
  (async done
         (-> (passkey/verify-registration (test-client) "at" nil {})
             (.then (fn [resp]
                      (is (error/anomaly? resp))
                      (done)))
             (.catch (fn [e] (is false (str e)) (done))))))
