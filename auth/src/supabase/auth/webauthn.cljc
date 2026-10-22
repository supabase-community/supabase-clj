(ns supabase.auth.webauthn
  "WebAuthn ceremony helpers for passkeys.

  Three layers, ported from auth-js (`lib/base64url.ts`, `lib/webauthn.ts`,
  `lib/webauthn.dom.ts`):

    * Base64-URL codec — pure, cross-platform: `bytes->base64url` /
      `base64url->bytes` over vectors of bytes.
    * DOM <-> JSON marshalling — ClojureScript-only:
      `deserialize-credential-creation-options` /
      `deserialize-credential-request-options` turn the server's W3C JSON
      options (base64url strings) into the JS objects
      `navigator.credentials.create`/`get` expect;
      `serialize-credential-creation-response` /
      `serialize-credential-request-response` turn a credential back into
      the JSON-ready map the server verifies.
    * High-level ceremony orchestration — ClojureScript-only:
      `register-passkey` and `sign-in-with-passkey` run the full
      start -> prompt -> verify round-trip against
      `supabase.auth.passkey`.

  Unlike auth-js this port always uses the manual JSON marshalling (the
  W3C `parseCreationOptionsFromJSON` / `toJSON` fast paths are not taken),
  so behaviour is identical across browsers. Detailed WebAuthn error
  identification (auth-js `webauthn.errors.ts`) is also out of scope:
  ceremony failures become `:cognitect.anomalies/incorrect` anomalies
  carrying the browser error name under `:webauthn/error-name`.

  Every ClojureScript-only function returns an anomaly on the JVM instead:
  passkeys require ClojureScript and a WebAuthn-capable browser.

  TESTING GAP: the WebAuthn ceremony itself (`navigator.credentials.create`
  / `.get`) cannot run under node or jsdom. The codec and marshalling are
  unit-tested on both platforms (the orchestration wiring against a
  hand-rolled fake `navigator.credentials`); a real WebAuthn ceremony needs
  manual verification in a browser against a Supabase project with
  passkeys enabled."
  (:require [clojure.string :as str]
            #?(:cljs [supabase.auth.passkey :as passkey])
            [supabase.core.error :as error]))

;; ---------------------------------------------------------------------------
;; Base64-URL codec (port of lib/base64url.ts, cross-platform)
;; ---------------------------------------------------------------------------

(def ^:private to-base64url
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

(def ^:private from-base64url
  "Maps a character to its 6 bits. Characters that may appear in a
  Base64-URL string but should be ignored (whitespace, `=`) map to -2;
  unknown characters fall through to the -1 default at lookup."
  (merge (zipmap to-base64url (range 64))
         (zipmap " \t\n\r=" (repeat -2))))

(defn- base64url-char-bits [c]
  (get from-base64url c -1))

(defn base64url->bytes
  "Decodes a Base64-URL string into a vector of bytes (ints 0-255).
  Whitespace and `=` padding are ignored; any other invalid character
  throws. Port of auth-js `base64UrlToUint8Array`.

      (base64url->bytes \"aGVsbG8\") ; => [104 101 108 108 111]"
  [s]
  (loop [i 0, queue 0, queued-bits 0, acc (transient [])]
    (if (< i (count s))
      (let [c (nth s i)
            bits (base64url-char-bits c)]
        (cond
          (= bits -2)
          (recur (inc i) queue queued-bits acc)

          (neg? bits)
          (throw (ex-info (str "Invalid Base64-URL character \"" c "\"") {}))

          :else
          (let [queue (bit-or (bit-shift-left queue 6) bits)
                queued-bits (+ queued-bits 6)]
            (if (>= queued-bits 8)
              (recur (inc i)
                     (bit-and queue 0xff)
                     (- queued-bits 8)
                     (conj! acc (bit-and (bit-shift-right queue (- queued-bits 8)) 0xff)))
              (recur (inc i) queue queued-bits acc)))))
      (persistent! acc))))

(defn bytes->base64url
  "Encodes a sequence of bytes (ints 0-255) as a Base64-URL string, without
  padding. Port of auth-js `bytesToBase64URL`.

      (bytes->base64url [104 101 108 108 111]) ; => \"aGVsbG8\""
  [bytes]
  (let [n (count bytes)]
    (loop [i 0, queue 0, queued-bits 0, acc (transient [])]
      (if (< i n)
        (let [queue (bit-or (bit-shift-left queue 8) (nth bytes i))
              queued-bits (+ queued-bits 8)
              ;; emit while at least 6 bits are queued
              [queue queued-bits acc]
              (loop [queue queue, queued-bits queued-bits, acc acc]
                (if (>= queued-bits 6)
                  (recur queue
                         (- queued-bits 6)
                         (conj! acc (nth to-base64url
                                         (bit-and (bit-shift-right queue (- queued-bits 6))
                                                  0x3f))))
                  [queue queued-bits acc]))]
          (recur (inc i) (bit-and queue 0xff) queued-bits acc))
        (let [acc (if (pos? queued-bits)
                    ;; flush the partial byte into a final character
                    (conj! acc (nth to-base64url
                                    (bit-and (bit-shift-left queue (- 6 queued-bits)) 0x3f)))
                    acc)]
          (str/join (persistent! acc)))))))

;; ---------------------------------------------------------------------------
;; Platform guards
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- platform-anomaly
     "Anomaly returned by the ClojureScript-only functions on the JVM."
     []
     (error/anomaly :cognitect.anomalies/incorrect
                    {:cognitect.anomalies/message
                     "passkeys require ClojureScript and a WebAuthn-capable browser"
                     :supabase/service :auth})))

