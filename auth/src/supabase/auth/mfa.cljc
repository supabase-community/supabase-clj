(ns supabase.auth.mfa
  "Multi-factor authentication against Supabase Auth.

  Supports TOTP (authenticator apps), phone (SMS / WhatsApp) and WebAuthn
  factors. Every function acts on behalf of the signed-in user identified
  by `access-token` — the JWT from an active session.

  The usual TOTP flow:

      (require '[supabase.auth.mfa :as mfa])

      ;; 1. enroll — body carries the QR code / secret to show the user
      (mfa/enroll client token {:factor-type \"totp\" :friendly-name \"authenticator\"})

      ;; 2. user scans the QR and types the 6-digit code
      (mfa/challenge-and-verify client token factor-id \"123456\")

  Phone and WebAuthn factors need the explicit `challenge` + `verify`
  round-trip since the response arrives out of band.

  Recovery codes (experimental server-side) give users a fallback when
  their usual factor is unavailable:

      (mfa/generate-recovery-codes client token)       ;; show codes once
      (mfa/get-recovery-codes-status client token)     ;; remaining count
      (mfa/verify-recovery-code client token {:code \"K4M9-X7QP-2AB8-HT3Z\"})

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. On ClojureScript every function returns a `js/Promise` of
  that value instead (fetch is async-only).
  See https://supabase.com/docs/guides/auth/auth-mfa"
  (:require [clojure.string :as str]
            [supabase.auth :as auth]
            [supabase.auth.errors :as errors]
            [supabase.auth.jwt :as jwt]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def ^:private factors-uri "/factors")

(defn- factor-path [& segments]
  (str/join "/" (cons factors-uri segments)))

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

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

(declare unenroll-stale-unverified-factor)

(defn enroll
  "Enrolls a new MFA factor for the user. The factor starts `unverified`;
  complete a `challenge` + `verify` round-trip to activate it.

  ## Parameters

  * `client` — Supabase client
  * `access-token` — the user's access token
  * `params`:
    * `:factor-type` — `\"totp\"`, `\"phone\"` or `\"webauthn\"` (required)
    * `:friendly-name` — label shown in factor lists
    * `:issuer` — TOTP issuer domain (totp only)
    * `:phone` — E.164 phone number (required for phone factors)

  For TOTP the response body carries `:totp` with the QR code SVG, secret
  and provisioning URI to present to the user.

  When a `\"webauthn\"` enrollment with a `:friendly-name` fails, the stale
  unverified factor a previous failed registration left under that name is
  unenrolled first, so a retry with the same name can succeed. Verified
  factors are never touched. Mirrors auth-js #2641.

  ## Example

      (enroll client \"<access-token>\" {:factor-type \"totp\"})"
  [client access-token params]
  (or (some-> (client/ensure-client client) resolve-when)
      (some-> (specs/ensure-valid specs/MFAEnroll params) resolve-when)
      (let [req (-> (http/request client)
                    (http/with-method :post)
                    (http/with-service-url :auth-url factors-uri)
                    (with-auth access-token)
                    (http/with-body (snake-keys params))
                    (errors/with-auth-errors))
            handle (fn [resp]
                     (if (and (error/anomaly? resp)
                              (= "webauthn" (:factor-type params))
                              (:friendly-name params))
                       (do (unenroll-stale-unverified-factor client access-token
                                                             (:friendly-name params))
                           resp)
                       resp))]
        #?(:clj  (handle (exec req))
           :cljs (.then (exec req) handle)))))

(defn challenge
  "Creates a challenge for the factor `factor-id`. The returned body carries
  the challenge `:id` to pass to `verify`.

  `params` is factor-type specific and optional:

    * TOTP     — none
    * phone    — `:channel` (`\"sms\"` or `\"whatsapp\"`)
    * WebAuthn — `:webauthn` map (`rp_id`, optional `rp_origins`)

  ## Example

      (challenge client \"<access-token>\" \"<factor-id>\")
      (challenge client \"<access-token>\" \"<factor-id>\" {:channel \"sms\"})"
  ([client access-token factor-id] (challenge client access-token factor-id {}))
  ([client access-token factor-id params]
   (or (some-> (client/ensure-client client) resolve-when)
       (some-> (specs/ensure-valid specs/MFAChallenge params) resolve-when)
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url (factor-path factor-id "challenge"))
           (with-auth access-token)
           (http/with-body (snake-keys params))
           (errors/with-auth-errors)
           exec))))

(defn verify
  "Verifies the challenge `challenge-id` for factor `factor-id`. On success
  the body carries a new session with elevated assurance level (AAL2).

  `params` is factor-type specific:

    * TOTP / phone — `{:code \"123456\"}`
    * WebAuthn     — `{:webauthn {...credential response...}}`

  ## Example

      (verify client \"<access-token>\" \"<factor-id>\" \"<challenge-id>\"
              {:code \"123456\"})"
  [client access-token factor-id challenge-id params]
  (or (some-> (client/ensure-client client) resolve-when)
      (some-> (specs/ensure-valid specs/MFAVerify params) resolve-when)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (factor-path factor-id "verify"))
          (with-auth access-token)
          (http/with-body (assoc (snake-keys params) :challenge_id challenge-id))
          (errors/with-auth-errors)
          exec)))

(defn unenroll
  "Removes the factor `factor-id` from the user's account. Permanent.

  ## Example

      (unenroll client \"<access-token>\" \"<factor-id>\")"
  [client access-token factor-id]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (factor-path factor-id))
          (with-auth access-token)
          (errors/with-auth-errors)
          exec)))

(defn challenge-and-verify
  "Challenge + verify in one call, for TOTP factors where the code is
  already at hand. Phone and WebAuthn factors need the separate calls.

  ## Example

      (challenge-and-verify client \"<access-token>\" \"<factor-id>\" \"123456\")"
  [client access-token factor-id code]
  (letfn [(step [resp]
            (if (error/anomaly? resp)
              resp
              (verify client access-token factor-id
                      (get-in resp [:body :id]) {:code code})))]
    #?(:clj  (step (challenge client access-token factor-id))
       :cljs (.then (challenge client access-token factor-id) step))))

(defn- group-factors
  "Groups a get-user response's `:factors` list into `:all` + per-type
  verified buckets."
  [resp]
  (if (error/anomaly? resp)
    resp
    (let [factors (vec (get-in resp [:body :factors]))
          verified-of (fn [factor-type]
                        (filterv #(and (= factor-type (:factor_type %))
                                       (= "verified" (:status %)))
                                 factors))]
      (assoc resp :body {:all factors
                         :totp (verified-of "totp")
                         :phone (verified-of "phone")
                         :webauthn (verified-of "webauthn")}))))

(defn list-factors
  "Lists the user's MFA factors, grouped for convenience.

  On success the body is a map with:

    * `:all`      — every factor, verified or not
    * `:totp` / `:phone` / `:webauthn` — verified factors of that type

  ## Example

      (list-factors client \"<access-token>\")"
  [client access-token]
  #?(:clj  (group-factors (auth/get-user client access-token))
     :cljs (.then (auth/get-user client access-token) group-factors)))

(defn get-authenticator-assurance-level
  "Returns the user's current and next possible authenticator assurance
  levels.

    * AAL1 — single factor (password, magic link, OAuth)
    * AAL2 — at least one verified MFA factor was used

  The current level and authentication methods come from the JWT claims
  (decoded locally); the next achievable level requires the user's factor
  list, so this makes one `get-user` call.

  Returns `{:current-level :next-level :current-authentication-methods}`
  on success — levels are `\"aal1\"` / `\"aal2\"`, methods the raw `amr`
  claim entries — or an anomaly on failure.

  ## Example

      (get-authenticator-assurance-level client \"<access-token>\")"
  [client access-token]
  (or (some-> (client/ensure-client client) resolve-when)
      (let [decoded (jwt/decode access-token)]
        (if (error/anomaly? decoded)
          (resolve-when decoded)
          (letfn [(aal-from [resp]
                    (if (error/anomaly? resp)
                      resp
                      (let [{:keys [aal amr]} (:payload decoded)
                            verified? (some #(= "verified" (:status %))
                                            (get-in resp [:body :all]))]
                        {:current-level aal
                         :next-level (if verified? "aal2" aal)
                         :current-authentication-methods (vec (or amr []))})))]
            #?(:clj  (aal-from (list-factors client access-token))
               :cljs (.then (list-factors client access-token) aal-from)))))))

(defn- unenroll-stale-unverified-factor
  "Best-effort cleanup after a failed WebAuthn registration: unenrolls the
  unverified `webauthn` factor carrying `friendly-name`, when one exists.
  Only unverified factors match; a verified factor under that name is a
  credential in active use and is never removed. Cleanup failures are
  swallowed so the caller still sees the original enroll anomaly."
  [client access-token friendly-name]
  (letfn [(cleanup [resp]
            (when-not (error/anomaly? resp)
              (when-let [factor (some #(when (and (= "webauthn" (:factor_type %))
                                                  (= friendly-name (:friendly_name %))
                                                  (= "unverified" (:status %)))
                                         %)
                                      (get-in resp [:body :all]))]
                (unenroll client access-token (:id factor))))
            nil)]
    #?(:clj  (cleanup (list-factors client access-token))
       :cljs (.then (list-factors client access-token) cleanup))))

;; ---------------------------------------------------------------------------
;; Recovery codes (experimental)
;; ---------------------------------------------------------------------------

(def ^:private recovery-codes-uri "/factors/recovery-codes")

(defn get-recovery-codes-status
  "Returns the enrollment status of the user's recovery codes.

  On success the body is a map with `:id` (the recovery codes factor id),
  `:type` (always `\"recovery_code\"`), `:total` (codes in the current set)
  and `:remaining` (codes not yet consumed). It never contains code values.

  Experimental: requires recovery codes to be enabled on the server.

  ## Example

      (get-recovery-codes-status client \"<access-token>\")"
  [client access-token]
  (or (some-> (client/ensure-client client) resolve-when)
      (-> (http/request client)
          (http/with-service-url :auth-url recovery-codes-uri)
          (with-auth access-token)
          (errors/with-auth-errors)
          exec)))

(defn generate-recovery-codes
  "Generates the user's set of recovery codes. The plaintext `:codes` in the
  response body are returned exactly once and cannot be retrieved again;
  present them to the user for safe-keeping.

  `params`:

    * `:friendly-name` — label for the recovery codes factor, as shown in
      factor lists. Must be unique among the user's factors; the server
      defaults it to `\"Recovery codes\"` when omitted. No request body is
      sent unless a name is given.

  Experimental: requires recovery codes to be enabled on the server.

  ## Example

      (generate-recovery-codes client \"<access-token>\")
      (generate-recovery-codes client \"<access-token>\" {:friendly-name \"backup\"})"
  ([client access-token] (generate-recovery-codes client access-token {}))
  ([client access-token params]
   (or (some-> (client/ensure-client client) resolve-when)
       (some-> (specs/ensure-valid specs/MFARecoveryCodesGenerate params) resolve-when)
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url recovery-codes-uri)
           (with-auth access-token)
           (http/with-body (when (:friendly-name params) (snake-keys params)))
           (errors/with-auth-errors)
           exec))))

(defn verify-recovery-code
  "Verifies one of the user's recovery codes and upgrades the session to
  AAL2. Each code can be used only once.

  `params`: `{:code \"K4M9-X7QP-2AB8-HT3Z\"}`. Letter case, whitespace and
  `-` separators are ignored by the server, so the code can be passed
  exactly as the user typed it.

  On success the body carries a fresh session (new access/refresh tokens)
  that replaces the current one: adopt it as the active session, as the
  user's other AAL1 sessions are signed out server-side.

  Experimental: requires recovery codes to be enabled on the server.

  ## Example

      (verify-recovery-code client \"<access-token>\" {:code \"K4M9-X7QP-2AB8-HT3Z\"})"
  [client access-token params]
  (or (some-> (client/ensure-client client) resolve-when)
      (some-> (specs/ensure-valid specs/MFARecoveryCodeVerify params) resolve-when)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (str recovery-codes-uri "/verify"))
          (with-auth access-token)
          (http/with-body (snake-keys params))
          (errors/with-auth-errors)
          exec)))
