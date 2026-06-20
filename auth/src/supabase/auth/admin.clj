(ns supabase.auth.admin
  "Server-side admin operations against the Supabase Auth API.

  Every function here requires a `client` configured with the project's
  **service-role** key (not the anon key) as its access token — these
  endpoints bypass Row-Level Security and must never be exposed to a browser.

  ## Example

      (require '[supabase.core.client :as client]
               '[supabase.auth.admin :as admin])

      (def c (client/make-client \"https://abc.supabase.co\" \"service-role-key\"))

      (admin/create-user c {:email \"user@example.com\" :password \"secret\"
                            :email-confirm true})
      (admin/list-users c {:page 1 :per-page 50})

  Each function returns `{:status :body :headers}` on success or an anomaly
  map on failure. See https://supabase.com/docs/reference/javascript/auth-admin-api"
  (:require [clojure.string :as str]
            [supabase.auth.specs :as specs]
            [supabase.core.client :as client]
            [supabase.core.http :as http]))

(def ^:private invite-uri "/invite")
(def ^:private generate-link-uri "/admin/generate_link")
(def ^:private users-uri "/admin/users")
(def ^:private logout-uri "/logout")

(defn- snake-keys [m]
  (when m
    (update-keys m #(-> % name (str/replace "-" "_") keyword))))

(defn- users-path
  ([] users-uri)
  ([id] (str users-uri "/" id)))

(defn invite-user-by-email
  "Sends an invite email to `email`, creating the user in an unconfirmed state.

  ## Parameters

  * `client` — service-role client
  * `email` — invitee address (required)
  * `options` (optional):
    * `:data` — user metadata attached to the new user
    * `:redirect-to` — URL the invite link points to

  ## Example

      (invite-user-by-email client \"new@example.com\" {:data {:role \"member\"}})"
  ([client email] (invite-user-by-email client email {}))
  ([client email options]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/InviteUser {:email email :options options})
       (let [body (cond-> {:email email}
                    (:data options) (assoc :data (:data options)))]
         (-> (http/request client)
             (http/with-method :post)
             (http/with-service-url :auth-url invite-uri)
             (http/with-query (cond-> {} (:redirect-to options)
                                      (assoc "redirect_to" (:redirect-to options))))
             (http/with-body body)
             (http/execute))))))

(defn generate-link
  "Generates an email action link (and the underlying user) without sending
  an email, so the caller can deliver it through its own channel.

  ## Parameters

  * `client` — service-role client
  * `params`:
    * `:type` — `\"signup\"`, `\"invite\"`, `\"magiclink\"`, `\"recovery\"`,
      `\"email_change_current\"`, or `\"email_change_new\"` (required)
    * `:email` — target email (required)
    * `:password` — required for `\"signup\"`
    * `:new-email` — required for the email-change types
    * `:data` — user metadata (signup/invite only)
    * `:options`:
      * `:redirect-to` — URL the link points to

  ## Example

      (generate-link client {:type \"signup\" :email \"a@b.com\" :password \"secret\"})"
  [client params]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/GenerateLink params)
      (let [{:keys [options]} params
            body (-> (dissoc params :options)
                     snake-keys
                     (cond-> (:redirect-to options)
                       (assoc :redirect_to (:redirect-to options))))]
        (-> (http/request client)
            (http/with-method :post)
            (http/with-service-url :auth-url generate-link-uri)
            (http/with-body body)
            (http/execute)))))

(defn create-user
  "Creates a user directly, bypassing sign-up flows. Set `:email-confirm` /
  `:phone-confirm` to mark contacts as already verified.

  ## Parameters

  * `client` — service-role client
  * `attrs` — any of `:email`, `:phone`, `:password`, `:email-confirm`,
    `:phone-confirm`, `:user-metadata`, `:app-metadata`, `:ban-duration`,
    `:role`

  ## Example

      (create-user client {:email \"a@b.com\" :password \"secret\" :email-confirm true})"
  [client attrs]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/AdminCreateUser attrs)
      (-> (http/request client)
          (http/with-method :post)
          (http/with-service-url :auth-url (users-path))
          (http/with-body (snake-keys attrs))
          (http/execute))))

(defn list-users
  "Lists users, paginated. The `link` response header carries next/prev page
  cursors.

  ## Parameters

  * `client` — service-role client
  * `opts` (optional): `:page` (1-based) and `:per-page`

  ## Example

      (list-users client)
      (list-users client {:page 2 :per-page 100})"
  ([client] (list-users client {}))
  ([client opts]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/ListUsers opts)
       (-> (http/request client)
           (http/with-method :get)
           (http/with-service-url :auth-url (users-path))
           (http/with-query (cond-> {}
                              (:page opts)     (assoc "page" (:page opts))
                              (:per-page opts) (assoc "per_page" (:per-page opts))))
           (http/execute)))))

(defn get-user-by-id
  "Fetches a single user by `id`.

  ## Example

      (get-user-by-id client \"<user-id>\")"
  [client id]
  (or (client/ensure-client client)
      (-> (http/request client)
          (http/with-method :get)
          (http/with-service-url :auth-url (users-path id))
          (http/execute))))

(defn update-user-by-id
  "Updates the user `id` with `attrs` (same keys as `create-user`, plus
  `:nonce`).

  ## Example

      (update-user-by-id client \"<user-id>\" {:role \"admin\"})"
  [client id attrs]
  (or (client/ensure-client client)
      (specs/ensure-valid specs/AdminUpdateUser attrs)
      (-> (http/request client)
          (http/with-method :put)
          (http/with-service-url :auth-url (users-path id))
          (http/with-body (snake-keys attrs))
          (http/execute))))

(defn delete-user
  "Deletes the user `id`. Pass `:soft? true` to soft-delete (retain the row,
  marked deleted) instead of a hard delete.

  ## Example

      (delete-user client \"<user-id>\")
      (delete-user client \"<user-id>\" {:soft? true})"
  ([client id] (delete-user client id {}))
  ([client id {:keys [soft?]}]
   (or (client/ensure-client client)
       (-> (http/request client)
           (http/with-method :delete)
           (http/with-service-url :auth-url (users-path id))
           (http/with-body {:should_soft_delete (boolean soft?)})
           (http/execute)))))

(defn sign-out
  "Revokes sessions for the user identified by `access-token`.

  `scope` selects which sessions to revoke:

    - `\"global\"` (default) — all sessions
    - `\"local\"`            — only the current session
    - `\"others\"`          — all sessions except the current one

  ## Example

      (sign-out client \"<access-token>\")
      (sign-out client \"<access-token>\" \"others\")"
  ([client access-token] (sign-out client access-token "global"))
  ([client access-token scope]
   (or (client/ensure-client client)
       (specs/ensure-valid specs/SignOutScope scope)
       (-> (http/request client)
           (http/with-method :post)
           (http/with-service-url :auth-url logout-uri)
           (http/with-headers {"authorization" (str "Bearer " access-token)})
           (http/with-query {"scope" scope})
           (http/execute)))))
