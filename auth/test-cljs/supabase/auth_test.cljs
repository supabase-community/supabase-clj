(ns supabase.auth-test
  (:require [cljs.test :refer [deftest is testing async]]
            [supabase.auth :as auth]
            [supabase.auth.jwt :as jwt]
            [supabase.auth.session-store :as session-store]
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

(defn- session-body []
  {:access_token "at" :refresh_token "rt"
   :expires_at (+ 3600 (quot (.getTime (js/Date.)) 1000))})

(deftest sign-in-with-password-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200
                     :headers {"content-type" "application/json"}
                     :body "{\"access_token\":\"at\",\"refresh_token\":\"rt\"}"}))]
           (-> (auth/sign-in-with-password
                (test-client {:transport t})
                {:email "a@b.com" :password "secret"})
               (.then (fn [resp]
                        (is (= 200 (:status resp)))
                        (is (= "at" (get-in resp [:body :access_token])))
                        (is (= :post (:method @seen)))
                        (is (= "password" (get-in @seen [:query-params "grant_type"])))
                        (is (= "{\"email\":\"a@b.com\",\"password\":\"secret\"}"
                               (:body @seen)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest sign-up-test
  (async done
         (let [t (fake-transport
                  (fn [_] {:status 200 :headers {}
                           :body "{\"id\":\"u1\",\"email\":\"a@b.com\"}"}))]
           (-> (auth/sign-up (test-client {:transport t})
                             {:email "a@b.com" :password "secret"})
               (.then (fn [resp]
                        (is (= "u1" (get-in resp [:body :id])))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest verify-otp-test
  (async done
         (let [seen (atom nil)
               t (fake-transport
                  (fn [request]
                    (reset! seen request)
                    {:status 200 :headers {}
                     :body "{\"access_token\":\"at\",\"refresh_token\":\"rt\"}"}))]
           (-> (auth/verify-otp (test-client {:transport t})
                                {:type "email" :email "a@b.com" :token "123456"})
               (.then (fn [resp]
                        (is (= "at" (get-in resp [:body :access_token])))
                        (is (= "{\"type\":\"email\",\"email\":\"a@b.com\",\"token\":\"123456\"}"
                               (:body @seen)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest validation-anomaly-is-a-promise-test
  (testing "early validation failures still resolve via .then on CLJS"
    (async done
           (-> (auth/sign-in-with-password (test-client) {})
               (.then (fn [resp]
                        (is (error/anomaly? resp))
                        (is (= :cognitect.anomalies/incorrect
                               (:cognitect.anomalies/category resp)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest refresh-if-needed-passthrough-test
  (async done
         (let [session (assoc (session-body) :access-token "at" :refresh-token "rt")]
           (-> (auth/refresh-if-needed (test-client) session)
               (.then (fn [s]
                        (is (= session s))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest refresh-if-needed-refresh-test
  (async done
         (let [t (fake-transport
                  (fn [_] {:status 200 :headers {}
                           :body "{\"access_token\":\"at2\",\"refresh_token\":\"rt2\"}"}))
               session {:access-token "at" :refresh-token "rt" :expires-at 0}]
           (-> (auth/refresh-if-needed (test-client {:transport t}) session)
               (.then (fn [s]
                        (is (= "at2" (:access_token s)))
                        (done)))
               (.catch (fn [e] (is false (str e)) (done)))))))

(deftest needs-refresh?-test
  (is (false? (auth/needs-refresh? (session-body))))
  (is (true? (auth/needs-refresh? {:expires-at 0})))
  (is (false? (auth/needs-refresh? {}))))

(deftest jwt-decode-test
  (let [encode (fn [x]
                 (-> (js/JSON.stringify (clj->js x))
                     (js/btoa)
                     (.replaceAll "+" "-")
                     (.replaceAll "/" "_")
                     (.replace (js/RegExp. "=+$" "") "")))
        token (str (encode {:alg "HS256" :typ "JWT"}) "."
                   (encode {:sub "u1" :aal "aal1"}) "."
                   "sig")]
    (testing "decodes header and payload"
      (let [{:keys [header payload]} (jwt/decode token)]
        (is (= "HS256" (:alg header)))
        (is (= "u1" (:sub payload)))
        (is (= "aal1" (:aal payload)))))
    (testing "malformed tokens yield an anomaly"
      (is (error/anomaly? (jwt/decode "not-a-jwt"))))
    (testing "non-JSON segments yield an anomaly"
      (is (error/anomaly? (jwt/decode "aa.bb.cc"))))))

(deftest atom-session-store-test
  (let [store (session-store/atom-session-store)
        session {:access-token "at"}]
    (is (nil? (session-store/load-session store)))
    (session-store/save-session store session)
    (is (= session (session-store/load-session store)))
    (session-store/clear-session store)
    (is (nil? (session-store/load-session store)))))

(deftest local-storage-session-store-test
  (let [backing (atom {})
        shim #js {:getItem (fn [k] (get @backing k))
                  :setItem (fn [k v] (swap! backing assoc k v))
                  :removeItem (fn [k] (swap! backing dissoc k))}]
    (set! (.-localStorage js/globalThis) shim)
    (let [store (session-store/local-storage-session-store "sb-test-auth-token")
          session {:access-token "at" :expires-at 123}]
      (is (nil? (session-store/load-session store)))
      (session-store/save-session store session)
      (is (= session (session-store/load-session store)))
      (is (string? (get @backing "sb-test-auth-token")))
      (session-store/clear-session store)
      (is (nil? (session-store/load-session store))))))
