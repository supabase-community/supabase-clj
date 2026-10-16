(ns supabase.auth.passkey
  "Passkey (WebAuthn) REST API against Supabase Auth.

  This is the low-level, two-step API plus passkey management, acting on
  behalf of the signed-in user identified by `access-token` — the JWT from
  an active session. The browser-side WebAuthn ceremony (prompting the user
  and marshalling credentials) lives in [[supabase.auth.webauthn]]; use it
  unless you run the ceremony yourself.

  Two-step registration (requires an active session):

      (require '[supabase.auth.passkey :as passkey])

      ;; 1. challenge + credential creation options for the browser
      (passkey/start-registration client token)

      ;; 2. verify the credential the ceremony produced
      (passkey/verify-registration client token challenge-id credential)

  Two-step authentication (no session yet, so no access token — the client's
  API key authenticates the request, mirroring auth-js):

      (passkey/start-authentication client)
      (passkey/verify-authentication client challenge-id credential)

  Management:

      (passkey/list client token)
      (passkey/update client token passkey-id \"my laptop\")
      (passkey/delete client token passkey-id)

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. On ClojureScript every function returns a `js/Promise` of
  that value instead (fetch is async-only).
  See https://supabase.com/docs/guides/auth/passkeys"
  (:refer-clojure :exclude [list update])
  (:require [clojure.string :as str]
            [supabase.auth.errors :as errors]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private passkeys-uri "/passkeys")

(defn- passkey-path [& segments]
  (str/join "/" (cons passkeys-uri segments)))

(defn- with-auth [req access-token]
  (http/with-headers req {"authorization" (str "Bearer " access-token)}))

(defn- exec
  "Executes the request: synchronously on the JVM, returning a `js/Promise`
  on ClojureScript."
  [req]
  #?(:clj  (http/execute req)
     :cljs (http/execute-async req)))

(defn- resolve-when
  "Wraps a plain value so every public fn has one return type per
  platform: the value itself on the JVM, a resolved `js/Promise` on
  ClojureScript."
  [v]
  #?(:clj v :cljs (js/Promise.resolve v)))

;; ---------------------------------------------------------------------------
;; Two-step registration
;; ---------------------------------------------------------------------------

(defn start-registration
  "Starts the passkey registration ceremony for the user identified by
  `access-token`. The body carries `:challenge-id`, `:options` (W3C
  `PublicKeyCredentialCreationOptionsJSON`, base64url-encoded) and
  `:expires-at`.

  Pass `:options` to `navigator.credentials.create` (see
  `supabase.auth.webauthn/deserialize-credential-creation-options`), then
  complete the flow with `verify-registration`.

  ## Example

      (start-registration client \"<access-token>\")"
  [client access-token]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (passkey-path "registration" "options"))
          (with-auth access-token)
          (http/with-body {})
          (errors/with-auth-errors)
          exec)))

(defn verify-registration
  "Verifies the registration credential produced by the WebAuthn ceremony
  against the challenge `challenge-id` issued by `start-registration`.
  `credential` is the serialized registration response (from
  `supabase.auth.webauthn/serialize-credential-creation-response`, or the
  browser's own `credential.toJSON()` output); it is forwarded as-is.

  On success the body carries the registered passkey metadata (`:id`,
  `:friendly-name`, `:created-at`).

  ## Example

      (verify-registration client \"<access-token>\" \"<challenge-id>\" credential)"
  [client access-token challenge-id credential]
  (or (some-> (client/ensure-client client) resolve-when)
      (some-> (specs/ensure-valid specs/PasskeyRegistrationVerify
                                  {:challenge-id challenge-id :credential credential})
              resolve-when)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (passkey-path "registration" "verify"))
          (with-auth access-token)
          (http/with-body {:challenge_id challenge-id :credential credential})
          (errors/with-auth-errors)
          exec)))

;; ---------------------------------------------------------------------------
;; Two-step authentication
;; ---------------------------------------------------------------------------

(defn start-authentication
  "Starts the passkey authentication (sign-in) ceremony. The body carries
  `:challenge-id`, `:options` (W3C `PublicKeyCredentialRequestOptionsJSON`,
  base64url-encoded) and `:expires-at`.

  No `access-token`: this runs before a session exists, so the request is
  authenticated with the client's API key only, mirroring auth-js.

  `params` (optional):
    * `:captcha-token` — CAPTCHA verification token

  Pass `:options` to `navigator.credentials.get` (see
  `supabase.auth.webauthn/deserialize-credential-request-options`), then
  complete the flow with `verify-authentication`.

  ## Example

      (start-authentication client)"
  ([client] (start-authentication client {}))
  ([client params]
   (or (some-> (client/ensure-client client) resolve-when)
       (some-> (specs/ensure-valid specs/PasskeyStartAuthentication params) resolve-when)
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url (passkey-path "authentication" "options"))
           (http/with-body {:gotrue_meta_security
                            (cond-> {}
                              (:captcha-token params) (assoc :captcha_token (:captcha-token params)))})
           (errors/with-auth-errors)
           exec))))

(defn verify-authentication
  "Verifies the authentication credential produced by the WebAuthn ceremony
  against the challenge `challenge-id` issued by `start-authentication`.
  `credential` is the serialized assertion response (from
  `supabase.auth.webauthn/serialize-credential-request-response`, or the
  browser's own `credential.toJSON()` output); it is forwarded as-is.

  On success the body carries a fresh session (access/refresh tokens and
  the embedded `:user`), like every other sign-in function; adopt it as the
  active session.

  No `access-token`: this call creates the session, so the request is
  authenticated with the client's API key only, mirroring auth-js.

  ## Example

      (verify-authentication client \"<challenge-id>\" credential)"
  [client challenge-id credential]
  (or (some-> (client/ensure-client client) resolve-when)
      (some-> (specs/ensure-valid specs/PasskeyAuthenticationVerify
                                  {:challenge-id challenge-id :credential credential})
              resolve-when)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (passkey-path "authentication" "verify"))
          (http/with-body {:challenge_id challenge-id :credential credential})
          (errors/with-auth-errors)
          exec)))

;; ---------------------------------------------------------------------------
;; Management
;; ---------------------------------------------------------------------------

(defn list
  "Lists the passkeys registered for the user identified by `access-token`.

  On success the body is a vector of passkeys (`:id`, `:friendly-name`,
  `:created-at`, `:last-used-at`) — see `supabase.auth.specs/Passkey`.

  ## Example

      (list client \"<access-token>\")"
  [client access-token]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url passkeys-uri)
          (with-auth access-token)
          (errors/with-auth-errors)
          exec)))

(defn update
  "Renames the passkey `passkey-id` to `friendly-name` for the user
  identified by `access-token`.

  ## Example

      (update client \"<access-token>\" \"<passkey-id>\" \"my laptop\")"
  [client access-token passkey-id friendly-name]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-method :patch)
          (http/with-service-url :auth-url (passkey-path passkey-id))
          (with-auth access-token)
          (http/with-body {:friendly_name friendly-name})
          (errors/with-auth-errors)
          exec)))

(defn delete
  "Deletes the passkey `passkey-id` for the user identified by
  `access-token`. Permanent; the credential can no longer sign in.

  ## Example

      (delete client \"<access-token>\" \"<passkey-id>\")"
  [client access-token passkey-id]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (passkey-path passkey-id))
          (with-auth access-token)
          (errors/with-auth-errors)
          exec)))
