(ns supabase.postgrest.client-info
  "Registers PostgREST in the structured client-info header."
  (:require [supabase.core.http :as http]))

(def ^:private client-name "supabase-clj-postgrest")
(def ^:private version "1.6.1") ;; #x-release-please-version

(defn with-postgrest-client-info
  "Registers this PostgREST package on an already-built HTTP request."
  [req]
  (http/with-client-info req client-name version))
