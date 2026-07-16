(ns supabase.auth
  "Authentication and user management against Supabase Auth.

  Supports password, OAuth, OTP, SSO, ID-token, Web3 and anonymous sign-in,
  user retrieval and sign-up, the full session/identity lifecycle
  (`verify-otp`, `refresh-session`, `update-user`, `resend`,
  `reset-password-for-email`, `exchange-code-for-session`, `reauthenticate`,
  `get-claims`, identity linking) and server observability
  (`get-server-health`, `get-server-settings`).

  See `supabase.auth.admin` for the service-role admin API.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth :as auth])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (auth/sign-up c {:email \"user@example.com\" :password \"secure-password\"})
      (auth/sign-in-with-password c {:email \"user@example.com\" :password \"secure-password\"})

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. See https://supabase.com/docs/reference/javascript/auth-api"
  (:require [clojure.string :as str]
            [supabase.auth.jwt :as jwt]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.error :as error]
            [supabase.core.http :as http]))

(def ^:private single-user-uri "/user")
(def ^:private sign-up-uri "/signup")
(def ^:private token-uri "/token")
(def ^:private otp-uri "/otp")
(def ^:private sso-uri "/sso")
(def ^:private authorize-uri "/authorize")
(def ^:private verify-uri "/verify")
(def ^:private resend-uri "/resend")
(def ^:private recover-uri "/recover")
(def ^:private reauthenticate-uri "/reauthenticate")
(def ^:private health-uri "/health")
(def ^:private settings-uri "/settings")
(def ^:private identities-authorize-uri "/user/identities/authorize")
(def ^:private identities-uri "/user/identities")

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

(defn- with-auth
  "Overrides the request's authorization header with a user access token."
  [req access-token]
  (http/with-headers req {"authorization" (str "Bearer " access-token)}))

(defn- token-sign-in [client schema grant-type credentials body]
  (or (client/ensure-client client)
      (specs/ensure-valid schema credentials)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url token-uri)
          (http/with-query {"grant_type" grant-type})
          (http/with-body body)
          (http/execute))))

(defn get-user
  "Retrieves the user profile associated with `access-token`.

  Returns `{:status 200 :body {...}}` with the user payload, or an anomaly
  map on failure (invalid client, expired token, etc.).

  ## Example

      (get-user client \"eyJhbG...\")"
  [client access-token]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url single-user-uri)
          (http/with-headers {"authorization" (str "Bearer " access-token)})
          (http/execute))))

(defn sign-up
  "Creates a new user with email/phone and password.

  ## Parameters

  * `client` — Supabase client
  * `credentials` — sign-up details:
    * `:email` — user's email address (required if `:phone` not provided)
    * `:phone` — user's phone number (required if `:email` not provided)
    * `:password` — user's password (required)
    * `:options` — optional:
      * `:email-redirect-to` — URL to redirect after email confirmation
      * `:data` — additional data attached to the user
      * `:captcha-token` — verification token from CAPTCHA challenge

  ## Example

      (sign-up client {:email \"user@example.com\" :password \"secure-password\"})"
  [client credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/SignUpWithPassword credentials)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url sign-up-uri)
          (http/with-body (snake-keys (select-keys credentials [:email :phone :password])))
          (http/execute))))