#?(:cljs
   (defn- browser-anomaly []
     (error/anomaly :cognitect.anomalies/incorrect
                    {:cognitect.anomalies/message "Browser does not support WebAuthn"
                     :supabase/service :auth})))

#?(:cljs
   (defn- ceremony-anomaly
     "Wraps a WebAuthn ceremony failure (browser API rejection or an
     unexpected credential shape) as an anomaly."
     [message err]
     (error/anomaly :cognitect.anomalies/incorrect
                    {:cognitect.anomalies/message (str "WebAuthn ceremony failed: " message)
                     :webauthn/error-name (some-> err (.-name))
                     :supabase/service :auth})))

#?(:cljs
   (defn- array-buffer
     "Uint8Array view over `bytes`, as an ArrayBuffer the WebAuthn API can
     consume."
     [bytes]
     (.-buffer (js/Uint8Array. (clj->js bytes)))))

#?(:cljs
   (defn- uint8->bytes
     "Vector of bytes from an ArrayBuffer or Uint8Array view."
     [u8]
     (vec (js/Array.from (js/Uint8Array. u8)))))

#?(:cljs
   (defn- descriptor->js
     "PublicKeyCredentialDescriptorJSON -> JS descriptor with a binary id."
     [{:keys [id type transports]}]
     (let [o #js {:id (array-buffer (base64url->bytes id))
                  :type (or type "public-key")}]
       (when transports
         (aset o "transports" (clj->js transports)))
       o)))

;; ---------------------------------------------------------------------------
;; DOM <-> JSON marshalling
;; ---------------------------------------------------------------------------

#?(:clj
   (defn deserialize-credential-creation-options
     "Converts the server's credential creation options (the `:options` map
     from `passkey/start-registration`, a W3C
     `PublicKeyCredentialCreationOptionsJSON` with base64url-encoded binary
     fields) into the JS `publicKey` object for
     `navigator.credentials.create`.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_options]
     (platform-anomaly))
   :cljs
   (defn deserialize-credential-creation-options
     "Converts the server's credential creation options (the `:options` map
     from `passkey/start-registration`, a W3C
     `PublicKeyCredentialCreationOptionsJSON` with base64url-encoded binary
     fields) into the JS `publicKey` object for
     `navigator.credentials.create`.

     The `challenge`, `user.id` and each `excludeCredentials[].id` are
     decoded to ArrayBuffers; everything else passes through unchanged.
     Browser options are otherwise accepted as-is.

     Throws on a malformed input (missing options map or invalid
     Base64-URL)."
     [options]
     (when-not (map? options)
       (throw (ex-info "Credential creation options are required" {})))
     (let [{:keys [challenge user excludeCredentials]} options
           result (clj->js (dissoc options :challenge :user :excludeCredentials))
           user-js #js {:id (array-buffer (base64url->bytes (:id user)))
                        :name (:name user)
                        :displayName (:displayName user)}]
       (aset result "challenge" (array-buffer (base64url->bytes challenge)))
       (aset result "user" user-js)
       (when (seq excludeCredentials)
         (aset result "excludeCredentials"
               (into-array (mapv descriptor->js excludeCredentials))))
       result)))

