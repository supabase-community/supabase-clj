(ns supabase.auth.specs
  "Malli schemas for Supabase Auth entities and operations.

  Used internally to validate API responses and operation inputs.
  Schemas follow the Supabase Auth API specification:
  https://supabase.com/docs/reference/javascript/auth-api"
  (:require [malli.core :as m]
            [supabase.core.error :as error]))

(def Factor
  "Schema for a multi-factor authentication method associated with a user.
  See https://supabase.com/docs/guides/auth/auth-mfa"
  (m/schema [:map
             {:closed true}
             [:id :string]
             [:friendly-name {:optional true} [:maybe :string]]
             [:factor-type [:enum "totp"]]
             [:status [:enum "verified" "unverified"]]
             [:created-at :string]
             [:updated-at :string]]))

(def ^:private Provider
  (m/schema [:enum "apple" "azure" "bitbucket" "discord" "email" "facebook" "figma" "github" "gitlab" "google" "kakao" "keycloak" "linkedin" "linkedin_oidc" "notion" "phone" "slack" "spotify" "twitch" "twitter" "workos" "zoom" "fly"]))

(def Identity
  (m/schema [:map
             {:closed true}
             [:id :string]
             [:user-id :string]
             [:identity-data {:optional true} [:maybe :map]]
             [:provider #'Provider]
             [:last-sign-in-at {:optional true} [:maybe :string]]
             [:created-at :string]
             [:updated-at :string]]))

(def User
  (m/schema [:map
             {:closed true}
             [:id :string]
             [:app-metadata :map]
             [:user-metadata {:optional true} :map]
             [:aud :string]
             [:confirmation-sent-at {:optional true} [:maybe :string]]
             [:recovery-sent-at {:optional true} [:maybe :string]]
             [:email-change-sent-at {:optional true} [:maybe :string]]
             [:new-email {:optional true} [:maybe :string]]
             [:new-phone {:optional true} [:maybe :string]]
             [:invited-at {:optional true} [:maybe :string]]
             [:action-link {:optional true} [:maybe :string]]
             [:email {:optional true} [:maybe :string]]
             [:phone {:optional true} [:maybe :string]]
             [:confirmed-at {:optional true} [:maybe :string]]
             [:email-confirmed-at {:optional true} [:maybe :string]]
             [:phone-confirmed-at {:optional true} [:maybe :string]]
             [:last-sign-in-at {:optional true} [:maybe :string]]
             [:encrypted-password {:optional true} [:maybe :string]]
             [:factors {:optional true} [:maybe [:vector #'Factor]]]
             [:identities {:optional true} [:maybe [:vector #'Identity]]]
             [:role {:optional true} [:maybe :string]]
             [:is-anonymous {:optional true} [:maybe :boolean]]
             [:created-at :string]
             [:updated-at {:optional true} [:maybe :string]]]))

(def Session
  "Schema for an authenticated session returned after sign-in/sign-up.
  See https://supabase.com/docs/reference/javascript/auth-getsession"
  (m/schema [:map
             {:closed true}
             [:access-token :string]
             [:refresh-token :string]
             [:expires-in :int]
             [:expires-at {:optional true} [:maybe :int]]
             [:token-type :string]
             [:provider-token {:optional true} [:maybe :string]]
             [:provider-refresh-token {:optional true} [:maybe :string]]
             [:user {:optional true} [:maybe #'User]]]))

(def ^:private email-or-phone
  [:fn {:error/message "at least email or phone must be provided"}
   (fn [{:keys [email phone]}]
     (or (some? email) (some? phone)))])

(def SignInWithPassword
  "Schema for email/phone + password authentication.
  See https://supabase.com/docs/reference/javascript/auth-signinwithpassword"
  (m/schema [:and
             [:map
              {:closed true}
              [:email {:optional true} [:maybe :string]]
              [:phone {:optional true} [:maybe :string]]
              [:password :string]
              [:options {:optional true} [:maybe [:map
                                                  {:closed true}
                                                  [:data {:optional true} [:maybe :map]]
                                                  [:captcha-token {:optional true} [:maybe :string]]]]]]
             email-or-phone]))

(def SignUpWithPassword
  "Schema for creating a new user with email/phone + password.
  See https://supabase.com/docs/reference/javascript/auth-signup"
  (m/schema [:and
             [:map
              {:closed true}
              [:email {:optional true} [:maybe :string]]
              [:phone {:optional true} [:maybe :string]]
              [:password :string]
              [:options {:optional true} [:maybe [:map
                                                  {:closed true}
                                                  [:email-redirect-to {:optional true} [:maybe :string]]
                                                  [:data {:optional true} [:maybe :map]]
                                                  [:captcha-token {:optional true} [:maybe :string]]]]]]
             email-or-phone]))

(def SignInWithOTP
  "Schema for passwordless OTP sign-in via email or phone.
  See https://supabase.com/docs/reference/javascript/auth-signinwithotp"
  (m/schema [:and
             [:map
              {:closed true}
              [:email {:optional true} [:maybe :string]]
              [:phone {:optional true} [:maybe :string]]
              [:options {:optional true} [:maybe [:map
                                                  {:closed true}
                                                  [:data {:optional true} [:maybe :map]]
                                                  [:email-redirect-to {:optional true} [:maybe :string]]
                                                  [:captcha-token {:optional true} [:maybe :string]]
                                                  [:channel {:optional true} [:enum "sms" "whatsapp"]]
                                                  [:should-create-user {:optional true} :boolean]]]]]
             email-or-phone]))

(def SignInWithOAuth
  "Schema for OAuth provider sign-in.
  See https://supabase.com/docs/reference/javascript/auth-signinwithoauth"
  (m/schema [:map
             {:closed true}
             [:provider #'Provider]
             [:options {:optional true} [:maybe [:map
                                                 {:closed true}
                                                 [:redirect-to {:optional true} [:maybe :string]]
                                                 [:scopes {:optional true} [:maybe [:vector :string]]]
                                                 [:query-params {:optional true} [:maybe :map]]
                                                 [:skip-browser-redirect {:optional true} :boolean]]]]]))

(def ^:private IdTokenProvider
  (m/schema [:enum "google" "apple" "azure" "facebook" "kakao"]))

(def SignInWithIdToken
  "Schema for sign-in using a third-party ID token.
  See https://supabase.com/docs/reference/javascript/auth-signinwithidtoken"
  (m/schema [:map
             {:closed true}
             [:provider #'IdTokenProvider]
             [:token :string]
             [:access-token {:optional true} [:maybe :string]]
             [:nonce {:optional true} [:maybe :string]]
             [:options {:optional true} [:maybe [:map
                                                 {:closed true}
                                                 [:captcha-token {:optional true} [:maybe :string]]]]]]))

(def SignInWithSSO
  "Schema for single sign-on via SAML.
  See https://supabase.com/docs/reference/javascript/auth-signinwithsso"
  (m/schema [:and
             [:map
              {:closed true}
              [:provider-id {:optional true} [:maybe :string]]
              [:domain {:optional true} [:maybe :string]]
              [:options {:optional true} [:maybe [:map
                                                  {:closed true}
                                                  [:redirect-to {:optional true} [:maybe :string]]
                                                  [:captcha-token {:optional true} [:maybe :string]]]]]]
             [:fn {:error/message "at least provider-id or domain must be provided"}
              (fn [{:keys [provider-id domain]}]
                (or (some? provider-id) (some? domain)))]]))

(def SignInWithWeb3
  "Schema for Web3/wallet-based authentication.
  See https://supabase.com/docs/guides/auth/social-login/auth-web3"
  (m/schema [:map
             {:closed true}
             [:chain [:enum "ethereum" "solana"]]
             [:message :string]
             [:signature :string]
             [:options {:optional true} [:maybe [:map
                                                 {:closed true}
                                                 [:captcha-token {:optional true} [:maybe :string]]]]]]))

(def SignInAnonymously
  "Schema for anonymous sign-in. Anonymous users can later be converted
  to permanent users by linking an identity.
  See https://supabase.com/docs/reference/javascript/auth-signinanonymously"
  (m/schema [:map
             {:closed true}
             [:data {:optional true} [:maybe :map]]
             [:captcha-token {:optional true} [:maybe :string]]]))

(def SignInRequest
  "Schema for the final Supabase Auth API request body, built from a sign-in schema.
  Used internally to encode the HTTP request sent to the auth server."
  (m/schema [:map
             [:email {:optional true} [:maybe :string]]
             [:phone {:optional true} [:maybe :string]]
             [:password {:optional true} [:maybe :string]]
             [:provider {:optional true} [:maybe :string]]
             [:access-token {:optional true} [:maybe :string]]
             [:nonce {:optional true} [:maybe :string]]
             [:id-token {:optional true} [:maybe :string]]
             [:provider-id {:optional true} [:maybe :string]]
             [:domain {:optional true} [:maybe :string]]
             [:create-user {:optional true} [:maybe :boolean]]
             [:redirect-to {:optional true} [:maybe :string]]
             [:channel {:optional true} [:maybe :string]]
             [:data {:optional true} [:maybe :map]]
             [:chain {:optional true} [:maybe :string]]
             [:message {:optional true} [:maybe :string]]
             [:signature {:optional true} [:maybe :string]]
             [:code-challenge {:optional true} [:maybe :string]]
             [:code-challenge-method {:optional true} [:maybe :string]]
             [:gotrue-meta-security {:optional true}
              [:maybe [:map
                       {:closed true}
                       [:captcha-token {:optional true} [:maybe :string]]]]]]))

(defn ensure-valid
  "Returns nil if `value` matches `schema`, otherwise an anomaly carrying the malli explanation."
  [schema value]
  (when-not (m/validate schema value)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid input"
                    :malli/explanation (m/explain schema value)
                    :supabase/service :auth})))
