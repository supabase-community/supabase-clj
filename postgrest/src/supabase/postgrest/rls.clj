(ns supabase.postgrest.rls
  "Request-level helpers for Row Level Security (RLS) headers.

  PostgREST exposes every request header to SQL via GUCs: inside an RLS
  policy (or any function), a header `x-jwt-<k>` is readable as

      current_setting('request.header.x-jwt-<k>', true)

  The second argument `true` makes the setting missing-ok (returns NULL
  instead of raising), so policies can key off per-request context
  without a custom JWT. For example, a per-tenant policy:

      CREATE POLICY tenant_isolation ON documents
        USING (tenant_id = current_setting('request.header.x-jwt-tenant', true)::uuid);

  Combined with [[with-access-token]], a service-key client can
  impersonate the end user for a single request so PostgREST applies
  that user's RLS policies:

      (-> (pg/from client \"documents\")
          (pg/select \"*\")
          (rls/with-access-token user-jwt)
          (rls/with-jwt-claims {:tenant tenant-id})
          (pg/execute))"
  (:require [supabase.core.http :as http]))

(defn with-access-token
  "Sets `authorization: Bearer <token>` on this one request, overriding
  the client-level token. Use it to impersonate the end user so
  PostgREST applies their RLS policies instead of the service key's."
  [req token]
  (http/with-headers req {"authorization" (str "Bearer " token)}))

(defn with-jwt-claim
  "Sets a single `x-jwt-<k>` header, readable inside RLS policies via
  `current_setting('request.header.x-jwt-<k>', true)`. `k` may be a
  keyword or string; `v` is stringified via `str`. Merges with headers
  already on the request."
  [req k v]
  (http/with-headers req {(str "x-jwt-" (name k)) (str v)}))

(defn with-jwt-claims
  "Sets one `x-jwt-<k>` header per entry of `claims-map`. See
  [[with-jwt-claim]] for the PostgREST mechanism. Existing unrelated
  headers are preserved."
  [req claims-map]
  (http/with-headers
    req
    (into {}
          (map (fn [[k v]] [(str "x-jwt-" (name k)) (str v)]))
          claims-map)))