#?(:clj
   (defn deserialize-credential-request-options
     "Converts the server's credential request options (the `:options` map
     from `passkey/start-authentication`, a W3C
     `PublicKeyCredentialRequestOptionsJSON`) into the JS `publicKey`
     object for `navigator.credentials.get`.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_options]
     (platform-anomaly))
   :cljs
   (defn deserialize-credential-request-options
     "Converts the server's credential request options (the `:options` map
     from `passkey/start-authentication`, a W3C
     `PublicKeyCredentialRequestOptionsJSON`) into the JS `publicKey`
     object for `navigator.credentials.get`.

     The `challenge` and each `allowCredentials[].id` are decoded to
     ArrayBuffers; everything else passes through unchanged. Throws on a
     malformed input."
     [options]
     (when-not (map? options)
       (throw (ex-info "Credential request options are required" {})))
     (let [{:keys [challenge allowCredentials]} options
           result (clj->js (dissoc options :challenge :allowCredentials))]
       (aset result "challenge" (array-buffer (base64url->bytes challenge)))
       (when (seq allowCredentials)
         (aset result "allowCredentials"
               (into-array (mapv descriptor->js allowCredentials))))
       result)))

#?(:clj
   (defn serialize-credential-creation-response
     "Serializes the registration credential from `navigator.credentials.create`
     into the JSON-ready map (`RegistrationResponseJSON`) the server
     expects.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_credential]
     (platform-anomaly))
   :cljs
   (defn serialize-credential-creation-response
     "Serializes the registration credential from `navigator.credentials.create`
     into the JSON-ready map (`RegistrationResponseJSON`) the server
     expects: binary response fields are Base64-URL encoded, `rawId` mirrors
     `id`, extension outputs are converted to a plain map. Pass the result
     to `passkey/verify-registration`."
     [credential]
     (let [response (.-response credential)]
       (merge {:id (.-id credential)
               :rawId (.-id credential)
               :response {:clientDataJSON
                          (bytes->base64url (uint8->bytes (.-clientDataJSON response)))
                          :attestationObject
                          (bytes->base64url (uint8->bytes (.-attestationObject response)))}
               :type "public-key"
               :clientExtensionResults (js->clj (.getClientExtensionResults credential))}
              (when-let [attachment (.-authenticatorAttachment credential)]
                {:authenticatorAttachment attachment})))))

#?(:clj
   (defn serialize-credential-request-response
     "Serializes the authentication credential from `navigator.credentials.get`
     into the JSON-ready map (`AuthenticationResponseJSON`) the server
     expects.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_credential]
     (platform-anomaly))
   :cljs
   (defn serialize-credential-request-response
     "Serializes the authentication credential from `navigator.credentials.get`
     into the JSON-ready map (`AuthenticationResponseJSON`) the server
     expects: binary response fields are Base64-URL encoded (`:userHandle`
     omitted when absent), `rawId` mirrors `id`, extension outputs are
     converted to a plain map. Pass the result to
     `passkey/verify-authentication`."
     [credential]
     (let [response (.-response credential)
           user-handle (.-userHandle response)]
       (merge {:id (.-id credential)
               :rawId (.-id credential)
               :response (merge {:clientDataJSON
                                 (bytes->base64url (uint8->bytes (.-clientDataJSON response)))
                                 :authenticatorData
                                 (bytes->base64url (uint8->bytes (.-authenticatorData response)))
                                 :signature
                                 (bytes->base64url (uint8->bytes (.-signature response)))}
                                (when user-handle
                                  {:userHandle (bytes->base64url (uint8->bytes user-handle))}))
               :type "public-key"
               :clientExtensionResults (js->clj (.getClientExtensionResults credential))}
              (when-let [attachment (.-authenticatorAttachment credential)]
                {:authenticatorAttachment attachment})))))

(defn browser-supports-webauthn?
  "True when the runtime exposes the WebAuthn API: `PublicKeyCredential`
  and `navigator.credentials` with callable `create`/`get`. Always false on
  the JVM."
  []
  #?(:clj  false
     :cljs (boolean
            (and (exists? js/window)
                 (.-PublicKeyCredential js/window)
                 (exists? js/navigator)
                 (let [credentials (.-credentials js/navigator)]
                   (and credentials
                        (fn? (.-create credentials))
                        (fn? (.-get credentials))))))))

;; ---------------------------------------------------------------------------
;; Ceremony lifecycle (port of WebAuthnAbortService)
;; ---------------------------------------------------------------------------

