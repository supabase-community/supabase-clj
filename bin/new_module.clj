;; Scaffold a new module under the monorepo.
;;
;; Creates `<name>/` with deps.edn, build.clj, version.txt, README, CHANGELOG,
;; tests.edn and src/test stubs. Patches root deps.edn, tests.edn, release-please
;; config + manifest, publish.yml and root README so the new module is wired into
;; CI, release-please and Clojars publish without manual edits.
;;
;; Run from repo root:  bb new-module <name>
;; Idempotent guard: aborts if `<name>/` already exists.

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def repo-root (str (fs/canonicalize ".")))
(def name-arg (first *command-line-args*))

(defn die [& msgs]
  (binding [*out* *err*] (apply println "Error:" msgs))
  (System/exit 1))

(defn validate-name! [n]
  (when-not n
    (die "Module name required.\nUsage: bb new-module <name>"))
  (when-not (re-matches #"[a-z][a-z0-9-]*" n)
    (die "Name must match [a-z][a-z0-9-]* (kebab-case)."))
  (when (= "core" n)
    (die "Cannot recreate the 'core' module."))
  (when (fs/exists? (str repo-root "/" n))
    (die (format "Directory '%s/' already exists." n))))

(defn write! [rel-path content]
  (let [path (str repo-root "/" rel-path)]
    (fs/create-dirs (fs/parent path))
    (spit path content)
    (println "  +" rel-path)))

(defn patch! [rel-path f]
  (let [path (str repo-root "/" rel-path)
        before (slurp path)
        after (f before)]
    (when (= before after)
      (die (format "Patch produced no change in %s — anchor probably moved." rel-path)))
    (spit path after)
    (println "  ~" rel-path)))

;; ---------------------------------------------------------------------------
;; Templates for new files
;; ---------------------------------------------------------------------------

(defn tmpl-deps-edn [_]
  "{:paths [\"src\"]

 :deps
 {io.github.supabase-community/core {:local/root \"../core\"}}

 :aliases
 {:test {:extra-paths [\"test\"]
         :extra-deps {lambdaisland/kaocha {:mvn/version \"1.91.1392\"}}
         :main-opts [\"-m\" \"kaocha.runner\"]}

  :build {:deps {io.github.clojure/tools.build {:mvn/version \"0.10.6\"}
                 slipset/deps-deploy {:mvn/version \"0.2.2\"}}
          :ns-default build}}}
")

(defn tmpl-build-clj [n]
  (format "(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.supabase-community/%s)
(def version (str/trim (slurp \"version.txt\")))
(def core-version (str/trim (slurp \"../core/version.txt\")))

(def class-dir \"target/classes\")
(def jar-file (format \"target/%%s-%%s.jar\" (name lib) version))

;; Build the basis from an inline project map so the published pom references
;; core via its mvn coordinate instead of the dev-time :local/root path.
(def basis
  (delay (b/create-basis
          {:project {:deps {'org.clojure/clojure {:mvn/version \"1.11.2\"}
                            'io.github.supabase-community/core
                            {:mvn/version core-version}}}})))

(defn jar [_]
  (b/delete {:path \"target\"})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs [\"src\"]
                :scm {:url \"https://github.com/supabase-community/supabase-clj\"
                      :connection \"scm:git:git://github.com/supabase-community/supabase-clj.git\"
                      :developerConnection \"scm:git:ssh://git@github.com/supabase-community/supabase-clj.git\"
                      :tag (str \"%s-v\" version)}
                :pom-data [[:licenses
                            [:license
                             [:name \"MIT\"]
                             [:url \"https://opensource.org/licenses/MIT\"]]]]})
  (b/copy-dir {:src-dirs [\"src\"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println (str \"Built \" jar-file)))

(defn deploy [_]
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
  (println (str \"Deployed \" lib \" \" version \" to Clojars\")))
" n n))

(defn tmpl-tests-edn [_]
  "#kaocha/v1
{:tests [{:id :unit
          :test-paths [\"test\"]
          :source-paths [\"src\"]
          :ns-patterns [\".*-test$\"]}]}
")

(defn tmpl-readme [n]
  (format "# io.github.supabase-community/%s

TODO: one-line description.

## Installation

<!-- x-release-please-start-version -->

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version \"0.2.0\"}
  io.github.supabase-community/%s {:mvn/version \"0.0.0\"}}}
```

<!-- x-release-please-end -->

## Quick Start

```clojure
(require '[supabase.core.client :as client]
         '[supabase.%s :as %s])

;; TODO
```

## License

MIT
" n n n n))

(defn tmpl-changelog [_] "# Changelog\n")

(defn tmpl-version [_] "0.0.0\n")

(defn tmpl-src [n]
  (format "(ns supabase.%s
  \"TODO: namespace docstring.\"
  (:require [supabase.core.client :as client]
            [supabase.core.http :as http]))
" n))

(defn tmpl-test [n]
  (format "(ns supabase.%s-test
  (:require [clojure.test :refer [deftest is testing]]
            [supabase.%s :as sut]))
" n n))

;; ---------------------------------------------------------------------------
;; Surgical patches to existing files
;; ---------------------------------------------------------------------------

(defn patch-root-deps-edn [n s]
  ;; Append <name>/test to :test :extra-paths.
  (-> s
      (str/replace
       #"(\:test \{[^}]*?\:extra-paths \[)([^\]]+)(\])"
       (fn [[_ pre paths post]]
         (str pre paths " \"" n "/src\" \"" n "/test\"" post)))
      ;; Append module dep into :test :extra-deps map.
      (str/replace
       #"(\:test \{\:extra-deps \{[^}]+?)(\}\n)"
       (fn [[_ deps tail]]
         (str deps
              "\n                       io.github.supabase-community/" n
              " {:local/root \"" n "\"}"
              tail)))))

(defn patch-root-tests-edn [n s]
  (-> s
      (str/replace
       #"(\:test-paths \[)([^\]]+)(\])"
       (fn [[_ pre paths post]] (str pre paths " \"" n "/test\"" post)))
      (str/replace
       #"(\:source-paths \[)([^\]]+)(\])"
       (fn [[_ pre paths post]] (str pre paths " \"" n "/src\"" post)))))

(defn patch-release-please-config [n s]
  ;; Insert package entry just before the "." (root-docs) entry.
  (let [insertion (format
                   (str "    \"%s\": {\n"
                        "      \"release-type\": \"simple\",\n"
                        "      \"package-name\": \"io.github.supabase-community/%s\",\n"
                        "      \"component\": \"%s\",\n"
                        "      \"changelog-path\": \"CHANGELOG.md\",\n"
                        "      \"extra-files\": [\n"
                        "        {\n"
                        "          \"type\": \"generic\",\n"
                        "          \"path\": \"README.md\"\n"
                        "        }\n"
                        "      ]\n"
                        "    },\n")
                   n n n)]
    (str/replace s
                 #"(    \"\.\": \{)"
                 (str insertion "$1"))))

(defn patch-release-please-manifest [n s]
  (str/replace s
               #"(\"core\": \"[^\"]+\",)"
               (str "$1\n  \"" n "\": \"0.0.0\",")))

(defn patch-publish-yml [n s]
  (str/replace s
               #"(module: \[)([^\]]+)(\])"
               (fn [[_ pre mods post]]
                 (str pre mods ", " n post))))

(defn patch-root-readme [n s]
  ;; Append a bullet after the existing module list.
  (let [bullet (format "- [%s](%s/)\n"
                       (str/upper-case (subs n 0 1)) ;; capitalize first letter
                       n)
        full-bullet (format "- [%s%s](%s/)\n"
                            (str/upper-case (subs n 0 1))
                            (subs n 1)
                            n)]
    (str/replace s
                 #"(- \[Auth\]\(auth/\)\n)"
                 (str "$1" full-bullet))
    ;; If "Auth" anchor is missing, fall back to inserting after Core.
    ))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(validate-name! name-arg)
(let [n name-arg]
  (println (format "Scaffolding module '%s'..." n))
  (println "Creating files:")
  (write! (str n "/deps.edn")                       (tmpl-deps-edn n))
  (write! (str n "/build.clj")                      (tmpl-build-clj n))
  (write! (str n "/version.txt")                    (tmpl-version n))
  (write! (str n "/CHANGELOG.md")                   (tmpl-changelog n))
  (write! (str n "/README.md")                      (tmpl-readme n))
  (write! (str n "/tests.edn")                      (tmpl-tests-edn n))
  (write! (str n "/src/supabase/" n ".clj")         (tmpl-src n))
  (write! (str n "/test/supabase/" n "_test.clj")   (tmpl-test n))
  (println "Patching:")
  (patch! "deps.edn"                                (partial patch-root-deps-edn n))
  (patch! "tests.edn"                               (partial patch-root-tests-edn n))
  (patch! ".github/release-please-config.json"      (partial patch-release-please-config n))
  (patch! ".github/.release-please-manifest.json"   (partial patch-release-please-manifest n))
  (patch! ".github/workflows/publish.yml"           (partial patch-publish-yml n))
  (patch! "README.md"                               (partial patch-root-readme n))
  (println)
  (println (format "Done. Next:" n))
  (println (format "  cd %s && nix develop ../#default --command clojure -M:test" n))
  (println "  Inspect the patched files, commit when happy."))
