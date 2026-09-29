(ns supabase.core.json
  "Platform JSON seam for the Supabase SDK.

  All JSON encoding/decoding in `supabase.core` goes through this
  namespace so the same code runs on both platforms:

    - **JVM** — jsonista (Jackson), keywordized keys.
    - **ClojureScript** — `js/JSON` with `js->clj` keywordization.

  Both sides decode object keys to keywords and throw on malformed
  input; callers that want lenient parsing should use [[read-string-safe]]."
  (:refer-clojure :exclude [read-string])
  #?(:clj (:require [jsonista.core :as jsonista])))

#?(:clj (def ^:private mapper (jsonista/object-mapper {:decode-key-fn true})))

(defn write-string
  "Encodes `x` as a JSON string."
  [x]
  #?(:clj  (jsonista/write-value-as-string x mapper)
     :cljs (js/JSON.stringify (clj->js x))))

(defn read-string
  "Decodes a JSON string, keywordizing object keys. Throws on malformed
  input."
  [s]
  #?(:clj  (jsonista/read-value s mapper)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))

(defn read-string-safe
  "Best-effort JSON decode. Returns the raw input unchanged on parse
  failure or when the input is not a string."
  [s]
  (if (string? s)
    (try
      (read-string s)
      (catch #?(:clj Exception :cljs :default) _ s))
    s))
