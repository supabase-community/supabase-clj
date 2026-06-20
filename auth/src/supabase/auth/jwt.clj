(ns supabase.auth.jwt
  "Local JWT decoding and asymmetric signature verification for Supabase Auth.

  Supabase issues JWTs signed with either a symmetric secret (`HS256`, the
  legacy default — not verifiable client-side) or an asymmetric key
  (`RS256` / `ES256`). For asymmetric algorithms the public keys are
  published at `<auth-url>/.well-known/jwks.json`; this namespace fetches
  and caches that JWKS and verifies signatures using the JDK's built-in
  crypto, with no third-party dependency.

  Callers should use [[supabase.auth/get-claims]] rather than these helpers
  directly."
  (:require [jsonista.core :as json]
            [supabase.core.error :as error])
  (:import (java.math BigInteger)
           (java.net URI)
           (java.security AlgorithmParameters KeyFactory Signature)
           (java.security.spec ECGenParameterSpec ECPoint ECPublicKeySpec
                               RSAPublicKeySpec)
           (java.util Base64)))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn true}))

(defonce ^:private jwks-cache (atom {}))
(def ^:private jwks-ttl-ms (* 10 60 1000))

(defn- b64url->bytes ^bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- b64url->str [^String s]
  (String. (b64url->bytes s) "UTF-8"))

(defn decode
  "Splits and decodes a JWT into `{:header :payload :signed-data :signature}`
  without verifying it. Returns an anomaly if the token is malformed."
  [^String token]
  (let [parts (.split token "\\.")]
    (if (= 3 (alength parts))
      (try
        {:header (json/read-value (b64url->str (aget parts 0)) json-mapper)
         :payload (json/read-value (b64url->str (aget parts 1)) json-mapper)
         :signed-data (.getBytes (str (aget parts 0) "." (aget parts 1)) "US-ASCII")
         :signature (b64url->bytes (aget parts 2))}
        (catch Exception e
          (error/from-exception e :auth)))
      (error/anomaly :cognitect.anomalies/incorrect
                     {:cognitect.anomalies/message "Malformed JWT"
                      :supabase/service :auth}))))

;; ---------------------------------------------------------------------------
;; JWK -> java.security.PublicKey
;; ---------------------------------------------------------------------------

(defn- b64url->bigint ^BigInteger [^String s]
  (BigInteger. 1 (b64url->bytes s)))

(defn- rsa-public-key [{:keys [n e]}]
  (.generatePublic (KeyFactory/getInstance "RSA")
                   (RSAPublicKeySpec. (b64url->bigint n) (b64url->bigint e))))

(defn- p256-params []
  (let [ap (AlgorithmParameters/getInstance "EC")]
    (.init ap (ECGenParameterSpec. "secp256r1"))
    (.getParameterSpec ap java.security.spec.ECParameterSpec)))

(defn- ec-public-key [{:keys [x y]}]
  (.generatePublic (KeyFactory/getInstance "EC")
                   (ECPublicKeySpec. (ECPoint. (b64url->bigint x) (b64url->bigint y))
                                     (p256-params))))

(defn- jwk->public-key [{:keys [kty] :as jwk}]
  (case kty
    "RSA" (rsa-public-key jwk)
    "EC"  (ec-public-key jwk)
    nil))

;; ---------------------------------------------------------------------------
;; ES256 raw (R||S) -> DER, as the JDK Signature API expects
;; ---------------------------------------------------------------------------

(defn- ->der-int [^bytes b]
  ;; Strip leading zero bytes, then re-pad with one zero if the high bit is set
  ;; (so the DER integer stays positive).
  (let [pos (loop [i 0] (if (and (< i (dec (alength b))) (zero? (aget b i))) (recur (inc i)) i))
        trimmed (java.util.Arrays/copyOfRange b pos (alength b))
        trimmed (if (neg? (aget trimmed 0))
                  (byte-array (cons (byte 0) (seq trimmed)))
                  trimmed)]
    (byte-array (concat [(byte 0x02) (byte (alength trimmed))] (seq trimmed)))))

(defn- raw->der [^bytes sig]
  (let [half (quot (alength sig) 2)
        r (->der-int (java.util.Arrays/copyOfRange sig 0 half))
        s (->der-int (java.util.Arrays/copyOfRange sig half (alength sig)))
        body (byte-array (concat (seq r) (seq s)))]
    (byte-array (concat [(byte 0x30) (byte (alength body))] (seq body)))))

(defn- verify-signature [alg public-key ^bytes signed-data ^bytes signature]
  (let [[jdk-alg ^bytes sig] (case alg
                               "RS256" ["SHA256withRSA" signature]
                               "ES256" ["SHA256withECDSA" (raw->der signature)]
                               [nil nil])]
    (when jdk-alg
      (let [v (Signature/getInstance ^String jdk-alg)]
        (.initVerify v public-key)
        (.update v signed-data)
        (.verify v sig)))))

;; ---------------------------------------------------------------------------
;; JWKS fetch + cache
;; ---------------------------------------------------------------------------

(defn- jwks-url [auth-url] (str auth-url "/.well-known/jwks.json"))

(defn- fetch-jwks [url]
  (let [body (slurp (.toURL (URI. url)))]
    (:keys (json/read-value body json-mapper))))

(defn- cached-jwks [url]
  (let [now (System/currentTimeMillis)
        {:keys [jwks fetched-at]} (get @jwks-cache url)]
    (if (and jwks (< (- now fetched-at) jwks-ttl-ms))
      jwks
      (let [ks (fetch-jwks url)]
        (swap! jwks-cache assoc url {:jwks ks :fetched-at now})
        ks))))

(defn- find-key [ks kid]
  (or (first (filter #(= kid (:kid %)) ks))
      (when (= 1 (count ks)) (first ks))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn asymmetric-alg? [alg] (contains? #{"RS256" "ES256"} alg))

(defn verify-with-keys
  "Like [[verify]], but verifies against an already-resolved JWKS `keys`
  vector instead of fetching one. Network-free."
  [jwks ^String token]
  (let [decoded (decode token)]
    (if (error/anomaly? decoded)
      decoded
      (let [{:keys [header payload signed-data signature]} decoded
            {:keys [alg kid]} header]
        (try
          (let [jwk (find-key jwks kid)
                pk (some-> jwk jwk->public-key)]
            (cond
              (nil? pk)
              (error/anomaly :cognitect.anomalies/not-found
                             {:cognitect.anomalies/message (str "No JWK for kid " kid)
                              :supabase/service :auth})

              (verify-signature alg pk signed-data signature)
              {:claims payload :header header}

              :else
              (error/anomaly :cognitect.anomalies/forbidden
                             {:cognitect.anomalies/message "JWT signature verification failed"
                              :supabase/code :invalid-jwt
                              :supabase/service :auth})))
          (catch Exception e
            (error/from-exception e :auth)))))))

(defn verify
  "Verifies `token` against the JWKS published at `auth-url` and returns
  `{:claims <payload> :header <header>}` on success, or an anomaly.

  Only asymmetric algorithms (`RS256`, `ES256`) are verified here; callers
  fall back to server-side verification for `HS256`."
  [auth-url ^String token]
  (try
    (verify-with-keys (cached-jwks (jwks-url auth-url)) token)
    (catch Exception e
      (error/from-exception e :auth))))

(defn clear-cache!
  "Clears the process-global JWKS cache. Primarily for tests."
  []
  (reset! jwks-cache {}))
