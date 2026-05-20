;; Manual release a module to Clojars via tag + GitHub release.
;;
;; Bumps version.txt, every README install snippet that mentions the module,
;; the optional `x-release-please-start-version` block in source, prepends a
;; CHANGELOG entry, updates the release-please manifest, commits, tags, pushes,
;; and creates a GitHub release. The release event triggers publish.yml which
;; deploys the matching module to Clojars (matrix-gated on `<module>-v*`).
;;
;; Run from repo root:
;;   bb release <module> <version> [--notes "release notes"]
;;
;; Example:
;;   bb release storage 0.1.1 --notes "Fix path normalization in upload"
;;
;; Aborts if: working tree dirty, not on main, branch behind origin, version
;; already tagged, or module/manifest entry missing.

(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell process]]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def repo-root (str (fs/canonicalize ".")))
(def manifest-path (str repo-root "/.github/.release-please-manifest.json"))
(def root-readme (str repo-root "/README.md"))

(defn die [& msgs]
  (binding [*out* *err*] (apply println "Error:" msgs))
  (System/exit 1))

(defn sh-out [& args]
  (let [{:keys [out exit err]} (shell {:out :string :err :string :continue true} args)]
    (when-not (zero? exit)
      (die "Command failed:" (str/join " " args) "\n" err))
    (str/trim out)))

(defn sh! [& args]
  (let [{:keys [exit]} (shell {:continue true} args)]
    (when-not (zero? exit)
      (die "Command failed:" (str/join " " args)))))

;; ---------------------------------------------------------------------------
;; Argument parsing
;; ---------------------------------------------------------------------------

