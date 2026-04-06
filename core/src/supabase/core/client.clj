(ns supabase.core.client
  "Client configuration for interacting with Supabase.

  Creates and manages immutable client maps containing connection options,
  service URLs, and configuration for your Supabase project. The client is
  a plain Clojure map validated with Malli schemas.

  ## Usage

      (require '[supabase.core.client :as client])

      ;; Create a client with defaults
      (client/make-client \"https://abc.supabase.co\" \"my-api-key\")

      ;; Create a client with options
      (client/make-client \"https://abc.supabase.co\" \"my-api-key\"
        :db {:schema \"another\"}
        :auth {:flow-type \"pkce\"}
        :global {:headers {\"custom-header\" \"custom-value\"}})

      ;; Update the access token for authenticated requests
      (client/update-access-token client \"new-token\")

  ## Client Map Structure

      {:base-url       \"https://abc.supabase.co\"
       :api-key        \"my-api-key\"
       :access-token   \"my-api-key\"
       :auth-url       \"https://abc.supabase.co/auth/v1\"
       :database-url   \"https://abc.supabase.co/rest/v1\"
       :storage-url    \"https://abc.supabase.co/storage/v1\"
       :functions-url  \"https://abc.supabase.co/functions/v1\"
       :realtime-url   \"https://abc.supabase.co/realtime/v1\"
       :db             {:schema \"public\"}
       :global         {:headers {\"x-client-info\" \"supabase-clj/0.1.0\"}}
       :auth           {:auto-refresh-token true, :flow-type \"implicit\", ...}
       :storage        {:use-new-hostname false}}

  See https://supabase.com/docs/reference/javascript/initializing"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [supabase.core.error :as error]))

;; x-release-please-start-version
(def ^:private version "0.1.1")
;; x-release-please-end

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def Database
  "Schema for database configuration. Defaults to the `\"public\"` schema."
  (m/schema [:map [:schema {:default "public"} :string]]))

(def Global
  "Schema for global client options, including default HTTP headers."
  (m/schema [:map [:headers {:default {}} [:map-of :string :string]]]))

(def flow-type
  "Schema for supported auth flow types."
  (m/schema [:enum "implicit" "pkce" "magicLink"]))

(def Auth
  "Schema for auth configuration options."
  (m/schema [:map
             {:closed true}
             [:auto-refresh-token {:default true} :boolean]
             [:debug {:default false} :boolean]
             [:detect-session-in-url {:default true} :boolean]
             [:flow-type {:default "implicit"} #'flow-type]
             [:persist-session {:default true} :boolean]
             [:storage-key {:optional true} [:maybe :string]]]))

(def Storage
  "Schema for storage configuration options."
  (m/schema [:map [:use-new-hostname {:default false} :boolean]]))

(def Client
  "Schema for the full Supabase client map."
  (m/schema [:map
             {:closed true}
             [:api-key :string]
             [:base-url :string]
             [:access-token :string]
             [:db #'Database]
             [:global #'Global]
             [:auth #'Auth]
             [:storage #'Storage]
             [:auth-url :string]
             [:functions-url :string]
             [:storage-url :string]
             [:database-url :string]
             [:realtime-url :string]]))

;; ---------------------------------------------------------------------------
;; Storage hostname transformation
;; ---------------------------------------------------------------------------

(def ^:private supabase-domain-re #"\.supabase\.(co|in|red)$")
(def ^:private storage-subdomain-re #"storage\.supabase\.")

(defn- supabase-domain? [hostname]
  (boolean (re-find supabase-domain-re hostname)))

(defn- has-storage-subdomain? [hostname]
  (boolean (re-find storage-subdomain-re hostname)))

(defn- transform-hostname [hostname]
  (str/replace-first hostname "supabase." "storage.supabase."))

(defn transform-storage-url
  "Transforms a storage URL to use the storage subdomain for official Supabase domains.

  Official domains (`.supabase.co`, `.supabase.in`, `.supabase.red`) are rewritten
  to route through `storage.supabase.*`. Custom domains, localhost, IPs, and URLs
  already using the storage subdomain are returned unchanged.

      (transform-storage-url \"https://abc.supabase.co/storage/v1\")
      ;; => \"https://abc.storage.supabase.co/storage/v1\"

      (transform-storage-url \"https://custom.example.com/storage/v1\")
      ;; => \"https://custom.example.com/storage/v1\""
  [storage-url]
  (when (and (string? storage-url) (seq storage-url))
    (let [uri (java.net.URI. storage-url)
          host (.getHost uri)]
      (if (and host (supabase-domain? host) (not (has-storage-subdomain? host)))
        (str (.getScheme uri) "://" (transform-hostname host)
             (when-let [port (.getPort uri)] (when (pos? port) (str ":" port)))
             (.getRawPath uri)
             (when-let [q (.getRawQuery uri)] (str "?" q))
             (when-let [f (.getRawFragment uri)] (str "#" f)))
        storage-url))))

(defn- maybe-transform-storage-url [storage-url opts]
  (if (get-in opts [:storage :use-new-hostname])
    (or (transform-storage-url storage-url) storage-url)
    storage-url))

;; ---------------------------------------------------------------------------
;; Default helpers
;; ---------------------------------------------------------------------------

(defn- default-storage-key
  "Derives the default auth storage key from the base URL.
  e.g. \"https://abc123.supabase.co\" => \"sb-abc123-auth-token\""
  [base-url]
  (let [host (.getHost (java.net.URI. base-url))
        prefix (first (str/split host #"\." 2))]
    (str "sb-" prefix "-auth-token")))

(defn- default-headers []
  {"x-client-info" (str "supabase-clj/" version)})

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn make-client
  "Creates a new Supabase client map from a base URL and API key.

  The client is a plain immutable map containing all configuration needed to
  interact with Supabase services. Service URLs are derived from `base-url`.
  By default, the `access-token` is set to the `api-key`.

  Returns the client map on success, or an anomaly map on validation failure.

  ## Options (keyword args)

    - `:access-token`  — override the default access token (defaults to `api-key`)
    - `:db`            — database options, e.g. `{:schema \"another\"}`
    - `:global`        — global options, e.g. `{:headers {\"x-custom\" \"val\"}}`
    - `:auth`          — auth options, e.g. `{:flow-type \"pkce\"}`
    - `:storage`       — storage options, e.g. `{:use-new-hostname true}`

  ## Examples

      (make-client \"https://abc.supabase.co\" \"my-key\")

      (make-client \"https://abc.supabase.co\" \"my-key\"
        :db {:schema \"private\"}
        :auth {:flow-type \"pkce\" :debug true})"
  [base-url api-key & {:as opts}]
  (let [opts (or opts {})
        access-token (or (:access-token opts) api-key)
        storage-url (maybe-transform-storage-url
                     (str base-url "/storage/v1") opts)
        storage-key (or (get-in opts [:auth :storage-key])
                        (default-storage-key base-url))
        user-headers (get-in opts [:global :headers] {})
        merged-headers (merge (default-headers) user-headers)
        client-map (merge {:db (or (:db opts) {})
                           :storage (or (:storage opts) {})}
                          opts
                          {:api-key api-key
                           :base-url base-url
                           :access-token access-token
                           :auth-url (str base-url "/auth/v1")
                           :realtime-url (str base-url "/realtime/v1")
                           :functions-url (str base-url "/functions/v1")
                           :database-url (str base-url "/rest/v1")
                           :storage-url storage-url}
                          {:global {:headers merged-headers}}
                          {:auth (assoc (or (:auth opts) {})
                                        :storage-key storage-key)})
        decoded (m/decode Client client-map mt/default-value-transformer)]
    (if (m/validate Client decoded)
      decoded
      (error/anomaly :cognitect.anomalies/incorrect
                     {:cognitect.anomalies/message "Invalid client configuration"
                      :malli/explanation (m/explain Client decoded)}))))

(defn update-access-token
  "Returns a new client with the given `access-token` swapped in.

  Returns an anomaly map if `client` is not a valid client map.

      (update-access-token client \"new-jwt-token\")"
  [client access-token]
  (if (m/validate Client client)
    (assoc client :access-token access-token)
    (error/anomaly :cognitect.anomalies/incorrect
                   {:cognitect.anomalies/message "Invalid client map"})))
