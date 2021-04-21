(ns ordnungsamt.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :refer [pprint]]
            clojure.set
            [clojure.string :as string]
            [clj-github.changeset :as changeset]
            [clj-github.httpkit-client :as github-client]
            [clj-github.issue :as issue]
            [clj-github.pull :as pull]
            [clj-github.token :as token]
            [ordnungsamt.close-open-prs :refer [close-open-prs!]]
            [ordnungsamt.render :as render])
  (:gen-class))

(def applied-migrations-file ".migrations.edn")

(defn- today []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
           (new java.util.Date)))

(defn- print-process-output [{:keys [exit out err]}]
  (letfn [(print-lines [output]
            (when output
              (->> (string/split output (re-pattern (System/lineSeparator)))
                   (map println)
                   doall)))]
    (print-lines out)
    (when-not (zero? exit)
      (print-lines err))))

(defn- render-pr-description! [migration-details]
  (let [context {:date       (today)
                 :migrations migration-details}]
    {:pr-title       (render/render-title context)
     :pr-description (render/render-pr context)}))

(defn- create-migration-pr!
  [client
   {:keys [repo branch org] :as _changeset}
   migration-details
   base-branch]
  (let [{:keys [pr-title pr-description]} (render-pr-description! migration-details)
        {:keys [number]} (pull/create-pull! client org {:repo   repo
                                                        :title  pr-title
                                                        :branch branch
                                                        :base   base-branch
                                                        :body   pr-description})]
    (issue/add-label! client org repo number "auto-migration")))

(defn out->list [{:keys [out]}]
  (remove empty? (string/split out #"\n")))

(defn- read-registered-migrations [dir]
  (let [applied-migrations-filepath (str dir "/" applied-migrations-file)]
    (if (.exists (io/file applied-migrations-filepath))
      (read-string (slurp applied-migrations-filepath))
      [])))

(defn- register-migration! [dir {:keys [id title] :as _migration}]
  (when id
    (let [applied-migrations-filepath (str dir "/" applied-migrations-file)
          migration-registry          (conj (read-registered-migrations dir) {:id id :_title title})
          migration-registry-str      (with-out-str (pprint migration-registry))
          header-comment              (str ";; auto-generated file\n"
                                           ";; By editing this file you can make the system skip certain migration.\n"
                                           ";; See README for more details\n")]
           (spit applied-migrations-filepath
                 (str header-comment migration-registry-str)))))

(defn- files-to-commit [dir]
  (let [modified  (->> (sh "git" "ls-files" "--modified" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))
        deleted   (->> (sh "git" "ls-files" "--deleted" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))
        added     (->> (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir)
                       out->list
                       (into #{}))]
    {:modified (-> modified
                   (conj applied-migrations-file)
                   (clojure.set/difference deleted)
                   (clojure.set/difference added))
     :deleted  deleted
     :added    added}))

(defn- sh! [& args]
  (let [{:keys [exit err] :as result} (apply sh args)]
    (when (not (zero? exit))
      (throw (ex-info (str "FAILED running command:\n" args "\nerror message:\n" err)
                      result)))))

(defn- has-changes? [dir]
  (->> dir
       files-to-commit
       (apply concat)
       seq))

(defn- drop-changes! [dir]
  (let [added (out->list (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir))]
    (run! #(sh "rm" % :dir dir) added))
  (sh "git" "stash" :dir dir)
  (sh "git" "stash" "drop" :dir dir))

(defn- local-commit! [{:keys [modified deleted added]} dir]
  (run! (fn [file] (sh! "git" "add" file :dir dir)) (concat modified added))
  (run! (fn [file] (sh! "git" "rm" file :dir dir)) deleted)
  (sh! "git" "config" "user.name" "ordnungsamt" :dir dir)
  (sh! "git" "config" "user.email" "order-department@not-real.com" :dir dir)
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
  (let [{:keys [exit] :as output} (try
                                    (apply sh (concat command [:dir dir]))
                                    (catch java.io.IOException e
                                      {:exit 1
                                       :err (str e)}))
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
        success?       (apply-migration! migration repo-dir)]
    (when (and success? (has-changes? repo-dir))
      (register-migration! repo-dir migration)
      (let [files-for-pr  (files-to-commit repo-dir)
            changed-files (->> files-for-pr
                               vals
                               (apply clojure.set/union))
            changeset'    {:changeset   (-> base-changeset
                                            (add-file-changes repo-dir changed-files)
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

(defn filter-registered-migrations [base-dir service]
  (let [registered-migrations (->> (read-registered-migrations (str base-dir service))
                                   (map :id)
                                   set)]
    (fn [{:keys [id]}] (not (contains? registered-migrations id)))))

(defn filter-opt-in [service]
  (fn [{:keys [opt-in]}] (or (nil? opt-in)
                             (contains? opt-in service))))

; there must be a better way to compose this predicates (without using a macro!)
(defn compose-filters [filters]
  (reduce (fn [acc f] (fn [value] (and (acc value) (f value)))) (constantly true) filters))

; rename
(defn run-migrations!* [github-client organization service default-branch target-branch base-dir migrations]
  (let [base-changeset      (-> github-client
                                (changeset/from-branch! organization service default-branch)
                                (changeset/create-branch! target-branch))
        [changeset details] (reduce (partial run-migration! base-dir)
                                    [base-changeset []]
                                    (:migrations migrations))]
    (when (seq details)
      (let [[changeset' _] (reduce (partial run-migration! base-dir)
                                   [changeset details]
                                   (:post migrations))]
        (create-migration-pr! github-client changeset' details default-branch)))))

(defn run-migrations! [github-client organization service default-branch target-branch base-dir migrations]
  (let [migrations-filter (compose-filters [(filter-registered-migrations base-dir service)
                                            (filter-opt-in service)])
        to-run-migrations (filter migrations-filter (:migrations migrations))]
    (run-migrations!* github-client organization service default-branch target-branch base-dir (assoc migrations :migrations to-run-migrations))))

#_(defn run-migrations! [github-client organization service default-branch target-branch base-dir migrations]
  (let [base-changeset      (-> github-client
                                (changeset/from-branch! organization service default-branch)
                                (changeset/create-branch! target-branch))
        [changeset details] (reduce (partial run-migration! base-dir)
                                    [base-changeset []]
                                    to-run-migrations)]
    (when (seq details)
      (let [[changeset' _] (reduce (partial run-migration! base-dir)
                                   [changeset details]
                                   (:post migrations))]
        (create-migration-pr! github-client changeset' details default-branch)))))

(defn resolve-token-fn [token-fn]
  (when token-fn
    (let [token-fn' (symbol token-fn)]
      (require (symbol (namespace token-fn')))
      (resolve token-fn'))))

(def default-token-fn
  (token/chain [token/hub-config token/env-var]))

(defn -main [& [service default-branch migrations-directory token-fn]]
  (let [org           "nubank"
        token-fn      (or (resolve-token-fn token-fn)
                          default-token-fn)
        github-client (github-client/new-client {:token-fn token-fn})
        target-branch (str "auto-refactor-" (today))
        migrations    (-> migrations-directory (str "/migrations.edn") slurp read-string)]
    (close-open-prs! github-client org service)
    (run-migrations! github-client org service default-branch target-branch "" migrations)
    (shutdown-agents)
    (System/exit 0)))
