# Supabase Clojure

Supabase Community Clojure SDK

<!-- x-release-please-start-version -->

```clojure
;; deps.edn
{:deps
  {io.supabase/core      {:mvn/version "0.1.0"}  ;; base SDK
   io.supabase/auth      {:mvn/version "0.1.0"}  ;; auth integration
   io.supabase/postgrest {:mvn/version "0.1.0"}  ;; postgrest integration
   io.supabase/storage   {:mvn/version "0.1.0"}  ;; storage integration
   io.supabase/functions {:mvn/version "0.1.0"}  ;; edge functions integration
   io.supabase/realtime  {:mvn/version "0.1.0"}}} ;; realtime integration
```

<!-- x-release-please-end -->

Individual product client documentation:

- [Core](core/)
- Auth (coming soon)
- PostgREST (coming soon)
- Storage (coming soon)
- Functions (coming soon)
- Realtime (coming soon)

### Clients

A Supabase client is a plain immutable Clojure map holding general information about your Supabase project. It can be passed to any of the service modules (auth, storage, postgrest, etc.) to interact with the corresponding API.

### Usage

To create a client:

```clojure
(require '[supabase.core.client :as supabase])

(supabase/make-client "https://<supabase-url>" "<supabase-api-key>")
;; => {:base-url "https://..." :api-key "..." :auth-url "..." ...}
```

Any additional config can be passed as keyword arguments:

```clojure
(supabase/make-client "https://<supabase-url>" "<supabase-api-key>"
  :db {:schema "another"}
  :auth {:flow-type "pkce"}
  :global {:headers {"custom-header" "custom-value"}})
```

Initialized clients are plain maps with no managed state.

### Error Handling

Errors are represented as [cognitect/anomalies](https://github.com/cognitect-labs/anomalies) maps instead of exceptions. Check results with `error/anomaly?`:

```clojure
(require '[supabase.core.error :as error])

(let [result (some-operation)]
  (if (error/anomaly? result)
    (println "Error:" (:cognitect.anomalies/message result))
    (println "Success:" (:body result))))
```

## License

MIT
