# io.github.supabase-community/auth

Authentication and user management for the Supabase Clojure SDK. Wraps the [Auth](https://github.com/supabase/auth) HTTP API.

## Installation

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version "0.2.0"}
  io.github.supabase-community/auth {:mvn/version "0.3.0"}}} ;; x-release-please-version
```

## Quick Start

```clojure
(require '[supabase.core.client :as client]
         '[supabase.auth :as auth])

(def c (client/make-client "https://abc.supabase.co" "anon-key"))

;; Create a user
(auth/sign-up c {:email "user@example.com" :password "secure-password"})

;; Sign in
(auth/sign-in-with-password c {:email "user@example.com" :password "secure-password"})

;; Get the user profile
(auth/get-user c "<access-token>")
```

Each function returns `{:status :body :headers}` on success, or an anomaly map on failure. Check with `supabase.core.error/anomaly?`.

## Session & identity lifecycle

```clojure
;; Complete an OTP / magic-link round-trip
(auth/verify-otp c {:type "email" :email "user@example.com" :token "123456"})

;; Refresh an expired session
(auth/refresh-session c "<refresh-token>")

;; PKCE: exchange the redirect code for a session
(auth/exchange-code-for-session c {:auth-code "<code>" :code-verifier "<verifier>"})

;; Update the authenticated user
(auth/update-user c "<access-token>" {:password "new-secret"})

;; Resend a confirmation, recover a password
(auth/resend c {:type "signup" :email "user@example.com"})
(auth/reset-password-for-email c "user@example.com")

;; Secure password change: nonce-based reauthentication
(auth/reauthenticate c "<access-token>")

;; Identity linking
(auth/link-identity c "<access-token>" {:provider "github"})
(auth/get-user-identities c "<access-token>")
(auth/unlink-identity c "<access-token>" "<identity-id>")

;; Server observability
(auth/get-server-health c)
(auth/get-server-settings c)
```

### Verifying JWTs locally — `get-claims`

`get-claims` verifies a Supabase JWT and returns `{:claims {...} :header {...}}`.
Asymmetric tokens (`RS256`, `ES256`) are verified **locally** against the
project's published JWKS (fetched once and cached, no third-party dependency);
legacy `HS256` tokens fall back to a server-side `get-user` check.

```clojure
(auth/get-claims c "<jwt>")
;; => {:claims {:sub "..." :role "authenticated" ...} :header {:alg "ES256" ...}}
```

## Admin API

`supabase.auth.admin` wraps the service-role endpoints. Build the client with the
**service-role key** — these calls bypass Row-Level Security and must never run
in a browser.

```clojure
(require '[supabase.auth.admin :as admin])

(def svc (client/make-client "https://abc.supabase.co" "<service-role-key>"))

(admin/create-user svc {:email "user@example.com" :password "secret" :email-confirm true})
(admin/list-users svc {:page 1 :per-page 50})
(admin/get-user-by-id svc "<user-id>")
(admin/update-user-by-id svc "<user-id>" {:role "admin"})
(admin/delete-user svc "<user-id>")
(admin/invite-user-by-email svc "invitee@example.com")
(admin/generate-link svc {:type "signup" :email "user@example.com" :password "secret"})
(admin/sign-out svc "<access-token>" "global")
```

## Validation

All functions validate the client and credentials before issuing the request. Validation failures return an anomaly with a malli explanation:

```clojure
(auth/sign-in-with-password c {:email "a@b.com"})
;; => {:cognitect.anomalies/category :cognitect.anomalies/incorrect
;;     :cognitect.anomalies/message  "Invalid input"
;;     :malli/explanation            {...}
;;     :supabase/service             :auth}
```

Schemas live in [`supabase.auth.specs`](src/supabase/auth/specs.clj).

## Development

Run from the repo root (uses the root `deps.edn` aliases):

```bash
# Run all tests across modules
clojure -M:test

# Lint + format
clojure -M:fmt check core/src core/test auth/src auth/test
```

Or from this directory:

```bash
clojure -M:test
```

## License

MIT