(defn sign-in-with-password
  "Authenticates a user with email/phone and password.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:email` — user's email (required if `:phone` not provided)
    * `:phone` — user's phone (required if `:email` not provided)
    * `:password` — user's password (required)
    * `:options` — optional:
      * `:captcha-token` — CAPTCHA challenge token
      * `:data` — additional data

  ## Example

      (sign-in-with-password client {:email \"user@example.com\" :password \"secure-password\"})"
  [client credentials]
  (token-sign-in client specs/SignInWithPassword "password" credentials
                 (snake-keys (select-keys credentials [:email :phone :password]))))

(defn sign-in-with-id-token
  "Signs in with an ID token issued by an external provider.

  Supported providers: `\"google\"`, `\"apple\"`, `\"azure\"`, `\"facebook\"`,
  `\"kakao\"`.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:provider` — provider name (required)
    * `:token` — OIDC ID token issued by the provider (required)
    * `:access-token` — optional, if the ID token contains an `at_hash` claim
    * `:nonce` — optional, if the ID token contains a `nonce` claim
    * `:options` — optional:
      * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (sign-in-with-id-token client {:provider \"google\" :token \"eyJ...\"})"
  [client credentials]
  (token-sign-in client specs/SignInWithIdToken "id_token" credentials
                 (cond-> {:provider (:provider credentials)
                          :id_token (:token credentials)}
                   (:access-token credentials) (assoc :access_token (:access-token credentials))
                   (:nonce credentials)        (assoc :nonce (:nonce credentials)))))

(defn sign-in-with-web3
  "Signs in with a Web3 wallet (Ethereum or Solana).

  The message must be signed client-side; Supabase Auth verifies the signature.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:chain` — `\"ethereum\"` or `\"solana\"` (required)
    * `:message` — SIWE/SIWS message that was signed (required)
    * `:signature` — wallet signature of the message (required)
    * `:options` — optional:
      * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (sign-in-with-web3 client {:chain \"ethereum\"
                                  :message \"example.com wants you to sign in...\"
                                  :signature \"0xabc...\"})"
  [client credentials]
  (token-sign-in client specs/SignInWithWeb3 "web3" credentials
                 (select-keys credentials [:chain :message :signature])))

(defn sign-in-with-otp
  "Sends a one-time password to the user's email or phone.

  The user completes sign-in by calling `verify-otp` with the code.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:email` — user's email (required if `:phone` not provided)
    * `:phone` — user's phone (required if `:email` not provided)
    * `:options` — optional:
      * `:data` — additional data
      * `:email-redirect-to` — URL to redirect after email verification
      * `:captcha-token` — CAPTCHA challenge token
      * `:channel` — `\"sms\"` or `\"whatsapp\"` for phone OTPs
      * `:should-create-user` — create the user if they don't exist

  ## Example

      (sign-in-with-otp client {:email \"user@example.com\"})
      (sign-in-with-otp client {:phone \"+15555550100\"
                                 :options {:channel \"sms\"}})"
  [client credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/SignInWithOTP credentials)
      (let [{:keys [email phone options]} credentials
            {:keys [data channel should-create-user email-redirect-to]} options
            body (cond-> {}
                   email   (assoc :email email)
                   phone   (assoc :phone phone)
                   data    (assoc :data data)
                   channel (assoc :channel channel)
                   (some? should-create-user) (assoc :create_user should-create-user))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :auth-url otp-uri)
            (http/with-query (cond-> {} email-redirect-to (assoc "redirect_to" email-redirect-to)))
            (http/with-body body)
            (http/execute)))))

(defn sign-in-with-sso
  "Initiates SAML single sign-on. Returns a response whose body contains the
  SSO redirect URL.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:provider-id` — SSO provider ID (required if `:domain` not provided)
    * `:domain` — SSO provider domain (required if `:provider-id` not provided)
    * `:options` — optional:
      * `:redirect-to` — URL to redirect after authentication
      * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (sign-in-with-sso client {:domain \"example.org\"})
      (sign-in-with-sso client {:provider-id \"sso-provider-id\"})"
  [client credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/SignInWithSSO credentials)
      (let [{:keys [provider-id domain options]} credentials
            redirect-to (:redirect-to options)
            body (cond-> {}
                   provider-id (assoc :provider_id provider-id)
                   domain      (assoc :domain domain))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :auth-url sso-uri)
            (http/with-query (cond-> {} redirect-to (assoc "redirect_to" redirect-to)))
            (http/with-body body)
            (http/execute)))))

(defn sign-in-anonymously
  "Signs in a user anonymously. Anonymous users can later be converted to
  permanent users by linking an identity.

  ## Parameters

  * `client` — Supabase client
  * `credentials` — optional:
    * `:data` — additional data attached to the user
    * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (sign-in-anonymously client {})
      (sign-in-anonymously client {:data {:locale \"en-US\"}})"
  [client credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/SignInAnonymously credentials)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url sign-up-uri)
          (http/with-body (select-keys credentials [:data]))
          (http/execute))))

(defn- oauth-query [{:keys [provider options]}]
  (let [{:keys [redirect-to scopes query-params]} options]
    (cond-> {"provider" provider}
      redirect-to  (assoc "redirect_to" redirect-to)
      (seq scopes) (assoc "scopes" (str/join " " scopes))
      (seq query-params) (merge (update-keys query-params name)))))

(defn sign-in-with-oauth
  "Builds the OAuth provider redirect URL. Does not issue an HTTP request —
  the caller navigates the user-agent to `:url`.

  Supported providers include `\"github\"`, `\"google\"`, `\"apple\"`, `\"azure\"`,
  `\"discord\"`, `\"facebook\"`, `\"gitlab\"`, `\"linkedin\"`, `\"slack\"`,
  `\"twitch\"`, `\"twitter\"`, and others.

  ## Parameters

  * `client` — Supabase client
  * `credentials`:
    * `:provider` — OAuth provider (required)
    * `:options` — optional:
      * `:redirect-to` — URL to redirect after authentication
      * `:scopes` — vector of OAuth scopes
      * `:query-params` — additional query params for the OAuth URL
      * `:skip-browser-redirect` — skip browser redirect

  ## Example

      (sign-in-with-oauth client
        {:provider \"github\"
         :options {:redirect-to \"https://example.com/callback\"}})
      ;; => {:provider \"github\"
      ;;     :flow-type \"implicit\"
      ;;     :url \"https://abc.supabase.co/auth/v1/authorize?provider=github&redirect_to=...\"}"
  [client credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/SignInWithOAuth credentials)
      (let [base (str (:auth-url client) authorize-uri)
            qs   (->> (oauth-query credentials)
                      (map (fn [[k v]] (str k "=" v)))
                      (str/join "&"))]
        {:provider  (:provider credentials)
         :flow-type (get-in client [:auth :flow-type])
         :url       (str base "?" qs)})))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn verify-otp
  "Verifies an OTP / magic-link token, completing the round-trip started by
  `sign-in-with-otp`, `sign-up`, `resend`, or `reset-password-for-email`.

  Returns `{:status 200 :body {...session...}}` on success.

  ## Parameters

  * `client` — Supabase client
  * `params`:
    * `:type` — one of `\"sms\"`, `\"phone_change\"`, `\"signup\"`, `\"invite\"`,
      `\"magiclink\"`, `\"recovery\"`, `\"email_change\"`, `\"email\"`, `\"phone\"` (required)
    * `:token` — the OTP code (required unless `:token-hash` is given)
    * `:email` / `:phone` — the address the OTP was sent to (required with `:token`)
    * `:token-hash` — hash from an email link (alternative to `:token` + email/phone)
    * `:options`:
      * `:redirect-to` — URL to redirect after verification
      * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (verify-otp client {:type \"email\" :email \"a@b.com\" :token \"123456\"})
      (verify-otp client {:type \"email\" :token-hash \"abc...\"})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/VerifyOtp params)
      (let [{:keys [type email phone token token-hash options]} params
            redirect-to (:redirect-to options)
            body (cond-> {:type type}
                   email      (assoc :email email)
                   phone      (assoc :phone phone)
                   token      (assoc :token token)
                   token-hash (assoc :token_hash token-hash))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :auth-url verify-uri)
            (http/with-query (cond-> {} redirect-to (assoc "redirect_to" redirect-to)))
            (http/with-body body)
            (http/execute)))))

(defn refresh-session
  "Exchanges a refresh token for a fresh session. The caller is responsible
  for storing the returned access + refresh tokens.

  ## Example

      (refresh-session client \"<refresh-token>\")"
  [client refresh-token]
  (or (client/ensure-client client)
      (token-sign-in client [:map] "refresh_token" {}
                     {:refresh_token refresh-token})))

(defn exchange-code-for-session
  "Completes the PKCE flow by exchanging an authorization code for a session.

  ## Parameters

  * `client` — Supabase client
  * `params`:
    * `:auth-code` — code returned to the redirect URL (required)
    * `:code-verifier` — the verifier generated when the flow started (required)

  ## Example

      (exchange-code-for-session client {:auth-code \"abc\" :code-verifier \"xyz\"})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/ExchangeCodeForSession params)
      (token-sign-in client [:map] "pkce" {}
                     {:auth_code (:auth-code params)
                      :code_verifier (:code-verifier params)})))

(defn update-user
  "Updates attributes of the user identified by `access-token`.

  ## Parameters

  * `client` — Supabase client
  * `access-token` — the user's access token
  * `attrs` — at least one of:
    * `:email` — new email address
    * `:phone` — new phone number
    * `:password` — new password
    * `:nonce` — reauthentication nonce (for password change after `reauthenticate`)
    * `:data` — user metadata map

  ## Example

      (update-user client \"<access-token>\" {:password \"new-secret\"})"
  [client access-token attrs]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/UpdateUser attrs)
      (-> (http/request client)
          (http/with-method :put)
          (http/with-service-url :auth-url single-user-uri)
          (with-auth access-token)
          (http/with-body (snake-keys attrs))
          (http/execute))))

(defn resend
  "Resends a signup confirmation or OTP for an existing, unconfirmed sign-up
  or change request.

  ## Parameters

  * `client` — Supabase client
  * `params`:
    * `:type` — `\"signup\"`, `\"email_change\"`, `\"sms\"`, or `\"phone_change\"` (required)
    * `:email` / `:phone` — the destination (one required)
    * `:options`:
      * `:email-redirect-to` — URL to redirect after confirmation
      * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (resend client {:type \"signup\" :email \"a@b.com\"})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/Resend params)
      (let [{:keys [type email phone options]} params
            {:keys [email-redirect-to]} options
            body (cond-> {:type type}
                   email (assoc :email email)
                   phone (assoc :phone phone))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :auth-url resend-uri)
            (http/with-query (cond-> {} email-redirect-to (assoc "redirect_to" email-redirect-to)))
            (http/with-body body)
            (http/execute)))))

(defn reset-password-for-email
  "Sends a password-recovery email. Complete the reset by verifying the
  emailed link (type `\"recovery\"`) and calling `update-user` with the new
  password.

  ## Parameters

  * `client` — Supabase client
  * `email` — the account email (required)
  * `options` (optional):
    * `:redirect-to` — URL the recovery link points to
    * `:captcha-token` — CAPTCHA challenge token

  ## Example

      (reset-password-for-email client \"a@b.com\")
      (reset-password-for-email client \"a@b.com\" {:redirect-to \"https://app/reset\"})"
  ([client email] (reset-password-for-email client email {}))
  ([client email options]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ResetPasswordForEmail {:email email :options options})
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url recover-uri)
           (http/with-query (cond-> {} (:redirect-to options)
                                    (assoc "redirect_to" (:redirect-to options))))
           (http/with-body {:email email})
           (http/execute)))))

(defn reauthenticate
  "Sends a reauthentication nonce (OTP) to the user's email or phone. Used
  before a password change when secure password change is enabled. The nonce
  is then passed to `update-user` as `:nonce`.

  ## Example

      (reauthenticate client \"<access-token>\")"
  [client access-token]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url reauthenticate-uri)
          (with-auth access-token)
          (http/execute))))

(defn get-claims
  "Returns the verified claims of a Supabase JWT.

  For asymmetric algorithms (`RS256`, `ES256`) the signature is verified
  locally against the project's published JWKS (fetched once and cached).
  For the symmetric `HS256` algorithm — which cannot be verified without the
  server secret — this falls back to a `get-user` call so the server
  validates the token.

  Returns `{:claims {...} :header {...}}` on success, or an anomaly on a
  malformed/invalid/expired token.

  ## Example

      (get-claims client \"eyJhbG...\")"
  [client jwt]
  (or (client/ensure-client client)
      (let [decoded (jwt/decode jwt)]
        (if (error/anomaly? decoded)
          decoded
          (let [alg (get-in decoded [:header :alg])]
            (if (jwt/asymmetric-alg? alg)
              (jwt/verify (:auth-url client) jwt)
              ;; HS256 / unknown: let the server validate.
              (let [resp (get-user client jwt)]
                (if (error/anomaly? resp)
                  resp
                  {:claims (:payload decoded) :header (:header decoded)}))))))))

;; ---------------------------------------------------------------------------
;; Identity linking
;; ---------------------------------------------------------------------------

(defn link-identity
  "Begins linking an OAuth identity to the user identified by `access-token`.
  Returns a response whose body contains a `:url` the caller navigates the
  user-agent to in order to authorize the new identity.

  ## Parameters

  * `client` — Supabase client
  * `access-token` — the user's access token
  * `credentials`:
    * `:provider` — OAuth provider (required)
    * `:options`:
      * `:redirect-to` — URL to redirect after authorization
      * `:scopes` — vector of OAuth scopes
      * `:query-params` — additional query params

  ## Example

      (link-identity client \"<access-token>\" {:provider \"github\"})"
  [client access-token credentials]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/LinkIdentity credentials)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url identities-authorize-uri)
          (with-auth access-token)
          (http/with-query (assoc (oauth-query credentials) "skip_http_redirect" "true"))
          (http/execute))))

(defn unlink-identity
  "Unlinks the identity `identity-id` from the user identified by
  `access-token`.

  ## Example

      (unlink-identity client \"<access-token>\" \"<identity-id>\")"
  [client access-token identity-id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (str identities-uri "/" identity-id))
          (with-auth access-token)
          (http/execute))))

(defn get-user-identities
  "Returns the identities linked to the user identified by `access-token`.

  On success returns `{:status 200 :body [...identities...]}`.

  ## Example

      (get-user-identities client \"<access-token>\")"
  [client access-token]
  (let [resp (get-user client access-token)]
    (if (error/anomaly? resp)
      resp
      (assoc resp :body (get-in resp [:body :identities])))))

;; ---------------------------------------------------------------------------
;; Server observability
;; ---------------------------------------------------------------------------

(defn get-server-health
  "Returns the auth server health payload (`GET /health`).

  ## Example

      (get-server-health client)"
  [client]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url health-uri)
          (http/execute))))

(defn get-server-settings
  "Returns the auth server's advertised settings/capabilities (`GET /settings`).

  ## Example

      (get-server-settings client)"
  [client]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url settings-uri)
          (http/execute))))

