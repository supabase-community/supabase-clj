# io.github.supabase-community/auth

Authentication and user management for the Supabase Clojure SDK. Wraps the [Auth](https://github.com/supabase/auth) HTTP API.

## Installation

<!-- x-release-please-start-version -->

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version "0.2.0"}
  io.github.supabase-community/auth {:mvn/version "0.1.0"}}}
```

<!-- x-release-please-end -->

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
