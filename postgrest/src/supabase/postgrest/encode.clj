(ns supabase.postgrest.encode
  "Pre-encoders for complex PostgreSQL column types, for use as filter
  values or RPC arguments. These render the literal forms PostgREST expects;
  PostgREST itself validates them server-side.

  ## Example

      (require '[supabase.postgrest :as pg]
               '[supabase.postgrest.encode :as enc])

      (-> (pg/from c \"reservations\")
          (pg/eq \"during\" (enc/pg-range start end))
          (pg/contains \"tags\" (enc/pg-array [\"vip\" \"weekend\"]))
          (pg/execute))"
  (:require [clojure.string :as str])
  (:import (java.time Instant)
           (java.util Date)))

(defn- quote-elem
  "Quotes a single array element when it contains characters that would
  otherwise break the `{...}` literal."
  [x]
  (let [s (str x)]
    (if (re-find #"[,\"\\{}\s]" s)
      (str "\"" (str/replace s #"([\"\\])" "\\\\$1") "\"")
      s)))

(defn pg-array
  "Encodes `xs` as a PostgreSQL array literal: `{a,b,c}`. Elements with
  special characters are quoted; `nil` elements become `NULL`."
  [xs]
  (str "{" (str/join "," (map #(if (nil? %) "NULL" (quote-elem %)) xs)) "}"))

(defn ->iso
  "Encodes a timestamp as ISO-8601. Accepts `java.time.Instant`,
  `java.util.Date`, or a string (passed through)."
  [t]
  (cond
    (instance? Instant t) (.toString ^Instant t)
    (instance? Date t)    (.toString (.toInstant ^Date t))
    :else                 (str t)))

(defn pg-range
  "Encodes a range literal. Default bounds `[lo,hi)` (inclusive low,
  exclusive high); pass `bounds` as one of `\"[)\"`, `\"[]\"`, `\"()\"`,
  `\"(]\"`. Either endpoint may be `nil` for an unbounded side. Timestamp
  endpoints are coerced via [[->iso]]."
  ([lo hi] (pg-range lo hi "[)"))
  ([lo hi bounds]
   (str (subs bounds 0 1)
        (when (some? lo) (->iso lo)) ","
        (when (some? hi) (->iso hi))
        (subs bounds 1 2))))

(defn pg-bool
  "Encodes a boolean as `\"true\"`/`\"false\"`, for `is`-style filters."
  [b]
  (if b "true" "false"))