(defn parse-args [args]
  (let [[positional flag-args] (split-with #(not (str/starts-with? % "--")) args)
        [module version] positional
        flags (apply hash-map flag-args)]
    (when-not (and module version)
      (die "Usage: bb release <module> <version> [--notes \"...\"]"))
    (when-not (re-matches #"\d+\.\d+\.\d+" version)
      (die "Version must be semver X.Y.Z, got:" version))
    (when (= "core" module) ;; allowed, just warn
      (println "Note: core releases also need any dependent module README bumps."))
    {:module module
     :version version
     :notes (get flags "--notes")}))

;; ---------------------------------------------------------------------------
;; Pre-flight checks
;; ---------------------------------------------------------------------------

(defn check-clean-tree! []
  (let [status (sh-out "git" "status" "--porcelain")]
    (when (seq status)
      (die "Working tree not clean:\n" status))))

(defn check-on-main! []
  (let [branch (sh-out "git" "rev-parse" "--abbrev-ref" "HEAD")]
    (when-not (= "main" branch)
      (die "Must be on main, currently on:" branch))))

(defn check-up-to-date! []
  (sh! "git" "fetch" "origin" "main")
  (let [local (sh-out "git" "rev-parse" "main")
        remote (sh-out "git" "rev-parse" "origin/main")]
    (when-not (= local remote)
      (die "Local main not up to date with origin/main."))))

(defn check-module-exists! [module]
  (when-not (fs/exists? (str repo-root "/" module))
    (die (format "Module '%s' does not exist." module)))
  (when-not (fs/exists? (str repo-root "/" module "/version.txt"))
    (die (format "Module '%s' has no version.txt." module))))

(defn check-tag-free! [tag]
  (let [{:keys [exit]} (shell {:out :string :err :string :continue true}
                              "git" "rev-parse" "--verify" (str "refs/tags/" tag))]
    (when (zero? exit)
      (die "Tag already exists locally:" tag))))

;; ---------------------------------------------------------------------------
;; File mutations
;; ---------------------------------------------------------------------------

(defn bump-version-txt [module version]
  (let [path (str repo-root "/" module "/version.txt")]
    (spit path (str version "\n"))
    (println "  ~" (str module "/version.txt"))))

(defn bump-mvn-coord
  "Within `text`, rewrites every `<group>/<module> {:mvn/version \"...\"}` to
  the new version. Only touches lines matching this exact module coord."
  [text module version]
  (str/replace text
               (re-pattern (str "(io\\.github\\.supabase-community/"
                                module
                                "\\s+\\{:mvn/version )\"[^\"]+\"(\\})"))
               (str "$1\"" version "\"$2")))

(defn bump-readme-snippets [module version]
  ;; Bump every README under repo (root + each module) wherever this module's
  ;; mvn coord appears inside an x-release-please-start-version block.
  (doseq [readme (->> (fs/glob repo-root "**/README.md")
                      (map str)
                      (clojure.core/remove #(str/includes? % "/.")))]
    (let [content (slurp readme)
          updated (bump-mvn-coord content module version)]
      (when (not= content updated)
        (spit readme updated)
        (println "  ~" (str/replace readme (str repo-root "/") ""))))))

(defn bump-source-version-block
  "Updates `(def ^:private version \"X.Y.Z\")` between the
  `x-release-please-start-version` and `x-release-please-end` markers, when
  present. Only relevant for `core` today (see core/src/supabase/core/client.clj)."
  [module version]
  (doseq [src (->> (fs/glob (str repo-root "/" module "/src") "**/*.clj")
                   (map str))]
    (let [content (slurp src)]
      (when (str/includes? content "x-release-please-start-version")
        (let [updated (str/replace
                       content
                       #"(?s)(;; x-release-please-start-version.*?\(def\s+\^:private\s+version\s+\")[^\"]+(\".*?;; x-release-please-end)"
                       (str "$1" version "$2"))]
          (when (not= content updated)
            (spit src updated)
            (println "  ~" (str/replace src (str repo-root "/") ""))))))))

(defn prepend-changelog [module version notes]
  (let [path (str repo-root "/" module "/CHANGELOG.md")
        existing (if (fs/exists? path) (slurp path) "# Changelog\n")
        prev-tag (->> (sh-out "git" "tag" "--list" (str module "-v*"))
                      str/split-lines
                      (clojure.core/remove str/blank?)
                      sort
                      last)
        compare-base (or prev-tag (str module "-v0.0.0"))
        date (sh-out "date" "+%Y-%m-%d")
        section (format
                 "## [%s](https://github.com/supabase-community/supabase-clj/compare/%s...%s-v%s) (%s)\n\n%s\n"
                 version compare-base module version date
                 (or notes "### Features\n\n- TODO: fill in release notes."))
        [header rest] (if (str/starts-with? existing "# Changelog")
                        [(str (first (str/split-lines existing)) "\n\n")
                         (str/replace existing #"^# Changelog\n+" "")]
                        ["# Changelog\n\n" existing])]
    (spit path (str header section "\n" rest))
    (println "  ~" (str module "/CHANGELOG.md"))))

(defn bump-manifest [module version]
  (let [data (json/parse-string (slurp manifest-path) true)]
    (when-not (contains? data (keyword module))
      (die "Manifest missing module:" module))
    (spit manifest-path
          (str (json/generate-string (assoc data (keyword module) version)
                                     {:pretty true})
               "\n"))
    (println "  ~" ".github/.release-please-manifest.json")))

;; ---------------------------------------------------------------------------
;; Verification step (optional run of module tests)
;; ---------------------------------------------------------------------------

(defn run-module-tests [module]
  (println "Running module tests for" module "...")
  (let [{:keys [exit]} (shell {:dir (str repo-root "/" module) :continue true}
                              "clojure" "-M:test")]
    (when-not (zero? exit)
      (die "Module tests failed — aborting release."))))

;; ---------------------------------------------------------------------------
;; Git + GitHub
;; ---------------------------------------------------------------------------

(defn commit-bumps [module version]
  (sh! "git" "add" "-A")
  (sh! "git" "commit" "-m" (format "chore(%s): release %s" module version)))

(defn create-and-push-tag [tag]
  (sh! "git" "tag" "-a" tag "-m" tag)
  (sh! "git" "push" "origin" "main" tag))

(defn create-gh-release [tag module version notes]
  (let [title (format "%s %s" module version)
        body (or notes (format "Release %s. See %s/CHANGELOG.md for details." version module))]
    (sh! "gh" "release" "create" tag "--title" title "--notes" body)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(let [{:keys [module version notes]} (parse-args *command-line-args*)
      tag (str module "-v" version)]
  (println (format "Releasing %s %s..." module version))
  (println)
  (println "Pre-flight checks:")
  (check-clean-tree!)
  (check-on-main!)
  (check-up-to-date!)
  (check-module-exists! module)
  (check-tag-free! tag)
  (println "  ok")
  (println)
  (run-module-tests module)
  (println)
  (println "Bumping files:")
  (bump-version-txt module version)
  (bump-readme-snippets module version)
  (bump-source-version-block module version)
  (prepend-changelog module version notes)
  (bump-manifest module version)
  (println)
  (println "Committing, tagging, pushing:")
  (commit-bumps module version)
  (create-and-push-tag tag)
  (println "  ok")
  (println)
  (println "Creating GitHub release (triggers publish.yml):")
  (create-gh-release tag module version notes)
  (println)
  (println (format "Done. %s tagged and released. Watch publish workflow:" tag))
  (println "  gh run list --workflow=publish.yml --limit 1"))
