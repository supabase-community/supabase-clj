(ns supabase.storage.platform
  "Portable URL and UTF-8 Base64 helpers for Storage."
  (:require [clojure.string :as str])
  #?(:clj (:import (java.net URLEncoder)
                   (java.nio.charset StandardCharsets)
                   (java.util Base64))))

(defn encode-uri-component
  "Percent-encodes `s` with encodeURIComponent semantics."
  [s]
  #?(:clj
     (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
         (str/replace "+" "%20")
         (str/replace "%21" "!")
         (str/replace "%27" "'")
         (str/replace "%28" "(")
         (str/replace "%29" ")")
         (str/replace "%7E" "~"))
     :cljs
     (js/encodeURIComponent (str s))))

(defn encode-form-component
  "Encodes `s` as a UTF-8 application/x-www-form-urlencoded component."
  [s]
  #?(:clj
     (URLEncoder/encode (str s) StandardCharsets/UTF_8)
     :cljs
     (-> (js/encodeURIComponent (str s))
         (str/replace #"[!'()~]"
                      (fn [c]
                        (case c
                          "!" "%21"
                          "'" "%27"
                          "(" "%28"
                          ")" "%29"
                          "~" "%7E")))
         (str/replace "%20" "+"))))

(defn utf8-base64
  "Encodes a string as Base64 after converting it to UTF-8 bytes."
  [s]
  #?(:clj
     (.encodeToString (Base64/getEncoder)
                      (.getBytes (str s) StandardCharsets/UTF_8))
     :cljs
     (js/btoa (js/unescape (js/encodeURIComponent (str s))))))
