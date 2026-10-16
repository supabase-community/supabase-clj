(ns supabase.auth.admin.passkey
  "Passkey administration against the Supabase Auth API.

  Manages the passkeys registered by users, used when the Supabase project
  has the passkeys (WebAuthn) authentication method enabled.

  Like the rest of `supabase.auth.admin`, every function requires a client
  configured with the project's **service-role** key.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth.admin.passkey :as passkey])

      (def c (client/make-client \"https://abc.supabase.co\" \"service-role-key\"))

      (passkey/list-passkeys c \"<user-id>\")
      (passkey/delete-passkey c \"<user-id>\" \"<passkey-id>\")

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure."
  (:require [clojure.string :as str]
            [supabase.auth.errors :as errors]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private users-uri "/admin/users")

(defn- users-path
  ([] users-uri)
  ([id & segments] (str/join "/" (into [users-uri id] segments))))

(defn list-passkeys
  "Lists the passkeys registered for user `user-id`. The response body is a
  vector of passkeys — see `supabase.auth.specs/Passkey` for the shape.

  ## Example

      (list-passkeys client \"<user-id>\")"
  [client user-id]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/UUIDString user-id)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url (users-path user-id "passkeys"))
          (errors/with-auth-errors)
          (http/execute))))

(defn delete-passkey
  "Deletes the passkey `passkey-id` of user `user-id`. Permanent; the
  credential can no longer sign in.

  ## Example

      (delete-passkey client \"<user-id>\" \"<passkey-id>\")"
  [client user-id passkey-id]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/UUIDString user-id)
      (specs/ensure-valid specs/UUIDString passkey-id)
      (-> (http/request client)
          (http/with-method :delete)
          (http/with-service-url :auth-url (users-path user-id "passkeys" passkey-id))
          (errors/with-auth-errors)
          (http/execute))))