#?(:cljs
   (def ^:private current-controller
     "Singleton so only one WebAuthn ceremony is in flight; starting a new
     one aborts the previous, avoiding the browser's \"operation already in
     progress\" error."
     nil))

#?(:cljs
   (defn- abort!
     [controller message]
     (let [err (js/Error. message)]
       (set! (.-name err) "AbortError")
       (.abort controller err))))

#?(:clj
   (defn create-abort-signal
     "Cancels any in-flight WebAuthn ceremony and returns a fresh
     `AbortSignal` to pass to `register-passkey` / `sign-in-with-passkey`
     (or to `navigator.credentials` directly).

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     []
     (platform-anomaly))
   :cljs
   (defn create-abort-signal
     "Cancels any in-flight WebAuthn ceremony and returns a fresh
     `AbortSignal` to pass to `register-passkey` / `sign-in-with-passkey`
     (or to `navigator.credentials` directly)."
     []
     (when-let [controller current-controller]
       (abort! controller "Cancelling existing WebAuthn API call for new one"))
     (let [controller (js/AbortController.)]
       (set! current-controller controller)
       (.-signal controller))))

#?(:clj
   (defn cancel-ceremony
     "Manually cancels the current WebAuthn ceremony, if any. Useful when
     the user dismisses the prompt.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     []
     (platform-anomaly))
   :cljs
   (defn cancel-ceremony
     "Manually cancels the current WebAuthn ceremony, if any. Useful when
     the user dismisses the prompt."
     []
     (when-let [controller current-controller]
       (abort! controller "Manually cancelling existing WebAuthn API call")
       (set! current-controller nil))
     nil))

;; ---------------------------------------------------------------------------
;; navigator.credentials wrappers
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- credential-options->js
     "Builds the options object for `navigator.credentials.create`/`get`,
     skipping absent keys (WebIDL enums like `mediation` reject null)."
     [{:keys [public-key signal mediation]}]
     (let [o #js {:publicKey public-key}]
       (when signal (aset o "signal" signal))
       (when mediation (aset o "mediation" mediation))
       o)))

#?(:cljs
   (defn- wrap-ceremony
     "Common shape for `navigator.credentials.create`/`get`: resolves with
     the credential, or with an anomaly when the ceremony fails or returns
     something unexpected. The W3C type check runs only when the runtime
     provides `PublicKeyCredential` (browsers); runtimes without it accept
     the credential as-is."
     [call]
     (-> call
         (.then (fn [resp]
                  (cond
                    (nil? resp)
                    (ceremony-anomaly "Empty credential response" nil)

                    (and (exists? js/PublicKeyCredential)
                         (not (instance? js/PublicKeyCredential resp)))
                    (ceremony-anomaly "Browser returned unexpected credential type" resp)

                    :else
                    resp)))
         (.catch (fn [err] (ceremony-anomaly (.-message err) err))))))

#?(:clj
   (defn create-credential
     "Runs `navigator.credentials.create` with `opts` (`:public-key` from
     `deserialize-credential-creation-options`, optional `:signal` from
     `create-abort-signal`).

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_opts]
     (platform-anomaly))
   :cljs
   (defn create-credential
     "Runs `navigator.credentials.create` with `opts` (`:public-key` from
     `deserialize-credential-creation-options`, optional `:signal` from
     `create-abort-signal`). Returns a `js/Promise` of the registration
     credential, or of an anomaly."
     [opts]
     (wrap-ceremony
      (.create (.-credentials js/navigator)
               (credential-options->js opts)))))

#?(:clj
   (defn get-credential
     "Runs `navigator.credentials.get` with `opts` (`:public-key` from
     `deserialize-credential-request-options`, optional `:signal` and
     `:mediation` — e.g. `\"conditional\"` for passkey autofill).

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     [_opts]
     (platform-anomaly))
   :cljs
   (defn get-credential
     "Runs `navigator.credentials.get` with `opts` (`:public-key` from
     `deserialize-credential-request-options`, optional `:signal` and
     `:mediation` — e.g. `\"conditional\"` for passkey autofill). Returns a
     `js/Promise` of the authentication credential, or of an anomaly."
     [opts]
     (wrap-ceremony
      (.get (.-credentials js/navigator)
            (credential-options->js opts)))))

