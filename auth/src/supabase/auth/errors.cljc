(ns supabase.auth.errors
  "Auth-specific HTTP error mapping.

  Installed on every Auth request via [[with-auth-errors]]. Parity with the
  auth-js `handleError` logic:

    * 5xx responses keep the server's own message (the body's `msg`,
      `message`, `error_description` or `error` field) instead of a generic
      status phrase.
    * Weak-password rejections - error code `weak_password` (as `:code` or
      `:error_code`), or the legacy `:weak_password` body shape - are tagged
      `:supabase/code :weak-password` and carry the failing rules under
      `:auth/weak-password-reasons`."
  (:require [supabase.core.error :as error]
            [supabase.core.http :as http]))

(defn- body-message
  "Extracts the server's human-readable message from an error body, in the
  field order auth-js checks."
  [body]
  (when (map? body)
    (some (fn [k]
            (let [v (get body k)]
              (when (string? v) v)))
          [:msg :message :error_description :error])))

(defn- weak-password-reasons
  "Returns the weak-password reason list when `body` is a weak-password
  rejection, nil otherwise. Mirrors auth-js: the `weak_password` error code
  (reasons may be empty), or the legacy shape with a non-empty list of
  string reasons."
  [body]
  (when (map? body)
    (let [code (or (:error_code body) (:code body))
          reasons (get-in body [:weak_password :reasons])]
      (cond
        (= "weak_password" code)
        (vec reasons)

        (and (nil? code)
             (sequential? reasons)
             (seq reasons)
             (every? string? reasons))
        (vec reasons)))))

(defn auth-error-parser
  "Maps an Auth HTTP error response to an anomaly. Wired via
  `http/with-error-parser`; see the namespace docstring for the mapping
  rules."
  [status body headers _service]
  (let [base (assoc (error/from-http-response status body :auth)
                    :http/headers headers)
        message (body-message body)
        reasons (weak-password-reasons body)]
    (cond-> base
      (and (<= 500 status 599) message)
      (assoc :cognitect.anomalies/message message)

      reasons
      (assoc :supabase/code :weak-password
             :cognitect.anomalies/message (or message (:cognitect.anomalies/message base))
             :auth/weak-password-reasons reasons))))

(defn with-auth-errors
  "Installs the Auth error parser on a request."
  [req]
  (http/with-error-parser req auth-error-parser))
