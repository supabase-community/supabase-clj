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

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. See https://supabase.com/docs/guides/auth/auth-mfa"
  (:require [clojure.string :as str]
            [supabase.auth :as auth]
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

  ## Example

      (enroll client \"<access-token>\" {:factor-type \"totp\"})"
  [client access-token params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/MFAEnroll params)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url factors-uri)
          (with-auth access-token)
          (http/with-body (snake-keys params))
          (http/execute))))

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
   (or (client/ensure-client client)
       (specs/ensure-valid specs/MFAChallenge params)
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url (factor-path factor-id "challenge"))
           (with-auth access-token)
           (http/with-body (snake-keys params))
           (http/execute)))))

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
  (or (client/ensure-client client)
      (specs/ensure-valid specs/MFAVerify params)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (factor-path factor-id "verify"))
          (with-auth access-token)
          (http/with-body (assoc (snake-keys params) :challenge_id challenge-id))
          (http/execute))))

(defn unenroll
  "Removes the factor `factor-id` from the user's account. Permanent.

  ## Example

      (unenroll client \"<access-token>\" \"<factor-id>\")"
  [client access-token factor-id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (factor-path factor-id))
          (with-auth access-token)
          (http/execute))))

(defn challenge-and-verify
  "Challenge + verify in one call, for TOTP factors where the code is
  already at hand. Phone and WebAuthn factors need the separate calls.

  ## Example

      (challenge-and-verify client \"<access-token>\" \"<factor-id>\" \"123456\")"
  [client access-token factor-id code]
  (let [resp (challenge client access-token factor-id)]
    (if (error/anomaly? resp)
      resp
      (verify client access-token factor-id (get-in resp [:body :id]) {:code code}))))

(defn list-factors
  "Lists the user's MFA factors, grouped for convenience.

  On success the body is a map with:

    * `:all`      — every factor, verified or not
    * `:totp` / `:phone` / `:webauthn` — verified factors of that type

  ## Example

      (list-factors client \"<access-token>\")"
  [client access-token]
  (let [resp (auth/get-user client access-token)]
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
                           :webauthn (verified-of "webauthn")})))))

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
  (or (client/ensure-client client)
      (let [decoded (jwt/decode access-token)]
        (if (error/anomaly? decoded)
          decoded
          (let [resp (list-factors client access-token)]
            (if (error/anomaly? resp)
              resp
              (let [{:keys [aal amr]} (:payload decoded)
                    verified? (some #(= "verified" (:status %))
                                    (get-in resp [:body :all]))]
                {:current-level aal
                 :next-level (if verified? "aal2" aal)
                 :current-authentication-methods (vec (or amr []))})))))))