;; ---------------------------------------------------------------------------
;; High-level orchestration
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- verify-registration*
     "start-registration -> credentials.create -> verify-registration."
     [client access-token start-resp signal]
     (if (error/anomaly? start-resp)
       start-resp
       (let [{:keys [challenge_id options]} (:body start-resp)]
         (-> (create-credential {:public-key (deserialize-credential-creation-options options)
                                 :signal signal})
             (.then (fn [credential]
                      (if (error/anomaly? credential)
                        credential
                        (passkey/verify-registration
                         client access-token challenge_id
                         (serialize-credential-creation-response credential))))))))))

#?(:cljs
   (defn- verify-authentication*
     "start-authentication -> credentials.get -> verify-authentication."
     [client start-resp signal mediation]
     (if (error/anomaly? start-resp)
       start-resp
       (let [{:keys [challenge_id options]} (:body start-resp)]
         (-> (get-credential {:public-key (deserialize-credential-request-options options)
                              :signal signal
                              :mediation mediation})
             (.then (fn [credential]
                      (if (error/anomaly? credential)
                        credential
                        (passkey/verify-authentication
                         client challenge_id
                         (serialize-credential-request-response credential))))))))))

#?(:clj
   (defn register-passkey
     "Registers a passkey for the signed-in user, running the full WebAuthn
     ceremony: `passkey/start-registration`, then
     `navigator.credentials.create`, then `passkey/verify-registration`.

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     ([_client _access-token] (platform-anomaly))
     ([_client _access-token _opts] (platform-anomaly)))
   :cljs
   (defn register-passkey
     "Registers a passkey for the signed-in user identified by
     `access-token`, running the full WebAuthn ceremony:

       1. `passkey/start-registration` — challenge + creation options
       2. `navigator.credentials.create` — the user picks/verifies their
          authenticator
       3. `passkey/verify-registration` — server verifies the credential

     `opts` (optional): `:signal` — an `AbortSignal`; when omitted a signal
     from `create-abort-signal` is used so concurrent ceremonies cancel
     each other.

     Returns a `js/Promise` of the verify response (the registered passkey
     metadata) or of an anomaly: invalid client, unsupported browser,
     ceremony failure or server rejection.

     ## Example

         (register-passkey client \"<access-token>\")

     NOTE: the ceremony itself cannot be exercised under node/jsdom; verify
     manually in a browser (see the namespace docstring)."
     ([client access-token] (register-passkey client access-token {}))
     ([client access-token {:keys [signal]}]
      (if-not (browser-supports-webauthn?)
        (js/Promise.resolve (browser-anomaly))
        (-> (passkey/start-registration client access-token)
            (.then #(verify-registration* client access-token %
                                          (or signal (create-abort-signal)))))))))

#?(:clj
   (defn sign-in-with-passkey
     "Signs in with a passkey, running the full WebAuthn ceremony:
     `passkey/start-authentication`, then `navigator.credentials.get`,
     then `passkey/verify-authentication` (which returns a session).

     JVM: returns an anomaly — passkeys require ClojureScript and a
     WebAuthn-capable browser."
     ([_client] (platform-anomaly))
     ([_client _opts] (platform-anomaly)))
   :cljs
   (defn sign-in-with-passkey
     "Signs in with a passkey, running the full WebAuthn ceremony:

       1. `passkey/start-authentication` — challenge + request options
       2. `navigator.credentials.get` — the user picks/verifies their
          passkey
       3. `passkey/verify-authentication` — server verifies the assertion
          and returns a session

     `opts` (optional):
       * `:captcha-token` — CAPTCHA verification token
       * `:signal` — an `AbortSignal`; when omitted a signal from
         `create-abort-signal` is used
       * `:mediation` — forwarded to `navigator.credentials.get`; use
         `\"conditional\"` for passkey autofill (the page needs an input
         with `autocomplete=\"username webauthn\"`)

     Returns a `js/Promise` of the verify response (whose body carries the
     fresh session) or of an anomaly.

     ## Example

         (sign-in-with-passkey client)
         (sign-in-with-passkey client {:mediation \"conditional\"})

     NOTE: the ceremony itself cannot be exercised under node/jsdom; verify
     manually in a browser (see the namespace docstring)."
     ([client] (sign-in-with-passkey client {}))
     ([client {:keys [captcha-token signal mediation]}]
      (if-not (browser-supports-webauthn?)
        (js/Promise.resolve (browser-anomaly))
        (-> (passkey/start-authentication client {:captcha-token captcha-token})
            (.then #(verify-authentication* client % (or signal (create-abort-signal))
                                            mediation)))))))
