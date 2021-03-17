(ns ordnungsamt.core
  (:require [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
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

(defn render-pr-description! [client migration-details]
  (let [context {:date       (today)
                 :migrations migration-details}]
    {:pr-title       (render/render-title context)
     :pr-description (render/render-pr context)}))

(defn create-migration-pr!
  [{:keys [client repo branch org] :as changeset} branch-name migration-details]
  (let [{:keys [pr-title pr-description]} (render-pr-description! client migration-details)
        {:keys [number]} (pull/create-pull! client org {:repo   repo
                                                        :title  pr-title
                                                        :branch branch-name
                                                        :base   branch
                                                        :body pr-description})]
    (issue/add-label! client org repo number "auto-migration")))

(defn files-to-commit [dir]
  (let [modified (sh "git" "ls-files" "--modified" "--exclude-standard" :dir dir)
        deleted  (sh "git" "ls-files" "--deleted" "--exclude-standard" :dir dir)
        added    (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir)
        out->list (fn [{:keys [out]}] (remove empty? (string/split out #"\n")))]
    (set (concat (out->list modified)
                 (out->list deleted)
                 (out->list added)))))

(defn- has-changes? [service]
  (not (zero? (:exit (sh "git" "diff-index" "--quiet" "HEAD" :dir service)))))

(defn- drop-changes! [dir]
  (let [added (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir)]
    (run! #(sh "rm" % :dir dir) added))
  (sh "git" "stash" :dir dir)
  (sh "git" "stash" "drop" :dir dir))

(defn- add-file-changes [changeset repo files]
  (->> files
       (map (fn [filepath] [filepath (slurp (str repo "/" filepath))]))
       (assoc changeset :changes)))

(defn- apply-migration! [{:keys [command name date] :as _migration} service]
  (let [{:keys [exit] :as output} (sh command :dir service)
        success?                  (zero? exit)]
    (print-process-output output)
    (when (not success?)
      (drop-changes! service))
    success?))

(defn- apply+commit-migration! [{:keys [repo] :as base-changeset} {:keys [name description] :as migration}]
  (let [commit-message (str "the ordnungsamt applying " name)
        files-for-pr   (files-to-commit repo)
        success?       (apply-migration! migration repo)]
    (when (and success? (has-changes? repo))
      {:changeset   (-> base-changeset
                        (add-file-changes repo files-for-pr)
                        (changeset/commit! commit-message))
       :description description})))

(defn- push-to-github! [changeset migration-details]
  (let [target-branch  (str "auto-refactor-"
                            (today))]
    (changeset/create-branch! changeset target-branch)
    (create-migration-pr! changeset target-branch migration-details)))

(def ^:private migrations
  [{:title       "Sample migration"
    :description ""
    :created-at  "2021-03-16"
    :directory   ""
    :command     ""}])

(defn- run-migration! [[current-changeset details] migration]
  (if-let [{:keys [changeset description]} (apply+commit-migration! current-changeset migration)]
    [changeset (conj details description)]
    [current-changeset details]))

(defn run-migrations! [github-client organization service default-branch migrations]
  (let [[changeset details] (reduce run-migration!
                                    [(changeset/from-branch! github-client organization service default-branch) []]
                                    migrations)]
    (when (seq details)
      (push-to-github! changeset details))))

(defn -main [& [service default-branch]]
  (let [org "nubank"
        github-client (github-client/new-client {:token-fn (token/default-chain
                                                             "nu-secrets-br"
                                                             "go/agent/release-lib/bumpito_secrets.json")})]
    (run-migrations! github-client org service default-branch migrations)
    (shutdown-agents)
    (System/exit 0)))
