(ns supabase.auth.webauthn-test
  (:require [cljs.test :refer [deftest is async testing]]
            [clojure.string :as str]
            [supabase.auth.webauthn :as webauthn]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.transport :as transport]))

;; ---------------------------------------------------------------------------
;; base64url codec (pure, cross-platform)
;; ---------------------------------------------------------------------------

(deftest bytes->base64url-test
  (is (= "AA" (webauthn/bytes->base64url [0])))
  (is (= "AAAA" (webauthn/bytes->base64url [0 0 0])))
  (is (= "____" (webauthn/bytes->base64url [255 255 255])))
  (is (= "aGVsbG8" (webauthn/bytes->base64url [104 101 108 108 111])))
  (is (= "q83v" (webauthn/bytes->base64url [171 205 239]))))

(deftest base64url->bytes-test
  (is (= [104 101 108 108 111] (webauthn/base64url->bytes "aGVsbG8")))
  (is (= [0 255 128 1] (webauthn/base64url->bytes
                        (webauthn/bytes->base64url [0 255 128 1]))))
  (is (= (range 256) (webauthn/base64url->bytes (webauthn/bytes->base64url (range 256)))))
  (is (= [0] (webauthn/base64url->bytes " AA = ")))
  (let [ex (try (webauthn/base64url->bytes "AA+")
                (catch js/Error e e))]
    (is (some? ex))
    (is (re-find #"Invalid Base64-URL character" (ex-message ex)))))

;; ---------------------------------------------------------------------------
;; options -> JS marshalling
;; ---------------------------------------------------------------------------

(defn- array-buffer->vec [buf]
  (vec (js/Array.from (js/Uint8Array. buf))))

(defn- creation-options []
  {:rp {:name "acme" :id "example.com"}
   :user {:id "AAEC" :name "u1" :displayName "User One"}
   :challenge "AQID"
   :pubKeyCredParams [{:type "public-key" :alg -7}]
   :excludeCredentials [{:id "AA" :type "public-key" :transports ["usb"]}]
   :authenticatorSelection {:residentKey "discouraged"}
   :attestation "direct"})

(deftest deserialize-credential-creation-options-test
  (let [js-opts (webauthn/deserialize-credential-creation-options (creation-options))]
    (is (= "example.com" (aget js-opts "rp" "id")))
    (is (= [1 2 3] (array-buffer->vec (.-challenge js-opts))))
    (is (= [0 1 2] (array-buffer->vec (aget js-opts "user" "id"))))
    (is (= "u1" (aget js-opts "user" "name")))
    (let [excluded (aget js-opts "excludeCredentials")]
      (is (= 1 (.-length excluded)))
      (is (= [0] (array-buffer->vec (aget excluded 0 "id"))))
      (is (= "public-key" (aget excluded 0 "type")))
      (is (= "usb" (aget excluded 0 "transports" 0))))
    (testing "non-binary fields pass through"
      (is (= "direct" (.-attestation js-opts)))
      (is (= -7 (aget js-opts "pubKeyCredParams" 0 "alg"))))))

(deftest deserialize-credential-request-options-test
  (let [js-opts (webauthn/deserialize-credential-request-options
                 {:challenge "AQID"
                  :rpId "example.com"
                  :allowCredentials [{:id "AA"}]})]
    (is (= [1 2 3] (array-buffer->vec (.-challenge js-opts))))
    (is (= "example.com" (.-rpId js-opts)))
    (let [allowed (aget js-opts "allowCredentials")]
      (is (= [0] (array-buffer->vec (aget allowed 0 "id"))))
      (testing "type defaults to public-key"
        (is (= "public-key" (aget allowed 0 "type")))))))

(deftest deserialize-options-validation-test
  (let [ex (try (webauthn/deserialize-credential-creation-options nil)
                (catch js/Error e e))]
    (is (some? ex))
    (is (re-find #"options are required" (ex-message ex))))
  (let [ex (try (webauthn/deserialize-credential-request-options {:challenge "AA+"})
                (catch js/Error e e))]
    (is (some? ex))
    (is (re-find #"Invalid Base64-URL character" (ex-message ex)))))

;; ---------------------------------------------------------------------------
;; credential -> JSON marshalling
;; ---------------------------------------------------------------------------

(defn- fake-creation-credential []
  #js {:id "cred-1"
       :response #js {:clientDataJSON (.-buffer (js/Uint8Array. #js [104 105]))
                      :attestationObject (.-buffer (js/Uint8Array. #js [1 2 3]))}
       :authenticatorAttachment "platform"
       :getClientExtensionResults (fn [] #js {:foo "bar"})})

(deftest serialize-credential-creation-response-test
  (let [serialized (webauthn/serialize-credential-creation-response
                    (fake-creation-credential))]
    (is (= "cred-1" (:id serialized)))
    (is (= "cred-1" (:rawId serialized)))
    (is (= "platform" (:authenticatorAttachment serialized)))
    (is (= "public-key" (:type serialized)))
    (is (= {"foo" "bar"} (:clientExtensionResults serialized)))
    (is (= "aGk" (get-in serialized [:response :clientDataJSON])
           (webauthn/bytes->base64url [104 105])))
    (is (= "AQID" (get-in serialized [:response :attestationObject])))))

(deftest serialize-credential-request-response-test
  (let [serialized (webauthn/serialize-credential-request-response
                    #js {:id "cred-2"
                         :response #js {:clientDataJSON (.-buffer (js/Uint8Array. #js [1]))
                                        :authenticatorData (.-buffer (js/Uint8Array. #js [2]))
                                        :signature (.-buffer (js/Uint8Array. #js [3]))
                                        :userHandle nil}
                         :getClientExtensionResults (fn [] #js {})})]
    (is (= "cred-2" (:id serialized)))
    (is (nil? (:authenticatorAttachment serialized)))
    (is (not (contains? (:response serialized) :userHandle)))
    (is (= "AQ" (get-in serialized [:response :clientDataJSON])
           (webauthn/bytes->base64url [1])))
    (is (= "Ag" (get-in serialized [:response :authenticatorData])))
    (is (= "Aw" (get-in serialized [:response :signature])))
    (is (= {} (:clientExtensionResults serialized)))))

;; ---------------------------------------------------------------------------
;; orchestration
;; ---------------------------------------------------------------------------

(deftest register-passkey-unsupported-test
  ;; node has no WebAuthn-capable browser: the orchestration must resolve
  ;; with an anomaly before touching navigator.credentials
  (async done
         (-> (webauthn/register-passkey (client/make-client "https://abc.supabase.co" "k")
                                        "at")
             (.then (fn [resp]
                      (is (error/anomaly? resp))
                      (is (= "Browser does not support WebAuthn"
                             (:cognitect.anomalies/message resp)))
                      (done)))
             (.catch (fn [e] (is false (str e)) (done))))))

(defn- fake-navigator-credentials
  "Replaces navigator.credentials with a fake resolving `credential` from
  create/get, recording the options it was passed."
  [credential seen]
  (set! (.-credentials js/navigator)
        #js {:create (fn [opts] (swap! seen conj opts)
                       (js/Promise.resolve credential))
             :get (fn [opts] (swap! seen conj opts)
                    (js/Promise.resolve credential))}))

(defn- with-browser-support
  "Runs `f` with `webauthn/browser-supports-webauthn?` forced true (the
  fake navigator below stands in for the browser)."
  [f]
  (with-redefs [webauthn/browser-supports-webauthn? (fn [] true)]
    (f)))

(defn- fake-transport
  [handler]
  (reify transport/Transport
    (execute [_ _] (throw (js/Error. "sync execute unavailable in tests")))
    (execute-async [_ request] (js/Promise.resolve (handler request)))))

;; hand-rolled fake credential standing in for a real PublicKeyCredential;
;; the real ceremony cannot run under node, so this only exercises the
;; orchestration wiring (see the namespace docstring)
(deftest register-passkey-orchestration-test
  (async done
         (let [seen-creds (atom [])
               seen-reqs (atom [])
               t (fake-transport
                  (fn [request]
                    (swap! seen-reqs conj request)
                    (if (str/ends-with? (:url request) "registration/options")
                      {:status 200 :headers {}
                       :body "{\"challenge_id\":\"c1\",\"options\":{\"rp\":{\"name\":\"acme\"},\"challenge\":\"AQID\",\"user\":{\"id\":\"AA\",\"name\":\"u1\",\"displayName\":\"U\"}}}"}
                      {:status 200 :headers {}
                       :body "{\"id\":\"pk-1\",\"friendly_name\":\"laptop\"}"})))
               credential (fake-creation-credential)]
           (fake-navigator-credentials credential seen-creds)
           (with-browser-support
             #(-> (webauthn/register-passkey (client/make-client "https://abc.supabase.co" "k" :transport t) "at")
                  (.then (fn [resp]
                           (is (= 2 (count @seen-reqs)))
                           (is (str/ends-with? (:url (first @seen-reqs)) "registration/options"))
                           (is (str/ends-with? (:url (second @seen-reqs)) "registration/verify"))
                           (is (= 1 (count @seen-creds)))
                           (testing "the browser got ArrayBuffers, not base64url strings"
                             (is (= [1 2 3] (-> @seen-creds first (aget "publicKey") (aget "challenge")
                                                array-buffer->vec))))
                           (is (= "pk-1" (get-in resp [:body :id])))
                           (done)))
                  (.catch (fn [e] (is false (str e)) (done))))))))

(deftest sign-in-with-passkey-orchestration-test
  (async done
         (let [seen-creds (atom [])
               seen-reqs (atom [])
               t (fake-transport
                  (fn [request]
                    (swap! seen-reqs conj request)
                    (if (str/ends-with? (:url request) "authentication/options")
                      {:status 200 :headers {}
                       :body "{\"challenge_id\":\"c2\",\"options\":{\"challenge\":\"AQID\",\"rpId\":\"example.com\"}}"}
                      {:status 200 :headers {}
                       :body "{\"access_token\":\"at1\",\"refresh_token\":\"rt1\"}"})))
               credential (fake-creation-credential)]
           (fake-navigator-credentials credential seen-creds)
           (with-browser-support
             #(-> (webauthn/sign-in-with-passkey (client/make-client "https://abc.supabase.co" "k" :transport t))
                  (.then (fn [resp]
                           (is (= 2 (count @seen-reqs)))
                           (is (str/ends-with? (:url (first @seen-reqs)) "authentication/options"))
                           (is (str/ends-with? (:url (second @seen-reqs)) "authentication/verify"))
                           (is (= 1 (count @seen-creds)))
                           (is (= "at1" (get-in resp [:body :access_token])))
                           (done)))
                  (.catch (fn [e] (is false (str e)) (done))))))))
