(ns supabase.auth
  "Authentication and user management against Supabase Auth.

  Supports password, OAuth, OTP, SSO, ID-token, Web3 and anonymous sign-in,
  plus user retrieval and sign-up.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth :as auth])

      (def c (client/make-client \"https://abc.supabase.co\" \"anon-key\"))

      (auth/sign-up c {:email \"user@example.com\" :password \"secure-password\"})
      (auth/sign-in-with-password c {:email \"user@example.com\" :password \"secure-password\"})

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. See https://supabase.com/docs/reference/javascript/auth-api"
  (:require [clojure.string :as str]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private single-user-uri "/user")
(def ^:private sign-up-uri "/signup")
(def ^:private token-uri "/token")
(def ^:private otp-uri "/otp")
(def ^:private sso-uri "/sso")
(def ^:private authorize-uri "/authorize")

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

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
