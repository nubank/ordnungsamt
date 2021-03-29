(ns ordnungsamt.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            clojure.set
            [clojure.string :as string]
            [common-github.changeset :as changeset]
            [common-github.httpkit-client :as github-client]
            [common-github.issue :as issue]
            [common-github.pull :as pull]
            [common-github.token :as token]
            [ordnungsamt.render :as render])
  (:gen-class))

(defn- today []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
           (new java.util.Date)))

(defn- print-process-output [{:keys [exit out err]}]
  (letfn [(print-lines [output]
            (->> (string/split output (re-pattern (System/lineSeparator)))
                 (map println)
                 doall))]
    (print-lines out)
    (when-not (zero? exit)
      (print-lines err))))

(defn render-pr-description! [migration-details]
  (let [context {:date       (today)
                 :migrations migration-details}]
    {:pr-title       (render/render-title context)
     :pr-description (render/render-pr context)}))

(defn create-migration-pr!
  [client
   {:keys [repo branch org] :as _changeset}
   migration-details
   branch-name]
  (let [{:keys [pr-title pr-description]} (render-pr-description! migration-details)
        {:keys [number]} (pull/create-pull! client org {:repo   repo
                                                        :title  pr-title
                                                        :branch branch-name
                                                        :base   branch
                                                        :body   pr-description})]
    (issue/add-label! client org repo number "auto-migration")))

(defn out->list [{:keys [out]}]
  (remove empty? (string/split out #"\n")))

(defn files-to-commit [dir]
  (let [modified  (->> (sh "git" "ls-files" "--modified" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))
        deleted   (->> (sh "git" "ls-files" "--deleted" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))
        added     (->> (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))]
    {:modified (clojure.set/difference modified deleted)
     :deleted  deleted
     :added    added}))

(defn sh! [& args]
  (let [{:keys [exit err] :as result} (apply sh args)]
    (when (not (zero? exit))
      (throw (ex-info (str "FAILED running command:\n" args "\nerror message:\n" err)
                      result)))))

(defn- has-changes? [dir]
  (not (zero? (:exit (sh "git" "diff-index" "--quiet" "HEAD" :dir dir)))))

(defn- drop-changes! [dir]
  (let [added (out->list (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir))]
    (run! #(sh "rm" % :dir dir) added))
  (sh "git" "stash" :dir dir)
  (sh "git" "stash" "drop" :dir dir))

(defn- local-commit! [{:keys [modified deleted added]} dir]
  (run! (fn [file] (sh! "git" "add" file :dir dir)) (concat modified added))
  (run! (fn [file] (sh! "git" "rm" file :dir dir)) deleted)
  (sh! "git" "-c" "commit.gpgsign=false" "commit" "-m" "migration applied" :dir dir))

(defn- add-file-changes [changeset repo files]
  (reduce (fn [changeset filepath]
            (let [file (io/file (str repo "/" filepath))]
              (if (.exists file)
                (changeset/put-content changeset filepath (slurp file))
                (changeset/delete changeset filepath))))
          changeset
          files))

(defn- apply-migration! [{:keys [command] :as _migration} dir]
  (let [{:keys [exit] :as output} (apply sh (concat command [:dir dir]))
        success?                  (zero? exit)]
    (print-process-output output)
    (when (not success?)
      (drop-changes! dir))
    success?))

(defn- apply+commit-migration!
  [base-dir
   {:keys [repo branch] :as base-changeset}
   {:keys [title] :as migration}]
  (let [repo-dir       (str base-dir repo)
        commit-message (str "the ordnungsamt applying " title)
        success?       (apply-migration! migration repo-dir)
        files-for-pr   (files-to-commit repo-dir)]
    (when (and success? (has-changes? repo-dir))
      (let [changeset' {:changeset   (-> base-changeset
                                         (add-file-changes repo-dir (->> files-for-pr vals (apply clojure.set/union)))
                                         (changeset/commit! commit-message)
                                         (assoc :branch branch)
                                         changeset/update-branch!)
                        :description (select-keys migration [:title :created-at :description])}]
        (local-commit! files-for-pr repo-dir)
        changeset'))))

(defn- run-migration! [base-dir [current-changeset details] migration]
  (if-let [{:keys [changeset description]} (apply+commit-migration! base-dir current-changeset migration)]
    [changeset (conj details description)]
    [current-changeset details]))

(defn run-migrations! [github-client organization service default-branch target-branch base-dir migrations]
  (let [registered-migrations (->> (str base-dir service "/.migrations.edn")
                                   slurp
                                   read-string
                                   (map :id)
                                   set)
        to-run-migrations   (remove (fn [{:keys [id]}] (contains? registered-migrations id))
                                    migrations)
        base-changeset      (-> github-client
                                (changeset/from-branch! organization service default-branch)
                                (changeset/create-branch! target-branch))
        [changeset details] (reduce (partial run-migration! base-dir)
                                    [base-changeset []]
                                    to-run-migrations)]
    (when (seq details)
      (create-migration-pr! github-client changeset details target-branch))))

(def add-migration-file
  {:title       "add .migration file"
   :description "..."
   :id          0
   :created-at  "2021-03-29"
   :command     ["echo \"[{:id 0 :_title \"create .migration.edn file\"}]\" .migrations.edn"]})


(defn -main [& [service default-branch migrations-directory]]
  (let [org           "nubank"
        github-client (github-client/new-client {:token-fn (token/default-chain
                                                             "nu-secrets-br"
                                                             "go/agent/release-lib/bumpito_secrets.json")})
        target-branch (str "auto-refactor-" (today))
        migrations    (-> migrations-directory (str "/migrations.edn") slurp read-string)]
    (run-migrations! github-client org service default-branch target-branch "../" (concat [add-migration-file] migrations))
    (shutdown-agents)
    (System/exit 0)))
