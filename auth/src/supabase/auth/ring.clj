(ns supabase.auth.ring
  "Ring middleware for Supabase Auth JWT verification.

  Verifies the bearer token on incoming requests via
  [[supabase.auth/get-claims]] (local JWKS verification for asymmetric
  algorithms, server-side check for HS256) and attaches the verified claims
  to the request map. Depends only on the Ring request/response map shape,
  not on Ring itself.

  ## Example

      (require '[supabase.auth.ring :as auth.ring])

      (def app
        (-> handler
            (auth.ring/wrap-authentication client {:required? true})))

      ;; downstream handlers read the verified identity:
      (defn handler [request]
        (let [user-id (get-in request [:supabase/claims :sub])]
          ...))"
  (:require [clojure.string :as str]
            [supabase.auth :as auth]
            [supabase.core.error :as error]))

(defn bearer-token
  "Extracts the bearer token from a Ring request's `authorization` header.
  Returns nil when the header is absent or uses another scheme."
  [request]
  (when-let [header (get-in request [:headers "authorization"])]
    (let [[scheme token] (str/split header #"\s+" 2)]
      (when (and token (= "bearer" (str/lower-case scheme)))
        token))))

(defn- default-on-unauthenticated [_request]
  {:status 401
   :headers {"content-type" "application/json"}
   :body "{\"error\":\"unauthorized\"}"})

(defn- authenticate
  "Returns `{:request <req with claims>}` on success, `{:response <401>}`
  when authentication is required and fails, `{:request <req unchanged>}`
  otherwise."
  [client request {:keys [required? on-unauthenticated]
                   :or {on-unauthenticated default-on-unauthenticated}}]
  (let [token (bearer-token request)
        claims (when token (auth/get-claims client token))
        ok? (and claims (not (error/anomaly? claims)))]
    (cond
      ok? {:request (assoc request
                           :supabase/token token
                           :supabase/claims (:claims claims))}
      required? {:response (on-unauthenticated request)}
      :else {:request request})))

(defn wrap-authentication
  "Middleware that verifies the request's bearer token against Supabase
  Auth and assocs the result onto the request:

    * `:supabase/claims` — the verified JWT claims map
    * `:supabase/token`  — the raw access token

  Without a (valid) token the request passes through untouched, unless
  `:required?` is set, in which case the middleware short-circuits with a
  401 response.

  Supports both sync and async Ring handlers.

  ## Options

  * `:required?` — reject unauthenticated requests (default false)
  * `:on-unauthenticated` — fn of the request returning the rejection
    response (default: plain 401)

  ## Example

      ;; optional auth: handler decides per route
      (wrap-authentication handler client)

      ;; hard requirement with custom rejection
      (wrap-authentication handler client
                           {:required? true
                            :on-unauthenticated (fn [_] {:status 302
                                                         :headers {\"location\" \"/login\"}
                                                         :body \"\"})})"
  ([handler client] (wrap-authentication handler client {}))
  ([handler client opts]
   (fn
     ([request]
      (let [{:keys [request response]} (authenticate client request opts)]
        (or response (handler request))))
     ([request respond raise]
      (let [{:keys [request response]} (authenticate client request opts)]
        (if response
          (respond response)
          (handler request respond raise)))))))