;; ---------------------------------------------------------------------------
;; Session lifecycle helpers
;; ---------------------------------------------------------------------------

;; A \"session\" here is the token payload the auth server returns from
;; sign-in / `verify-otp` / `refresh-session` — the `:body` of those
;; responses. Keys are accepted in both response form (`:access_token`) and
;; kebab form (`:access-token`).

(defn- session-get [session k]
  (or (get session k)
      (get session (keyword (str/replace (name k) "-" "_")))))

(defn- now-secs []
  (quot (System/currentTimeMillis) 1000))

(defn needs-refresh?
  "True when `session` is expired or expires within `:within` seconds
  (default 300). A session without expiry information never needs refresh.

  ## Example

      (needs-refresh? session)
      (needs-refresh? session {:within 60})"
  ([session] (needs-refresh? session {}))
  ([session {:keys [within] :or {within 300}}]
   (if-let [expires-at (session-get session :expires-at)]
     (<= (- expires-at (now-secs)) within)
     false)))

(defn- refresh-to-session [client session]
  (let [resp (refresh-session client (session-get session :refresh-token))]
    (if (error/anomaly? resp)
      resp
      (:body resp))))

(defn refresh-if-needed
  "Refreshes `session` when it is expired or about to expire, otherwise
  returns it unchanged. Useful for proactive refresh in request handlers.

  Returns the (possibly new) session map, or an anomaly when the refresh
  call fails.

  ## Options

  * `:within` — seconds before expiry that trigger a refresh (default 300)
  * `:force` — refresh regardless of expiry

  ## Example

      (refresh-if-needed client session)
      (refresh-if-needed client session {:within 60})
      (refresh-if-needed client session {:force true})"
  ([client session] (refresh-if-needed client session {}))
  ([client session opts]
   (or (client/ensure-client client)
       (if (or (:force opts) (needs-refresh? session opts))
         (refresh-to-session client session)
         session))))

(defn ensure-valid-session
  "Validates `session` and refreshes it when needed, in one operation.

  Returns a session map that is neither expired nor about to expire, or an
  anomaly: `:cognitect.anomalies/incorrect` when the session is missing its
  tokens, or the refresh call's anomaly when the refresh fails.

  ## Options

  * `:within` — seconds before expiry that trigger a refresh (default 300)

  ## Example

      (let [session (ensure-valid-session client session)]
        (if (error/anomaly? session)
          (redirect-to-login)
          (make-api-call session)))"
  ([client session] (ensure-valid-session client session {}))
  ([client session opts]
   (or (client/ensure-client client)
       (cond
         (not (and (session-get session :access-token)
                   (session-get session :refresh-token)))
         (error/anomaly :cognitect.anomalies/incorrect
                        {:cognitect.anomalies/message "Invalid session: missing tokens"
                         :supabase/service :auth})

         (needs-refresh? session opts)
         (refresh-to-session client session)

         :else session))))
