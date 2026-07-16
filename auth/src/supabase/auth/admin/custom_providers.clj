(ns supabase.auth.admin.custom-providers
  "Custom OAuth2 / OIDC provider administration against the Supabase Auth API.

  Registers and manages identity providers beyond the built-in social login
  list, e.g. a private OIDC issuer or a partner's OAuth2 server.

  Like the rest of `supabase.auth.admin`, every function requires a client
  configured with the project's **service-role** key.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth.admin.custom-providers :as providers])

      (def c (client/make-client \"https://abc.supabase.co\" \"service-role-key\"))

      (providers/create-provider c {:provider-type \"oidc\"
                                    :identifier \"acme\"
                                    :name \"Acme SSO\"
                                    :client-id \"client-id\"
                                    :client-secret \"client-secret\"
                                    :discovery-url \"https://sso.acme.com/.well-known/openid-configuration\"})
      (providers/list-providers c)

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure."
  (:require [clojure.string :as str]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private providers-uri "/admin/custom-providers")

(defn- provider-path [& segments]
  (str/join "/" (cons providers-uri segments)))

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

(defn list-providers
  "Lists the registered custom providers. Pass `:type` (`\"oauth2\"` or
  `\"oidc\"`) to filter.

  ## Example

      (list-providers client)
      (list-providers client {:type \"oidc\"})"
  ([client] (list-providers client {}))
  ([client opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ListCustomProviders opts)
       (-> (http/request client)
           (http/with-method :get)
           (http/with-service-url :auth-url providers-uri)
           (http/with-query (cond-> {} (:type opts) (assoc "type" (:type opts))))
           (http/execute)))))

(defn create-provider
  "Registers a new custom provider.

  ## Parameters

  * `client` — service-role client
  * `params`:
    * `:provider-type` — `\"oauth2\"` or `\"oidc\"` (required)
    * `:identifier` — unique slug used in sign-in URLs (required)
    * `:name` — display name (required)
    * `:client-id` / `:client-secret` — credentials at the provider (required)
    * `:discovery-url` — OIDC discovery document (oidc)
    * `:issuer` — expected `iss` claim (oidc)
    * `:authorization-url` / `:token-url` / `:userinfo-url` / `:jwks-uri` —
      explicit endpoints (oauth2, or oidc without discovery)
    * `:acceptable-client-ids`, `:scopes`, `:pkce-enabled`,
      `:attribute-mapping`, `:authorization-params`, `:enabled`,
      `:email-optional`, `:skip-nonce-check` — optional behavior tuning

  ## Example

      (create-provider client {:provider-type \"oidc\" :identifier \"acme\"
                               :name \"Acme SSO\" :client-id \"id\"
                               :client-secret \"secret\"})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/CreateCustomProvider params)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url providers-uri)
          (http/with-body (snake-keys params))
          (http/execute))))

(defn get-provider
  "Fetches the custom provider registered under `identifier`.

  ## Example

      (get-provider client \"acme\")"
  [client identifier]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url (provider-path identifier))
          (http/execute))))

(defn update-provider
  "Updates the custom provider `identifier` with `params` — same keys as
  `create-provider` except `:provider-type` and `:identifier`, which are
  immutable.

  ## Example

      (update-provider client \"acme\" {:enabled false})"
  [client identifier params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/UpdateCustomProvider params)
      (-> (http/request client)
          (http/with-method :put)
          (http/with-service-url :auth-url (provider-path identifier))
          (http/with-body (snake-keys params))
          (http/execute))))

(defn delete-provider
  "Deletes the custom provider `identifier`. Permanent; sign-ins through it
  stop working.

  ## Example

      (delete-provider client \"acme\")"
  [client identifier]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (provider-path identifier))
          (http/execute))))
