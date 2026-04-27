(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.string :as str]))

(def lib 'io.github.supabase-community/auth)
(def version (str/trim (slurp "version.txt")))
(def core-version (str/trim (slurp "../core/version.txt")))

(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Build the basis from an inline project map so the published pom references
;; core via its mvn coordinate instead of the dev-time :local/root path in
;; deps.edn (Clojars rejects local-path deps).
(def basis
  (delay (b/create-basis
          {:project {:deps {'org.clojure/clojure {:mvn/version "1.11.2"}
                            'io.github.supabase-community/core
                            {:mvn/version core-version}}}})))

(defn jar [_]
  (b/delete {:path "target"})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/supabase-community/supabase-clj"
                      :connection "scm:git:git://github.com/supabase-community/supabase-clj.git"
                      :developerConnection "scm:git:ssh://git@github.com/supabase-community/supabase-clj.git"
                      :tag (str "auth-v" version)}
                :pom-data [[:licenses
                            [:license
                             [:name "MIT"]
                             [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println (str "Built " jar-file)))

(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
  (println (str "Deployed " lib " " version " to Clojars")))
