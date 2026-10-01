# Cookbook

Task-oriented recipes for the Supabase Clojure SDK. Each section assumes a
client built with `supabase.core.client/make-client` and walks one
workflow end to end.

- [ClojureScript setup](#clojurescript-setup)
- [MFA recovery codes](#mfa-recovery-codes)
- [Bucket lifecycle rules](#bucket-lifecycle-rules)
- [Object versioning](#object-versioning)

## ClojureScript setup

The SDK runs on the JVM and on ClojureScript (browser and Node.js >= 18)
from the same namespaces. The API surface is identical; what changes is
the transport and the return type.

### Dependency and build config

Depend on the modules you use, same coordinates as on the JVM:

```clojure
;; deps.edn
{:deps {io.github.supabase-community/core     {:mvn/version "0.8.0"}
        io.github.supabase-community/auth     {:mvn/version "0.7.0"}
        io.github.supabase-community/realtime {:mvn/version "1.5.0"}}}
```

A minimal shadow-cljs build needs no SDK-specific configuration:

```clojure
;; shadow-cljs.edn
{:deps true ;; read dependencies from deps.edn
 :builds {:app {:target     :browser
                :output-dir "public/js"
                :modules    {:main {:init-fn my.app/init}}}}}
```

### Promises, not values

On ClojureScript every operation that performs HTTP (auth, postgrest,
functions) returns a `js/Promise` resolving to the same response map the
JVM returns directly. There is no synchronous `execute` — calling one
throws. Handle results with `.then` / `->`:

```clojure
(require '[supabase.auth :as auth]
         '[supabase.core.error :as error])

(-> (auth/sign-in-with-password client {:email "me@example.com"
                                        :password "s3cret"})
    (.then (fn [res]
             (if (error/anomaly? res)
               (js/console.error (:cognitect.anomalies/message res))
               (js/console.log "signed in" (get-in res [:body :user :email]))))))
```

Anomaly maps are the same on both platforms, so error handling code
ports unchanged.

### Browser auth flow

Auth functions are stateless on both platforms: the caller owns the
session map returned in `:body`. On the JVM you would keep it in an atom
(`session-store/atom-session-store`); in the browser, persist it across
page reloads with `localStorage`:

```clojure
(require '[supabase.auth.session-store :as session-store])

;; Share the key slot with the client's :auth :storage-key
(def store (session-store/local-storage-session-store
             (get-in client [:auth :storage-key])))

;; Sign in and persist
(-> (auth/sign-in-with-password client creds)
    (.then (fn [res]
             (when-not (error/anomaly? res)
               (session-store/save-session store (:body res))))))

;; On app boot, restore
(when-let [session (session-store/load-session store)]
  (js/console.log "resumed as" (get-in session [:user :email])))

;; Sign out locally
(session-store/clear-session store)
```

Token refresh stays explicit: when a call returns an expired-token
anomaly, call `auth/refresh-session` with the stored `:refresh-token` and
`save-session` the new `:body`. The JVM's proactive-refresh patterns (a
scheduled refresh before `:expires-in` elapses) work in the browser with
`js/setTimeout`.

### Realtime in the browser

Realtime uses `js/WebSocket` by default on ClojureScript; the API is
identical to the JVM quick start:

```clojure
(require '[supabase.realtime :as rt])

(def conn (rt/connect client {:on-error #(js/console.error "realtime" %)}))
(def ch   (rt/channel conn "room:lobby"))

(rt/on ch :postgres-changes
       {:event :insert :schema "public" :table "messages"}
       (fn [payload] (js/console.log "new row" payload)))

(rt/subscribe ch)
```

Two ClojureScript-only details:

- `broadcast-with-ack` / `wait-for-ack` return `js/Promise`s instead of
  parking on a latch.
- For tests, inject a fake socket with `:transport-factory`
  `(fn [url headers handlers] ...)` — see `realtime/test-cljs` in the
  repo for a recording-transport example.

### Platform notes

- **Transport**: `js/fetch` with `AbortController` timeouts. Works in
  browsers and Node.js >= 18 (global `fetch`). On the JVM the hato
  transport is untouched and remains the default.
- **JSON**: `js/JSON` behind the `supabase.core.json` seam; response
  bodies are keywordized the same way as on the JVM.
- **Retries and telemetry**: `:on-event` callbacks and the retry policy
  behave the same; retry delays use `js/setTimeout`.
- **Auth lock semantics**: supabase-js serializes auth operations with a
  cross-tab lock (`navigator.locks`). This SDK does not — if your app
  runs in multiple tabs, coordinate refresh through your session store
  (last write wins is usually fine, since refresh tokens rotate and the
  losing tab's next refresh will fail cleanly with an anomaly).
- **Storage**: the storage module is JVM-only for now; call the Storage
  REST API via `fetch` or use supabase-js from ClojureScript if you need
  uploads in the browser.

## MFA recovery codes

Recovery codes give users a second-factor fallback when they lose their
TOTP device. Each code is single-use; verifying one upgrades the session
to AAL2. Experimental: requires recovery codes enabled on the Supabase
project.

The typical flow after the user has enrolled a TOTP factor:

```clojure
(require '[supabase.auth.mfa :as mfa])

;; 1. Check whether the user already has codes
(mfa/get-recovery-codes-status client access-token)
;; => {:body {:id "..." :type "recovery_code" :total 8 :remaining 8}}

;; 2. Generate a set. The plaintext :codes come back exactly once —
;;    show them to the user immediately; they cannot be retrieved later.
(mfa/generate-recovery-codes client access-token {:friendly-name "backup"})
;; => {:body {:codes ["K4M9-X7QP-..." ...]}}

;; 3. Later, when the user logs in with a code instead of TOTP
(mfa/verify-recovery-code client access-token {:code "K4M9-X7QP-2AB8-HT3Z"})
;; => {:body {:access_token "..." :refresh_token "..." ...}}
```

Two operational details:

- **Adopt the returned session.** `verify-recovery-code` signs out the
  user's other AAL1 sessions server-side; the body of a successful verify
  carries fresh tokens that replace the current session.
- **Watch `:remaining`.** When it drops low, prompt the user to
  regenerate. Regenerating invalidates the previous set.

## Bucket lifecycle rules

Lifecycle rules expire *previous versions* of objects automatically, so
versioned buckets do not grow without bound. They act on noncurrent
versions only — enable versioning first or there is nothing for the
policy to act on.

```clojure
(require '[supabase.storage :as storage])

;; Enable versioning on the bucket
(storage/update-bucket client "avatars" {:versioning-status "ENABLED"})

;; Keep noncurrent versions for 30 days, and at most 2 of them
(storage/update-bucket-lifecycle client "avatars"
  {:rules [{:id "expire-history"
            :status :enabled
            :filter {}
            :noncurrent-version-expiration {:noncurrent-days 30
                                            :newer-noncurrent-versions 2}}]})

;; Inspect and remove
(storage/get-bucket-lifecycle client "avatars")
(storage/delete-bucket-lifecycle client "avatars")
```

Notes:

- `update-bucket-lifecycle` replaces the **whole** policy — fetch, amend,
  and send back if you want to change one rule among several.
- `:filter` is required and must be `{}` today; `:id` is optional (the
  server generates one).
- `get-bucket-lifecycle` fails with `NoSuchLifecycleConfiguration` when
  no policy is stored; `delete-bucket-lifecycle` is safe to call anyway.

## Object versioning

With versioning enabled, every overwrite keeps the previous object as a
noncurrent version. Operations default to the current version; pass
`:version-id` to reach a specific one.

```clojure
(def s (storage/from client "avatars"))

;; List versions alongside current objects
(storage/list-files s nil {:noncurrent-versions :include
                           :delete-markers :include})
;; entries carry :version-id

;; Download an old version
(storage/download s "avatars/u42.png" {:version-id "3fa4..."})

;; URLs for a specific version
(storage/get-public-url    s "avatars/u42.png" {:version-id "3fa4..."})
(storage/create-signed-url s "avatars/u42.png" {:expires-in 3600
                                                :version-id "3fa4..."})

;; Metadata for one version
(storage/info s "avatars/u42.png" {:version-id "3fa4..."})

;; Move/copy a specific version (restores it as a new current version)
(storage/move s {:from "avatars/u42.png" :to "avatars/u42-restored.png"
                 :source-version-id "3fa4..."})

;; Delete exactly one version (plain path deletes current)
(storage/remove s [{:path "avatars/u42.png" :version-id "3fa4..."}])
```

Suspending versioning (`{:versioning-status "SUSPENDED"}`) stops new
versions from being created but keeps existing ones; lifecycle rules
above are how you reclaim them.
