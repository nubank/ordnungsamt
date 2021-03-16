(ns ordnungsamt.core
  (:require [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [common-github.changeset :as changeset]
            [common-github.httpkit-client :as github-client]
            [common-github.issue :as issue]
            [common-github.pull :as pull]
            [common-github.token :as token])
  (:gen-class))

(defn- print-process-output [{:keys [exit out err]}]
  (letfn [(print-lines [output]
            (->> (string/split output (re-pattern (System/lineSeparator)))
                 (map println)
                 doall))]
    (print-lines out)
    (when-not (zero? exit)
      (print-lines err))))

(defn create-migration-pr!
  [client org service branch-name default-branch migration-details]
  (let [{:keys [number]} (pull/create-pull! client org {:repo   service
                                                        :title  migration-details
                                                        :branch branch-name
                                                        :base   default-branch
                                                        :body   ""})]
    (issue/add-label! client org service number "auto-migration")))

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

(defn- add-file-changes [changeset repo files]
  (->> files
       (map (fn [filepath] [filepath (slurp (str repo "/" filepath))]))
       (assoc changeset :changes)))

(defn- apply-migration! [{:keys [command name date] :as _migration} service]
  (let [{:keys [exit] :as output} (sh command :dir service)]
    (print-process-output output)
    (zero? exit)))

(defn- apply+commit-migration! [service base-changeset {:keys [name description] :as migration}]
  (let [commit-message (str "the ordnungsamt applying " name)
        files-for-pr   (files-to-commit service)
        success?       (apply-migration! migration service)]
    (when (and success? (has-changes? service))
      {:changeset   (-> base-changeset
                        (add-file-changes service files-for-pr)
                        (changeset/commit! commit-message))
       :description description})))

(defn- push-to-github! [client org service default-branch changeset migration-details]
  (let [target-branch  (str "auto-refactor-"
                            (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
                                     (new java.util.Date)))]
    (changeset/create-branch! changeset target-branch)
    (create-migration-pr! client org service target-branch default-branch migration-details)))

(def ^:private migrations
  [{:name          :sample-migration
    :description   ""
    :creation-date "2021-03-16"
    :command       ""}])

(defn run-migrations! [github-client organization service default-branch migrations]
  (let [run-migration!      (fn [[current-changeset details] migration]
                              (if-let [{:keys [changeset description]} (apply+commit-migration! service current-changeset migration)]
                                [changeset (conj details description)]
                                [current-changeset details]))
        [changeset details] (reduce run-migration!
                                    [(changeset/from-branch! github-client organization service default-branch) []]
                                    migrations)]
    (when (seq details)
      (push-to-github! github-client organization service default-branch changeset details))))

(defn -main [& [service default-branch]]
  (let [org "nubank"
        github-client (github-client/new-client {:token-fn (token/default-chain
                                                             "nu-secrets-br"
                                                             "go/agent/release-lib/bumpito_secrets.json")})]
    (run-migrations! github-client org service default-branch migrations)
    (shutdown-agents)
    (System/exit 0)))
