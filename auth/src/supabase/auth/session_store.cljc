(ns supabase.auth.session-store
  "Pluggable session persistence for Supabase Auth.

  The auth functions are stateless: the caller owns the session map
  returned by sign-in / `verify-otp` / `refresh-session`. This namespace
  provides a small protocol for persisting that session between process
  restarts (or, on ClojureScript, page reloads), matching the client's
  `:auth :persist-session` / `:storage-key` configuration.

  Implementations:

    - [[atom-session-store]] — in-memory atom. Both platforms; the
      default choice on the JVM.
    - `local-storage-session-store` — browser `localStorage`,
      ClojureScript only. Keyed by the client's `:auth :storage-key`.

  ## Example

      (def store (session-store/atom-session-store))

      (session-store/save-session store (:body (auth/sign-in-with-password c creds)))
      (session-store/load-session store)   ;; => session map or nil
      (session-store/clear-session store)  ;; sign-out"
  #?(:cljs (:require [supabase.core.json :as json])))

(defprotocol SessionStore
  "Session persistence contract. `session` is the token payload the auth
  server returns (the `:body` of sign-in / verify-otp / refresh-session
  responses); implementations store and return it as a plain map, or nil
  when absent."
  (save-session [store session]
    "Persists `session`. Returns nil.")
  (load-session [store]
    "Returns the persisted session map, or nil when absent.")
  (clear-session [store]
    "Removes any persisted session. Returns nil."))

;; ---------------------------------------------------------------------------
;; Atom (both platforms)
;; ---------------------------------------------------------------------------

(defrecord AtomSessionStore [state]
  SessionStore
  (save-session [_ session] (reset! state session) nil)
  (load-session [_] @state)
  (clear-session [_] (reset! state nil) nil))

(defn atom-session-store
  "Returns a [[SessionStore]] backed by an in-memory atom. Sessions do not
  survive process restarts (JVM) or page reloads (ClojureScript); use it as
  the default on the JVM and for tests everywhere."
  []
  (->AtomSessionStore (atom nil)))

;; ---------------------------------------------------------------------------
;; localStorage (ClojureScript only)
;; ---------------------------------------------------------------------------

#?(:cljs
   (defrecord LocalStorageSessionStore [key]
     SessionStore
     (save-session [_ session]
       (.setItem js/localStorage key (json/write-string session))
       nil)
     (load-session [_]
       (when-let [s (.getItem js/localStorage key)]
         (json/read-string-safe s)))
     (clear-session [_]
       (.removeItem js/localStorage key)
       nil)))

#?(:cljs
   (defn local-storage-session-store
     "Returns a [[SessionStore]] backed by browser `localStorage`, persisting
     the session across page reloads. `key` defaults to
     `\"supabase.auth.token\"`; pass the client's `:auth :storage-key` to
     share the slot with other tooling:

         (local-storage-session-store (get-in client [:auth :storage-key]))"
     ([] (local-storage-session-store "supabase.auth.token"))
     ([key] (->LocalStorageSessionStore key))))
