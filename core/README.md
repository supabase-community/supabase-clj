# io.supabase/core

Core module for the Supabase Clojure SDK. Provides client configuration, HTTP request building, and error handling used by all service modules.

## Installation

<!-- x-release-please-start-version -->

```clojure
;; deps.edn
{:deps {io.supabase/core {:mvn/version "0.1.0"}}}
```

<!-- x-release-please-end -->

## Quick Start

```clojure
(require '[supabase.core.client :as supabase])

;; 1. Create a client
(def my-client
  (supabase/make-client "https://abc123.supabase.co" "my-api-key"))
```

## Namespaces

### `supabase.core.client`

Creates and manages immutable client configuration maps.

```clojure
;; Create with defaults
(supabase/make-client "https://abc123.supabase.co" "my-api-key")

;; Create with options
(supabase/make-client "https://abc123.supabase.co" "my-api-key"
  :db {:schema "private"}
  :auth {:flow-type "pkce" :debug true}
  :global {:headers {"x-custom" "value"}}
  :storage {:use-new-hostname true})

;; Update access token (returns a new map)
(supabase/update-access-token my-client "new-jwt-token")
```

#### Client Map Structure

```clojure
{:base-url       "https://abc123.supabase.co"
 :api-key        "my-api-key"
 :access-token   "my-api-key"
 :auth-url       "https://abc123.supabase.co/auth/v1"
 :database-url   "https://abc123.supabase.co/rest/v1"
 :storage-url    "https://abc123.supabase.co/storage/v1"
 :functions-url  "https://abc123.supabase.co/functions/v1"
 :realtime-url   "https://abc123.supabase.co/realtime/v1"
 :db             {:schema "public"}
 :global         {:headers {"x-client-info" "supabase-clj/0.1.0"}}
 :auth           {:auto-refresh-token true
                  :debug false
                  :detect-session-in-url true
                  :flow-type "implicit"
                  :persist-session true
                  :storage-key "sb-abc123-auth-token"}
 :storage        {:use-new-hostname false}}
```

### `supabase.core.http`

Composable HTTP request builder and executor.

```clojure
;; Build a request step by step
(-> (http/request client)
    (http/with-service-url :storage-url "/bucket/avatars")
    (http/with-method :get)
    (http/with-headers {"prefer" "return=representation"})
    (http/with-query {"limit" "10" "offset" "0"})
    (http/execute))

;; Throwing variant
(http/execute! req)  ;; throws ex-info on error

;; Async variant
@(http/execute-async req)  ;; returns CompletableFuture
```

#### Response Format

Success (status < 400):

```clojure
{:status 200
 :body {:id "..." :email "..."}
 :headers {"content-type" "application/json" ...}}
```

Error (status >= 400) returns an anomaly map (see below).

### `supabase.core.error`

Anomaly-based error handling following [cognitect/anomalies](https://github.com/cognitect-labs/anomalies).

```clojure
;; Check if a result is an error
(error/anomaly? result)  ;; => true/false

;; Create anomalies manually
(error/anomaly :cognitect.anomalies/incorrect
  {:cognitect.anomalies/message "Invalid email"
   :supabase/service :auth})

;; HTTP errors are created automatically by execute
(error/from-http-response 404 {:message "Not found"} :storage)
;; => {:cognitect.anomalies/category :cognitect.anomalies/not-found
;;     :cognitect.anomalies/message  "Not Found"
;;     :supabase/service :storage
;;     :supabase/code    :not-found
;;     :http/status      404
;;     :http/body        {:message "Not found"}}
```

#### Anomaly Category Mapping

| HTTP Status | Anomaly Category                   | Code                          |
| ----------- | ---------------------------------- | ----------------------------- |
| 400         | `:cognitect.anomalies/incorrect`   | `:bad-request`                |
| 401, 403    | `:cognitect.anomalies/forbidden`   | `:unauthorized`, `:forbidden` |
| 404         | `:cognitect.anomalies/not-found`   | `:not-found`                  |
| 409         | `:cognitect.anomalies/conflict`    | `:resource-already-exists`    |
| 429         | `:cognitect.anomalies/busy`        | `:too-many-requests`          |
| 500         | `:cognitect.anomalies/fault`       | `:server-error`               |
| 503         | `:cognitect.anomalies/unavailable` | `:service-unavailable`        |

## Development

```bash
# Run all tests (Kaocha)
clojure -M:test

# Run specific test namespace
clojure -M:test --focus supabase.core.client-test

# Check formatting (cljfmt)
clojure -M:fmt check src test

# Fix formatting
clojure -M:fmt fix src test
```

## License

MIT
