(ns supabase.auth.webauthn-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.auth.webauthn :as webauthn]
            [supabase.core.client :as client]
            [supabase.core.error :as error]))

(def test-client (client/make-client "https://abc123.supabase.co" "anon-key"))

(deftest bytes->base64url-test
  (testing "known vectors (RFC 4648 base64url, unpadded)"
    (is (= "" (webauthn/bytes->base64url [])))
    (is (= "AA" (webauthn/bytes->base64url [0])))
    (is (= "AAA" (webauthn/bytes->base64url [0 0])))
    (is (= "AAAA" (webauthn/bytes->base64url [0 0 0])))
    (is (= "____" (webauthn/bytes->base64url [255 255 255])))
    (is (= "aGVsbG8" (webauthn/bytes->base64url [104 101 108 108 111])))
    (is (= "q83v" (webauthn/bytes->base64url [171 205 239])))))

(deftest base64url->bytes-test
  (testing "round-trips"
    (is (= [] (webauthn/base64url->bytes "")))
    (is (= [104 101 108 108 111] (webauthn/base64url->bytes "aGVsbG8")))
    (is (= [0 255 128 1] (webauthn/base64url->bytes
                          (webauthn/bytes->base64url [0 255 128 1]))))
    (is (= (range 256)
           (webauthn/base64url->bytes (webauthn/bytes->base64url (range 256))))))
  (testing "ignores whitespace and padding"
    (is (= [0] (webauthn/base64url->bytes " AA = ")))
    (is (= [104 101 108 108 111] (webauthn/base64url->bytes "aGVs\nbG8="))))
  (testing "invalid character throws"
    (let [ex (try (webauthn/base64url->bytes "AA+")
                  (catch Exception e e))]
      (is (some? ex))
      (is (re-find #"Invalid Base64-URL character" (ex-message ex))))))

(deftest ceremony-fns-unavailable-on-jvm-test
  (testing "ClojureScript-only functions return an anomaly on the JVM"
    (is (error/anomaly? (webauthn/deserialize-credential-creation-options {})))
    (is (error/anomaly? (webauthn/deserialize-credential-request-options {})))
    (is (error/anomaly? (webauthn/serialize-credential-creation-response nil)))
    (is (error/anomaly? (webauthn/serialize-credential-request-response nil)))
    (is (error/anomaly? (webauthn/create-abort-signal)))
    (is (error/anomaly? (webauthn/cancel-ceremony)))
    (is (error/anomaly? (webauthn/create-credential {})))
    (is (error/anomaly? (webauthn/get-credential {})))
    (is (error/anomaly? (webauthn/register-passkey test-client "token")))
    (is (error/anomaly? (webauthn/register-passkey test-client "token" {})))
    (is (error/anomaly? (webauthn/sign-in-with-passkey test-client)))
    (is (error/anomaly? (webauthn/sign-in-with-passkey test-client {})))
    (is (= "passkeys require ClojureScript and a WebAuthn-capable browser"
           (:cognitect.anomalies/message (webauthn/register-passkey test-client "token")))))
  (testing "the support predicate is false without a browser"
    (is (false? (webauthn/browser-supports-webauthn?)))))
