(ns supabase.auth.jwt-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [supabase.auth.jwt :as jwt]
            [supabase.core.error :as error])
  (:import (java.security KeyPairGenerator Signature)
           (java.security.interfaces ECPublicKey RSAPublicKey)
           (java.security.spec ECGenParameterSpec)
           (java.util Arrays Base64)))

(defn- b64url ^String [^bytes b]
  (.encodeToString (Base64/getUrlEncoder) b))

(defn- strip-sign ^bytes [^bytes b]
  (if (and (> (alength b) 1) (zero? (aget b 0)))
    (Arrays/copyOfRange b 1 (alength b))
    b))

(defn- fixed-be ^bytes [^java.math.BigInteger n len]
  (let [b (strip-sign (.toByteArray n))
        out (byte-array len)]
    (System/arraycopy b 0 out (- len (alength b)) (alength b))
    out))

(defn- signing-input [header payload]
  (str (b64url (.getBytes (json/write-value-as-string header) "UTF-8")) "."
       (b64url (.getBytes (json/write-value-as-string payload) "UTF-8"))))

(defn- sign ^bytes [jdk-alg priv ^String input]
  (let [s (Signature/getInstance jdk-alg)]
    (.initSign s priv)
    (.update s (.getBytes input "US-ASCII"))
    (.sign s)))

;; ES256 signatures in JWTs are raw R||S; the JDK emits DER. Convert.
(defn- der->raw ^bytes [^bytes der]
  (let [rlen (aget der 3)
        roff 4
        slen (aget der (+ roff rlen 1))
        soff (+ roff rlen 2)
        r (Arrays/copyOfRange der roff (+ roff rlen))
        s (Arrays/copyOfRange der soff (+ soff slen))
        pad (fn [^bytes x]
              (let [x (strip-sign x) out (byte-array 32)]
                (System/arraycopy x 0 out (- 32 (alength x)) (alength x))
                out))]
    (byte-array (concat (seq (pad r)) (seq (pad s))))))

(deftest rsa-round-trip-test
  (let [kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
        ^RSAPublicKey pub (.getPublic kp)
        header {:alg "RS256" :kid "k1" :typ "JWT"}
        payload {:sub "user-1" :role "authenticated"}
        input (signing-input header payload)
        token (str input "." (b64url (sign "SHA256withRSA" (.getPrivate kp) input)))
        jwk {:kty "RSA" :kid "k1"
             :n (b64url (strip-sign (.toByteArray (.getModulus pub))))
             :e (b64url (strip-sign (.toByteArray (.getPublicExponent pub))))}]
    (testing "valid signature returns claims"
      (let [result (jwt/verify-with-keys [jwk] token)]
        (is (= "user-1" (get-in result [:claims :sub])))
        (is (= "RS256" (get-in result [:header :alg])))))
    (testing "tampered payload fails"
      (let [bad (str input "x." (b64url (sign "SHA256withRSA" (.getPrivate kp) input)))]
        (is (error/anomaly? (jwt/verify-with-keys [jwk] bad)))))))

(deftest ec-round-trip-test
  (let [gen (doto (KeyPairGenerator/getInstance "EC")
              (.initialize (ECGenParameterSpec. "secp256r1")))
        kp (.generateKeyPair gen)
        ^ECPublicKey pub (.getPublic kp)
        w (.getW pub)
        header {:alg "ES256" :kid "k2" :typ "JWT"}
        payload {:sub "user-2"}
        input (signing-input header payload)
        raw-sig (der->raw (sign "SHA256withECDSA" (.getPrivate kp) input))
        token (str input "." (b64url raw-sig))
        jwk {:kty "EC" :kid "k2" :crv "P-256"
             :x (b64url (fixed-be (.getAffineX w) 32))
             :y (b64url (fixed-be (.getAffineY w) 32))}
        result (jwt/verify-with-keys [jwk] token)]
    (is (= "user-2" (get-in result [:claims :sub])))
    (is (= "ES256" (get-in result [:header :alg])))))

(deftest malformed-token-test
  (is (error/anomaly? (jwt/decode "only.two")))
  (is (error/anomaly? (jwt/verify-with-keys [] "a.b.c"))))
