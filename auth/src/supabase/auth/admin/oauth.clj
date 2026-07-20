(ns supabase.auth.admin.oauth
  "OAuth 2.1 client administration against the Supabase Auth API.

  Manages the OAuth clients registered on the project (dynamic client
  registration), used when the Supabase project acts as an OAuth
  authorization server.

  Like the rest of `supabase.auth.admin`, every function requires a client
  configured with the project's **service-role** key.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth.admin.oauth :as oauth])

      (def c (client/make-client \"https://abc.supabase.co\" \"service-role-key\"))

      (oauth/create-client c {:client-name \"my-app\"
                              :redirect-uris [\"https://app.example.com/cb\"]})
      (oauth/list-clients c)

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure."
  (:require [clojure.string :as str]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private clients-uri "/admin/oauth/clients")

(defn- client-path [& segments]
  (str/join "/" (cons clients-uri segments)))

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

(defn list-clients
  "Lists the registered OAuth clients, paginated.

  ## Parameters

  * `client` — service-role client
  * `opts` (optional): `:page` (1-based) and `:per-page`

  ## Example

      (list-clients client)
      (list-clients client {:page 2 :per-page 50})"
  ([client] (list-clients client {}))
  ([client opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ListOAuthClients opts)
       (-> (http/request client)
           (http/with-method :get)
           (http/with-service-url :auth-url clients-uri)
           (http/with-query (cond-> {}
                              (:page opts)     (assoc "page" (:page opts))
                              (:per-page opts) (assoc "per_page" (:per-page opts))))
           (http/execute)))))

(defn create-client
  "Registers a new OAuth client. The response body carries the generated
  `:client_id` and — shown only once — the `:client_secret`.

  ## Parameters

  * `client` — service-role client
  * `params`:
    * `:client-name` — human-readable name (required)
    * `:redirect-uris` — allowed redirect URIs, at least one (required)
    * `:client-uri` — homepage of the client application
    * `:grant-types` — e.g. `[\"authorization_code\" \"refresh_token\"]`
    * `:response-types` — e.g. `[\"code\"]`
    * `:scope` — space-separated scope string
    * `:token-endpoint-auth-method` — e.g. `\"client_secret_basic\"`

  ## Example

      (create-client client {:client-name \"my-app\"
                             :redirect-uris [\"https://app.example.com/cb\"]})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/CreateOAuthClient params)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url clients-uri)
          (http/with-body (snake-keys params))
          (http/execute))))

(defn get-client
  "Fetches the OAuth client `client-id`.

  ## Example

      (get-client client \"<client-id>\")"
  [client client-id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url (client-path client-id))
          (http/execute))))

(defn update-client
  "Updates the OAuth client `client-id` with `params` — any of
  `:client-name`, `:client-uri`, `:logo-uri`, `:redirect-uris`,
  `:grant-types`, `:token-endpoint-auth-method`.

  ## Example

      (update-client client \"<client-id>\" {:client-name \"renamed\"})"
  [client client-id params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/UpdateOAuthClient params)
      (-> (http/request client)
          (http/with-method :put)
          (http/with-service-url :auth-url (client-path client-id))
          (http/with-body (snake-keys params))
          (http/execute))))

(defn delete-client
  "Deletes the OAuth client `client-id`. Permanent; tokens issued to it stop
  working.

  ## Example

      (delete-client client \"<client-id>\")"
  [client client-id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (client-path client-id))
          (http/execute))))

(defn regenerate-client-secret
  "Regenerates the secret for OAuth client `client-id`. The old secret stops
  working immediately; the new one is in the response body, shown only once.

  ## Example

      (regenerate-client-secret client \"<client-id>\")"
  [client client-id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (client-path client-id "regenerate_secret"))
          (http/execute))))
