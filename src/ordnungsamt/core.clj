(ns ordnungsamt.core
  (:require [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [common-core.time :as time]
            [common-github.changeset :as changeset]
            [common-github.httpkit-client :as github-client]
            [common-github.issue :as issue]
            [common-github.pull :as pull]
            [common-github.token :as token])
  (:gen-class))

(def org "nubank")

(defn- print-process-output [{:keys [exit out err]}]
  (letfn [(print-lines [output]
            (->> (string/split output (re-pattern (System/lineSeparator)))
                 (map println)
                 doall))]
    (print-lines out)
    (when-not (zero? exit)
      (print-lines err))))

(defn create-migration-pr!
  [client org service branch-name default-branch migration-name]
  (let [{:keys [number]} (pull/create-pull! client org {:repo   service
                                                        :title  migration-name
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

(defn- push-to-github! [client service default-branch migration-name]
  (let [target-branch   migration-name
        commit-message (str "the ordnungsamt applying " migration-name)
        files-for-pr   (files-to-commit service)]
    (-> (changeset/from-branch! client org service default-branch)
        (add-file-changes service files-for-pr)
        (changeset/commit! commit-message)
        (changeset/create-branch! target-branch))
    (create-migration-pr! client org service target-branch default-branch migration-name)))

(defn -main [& [service default-branch migration-name migration-shell-command]]
  (let [client (github-client/new-client {:token-fn (token/default-chain
                                                      "nu-secrets-br"
                                                      "go/agent/release-lib/bumpito_secrets.json")})]
    (let [{:keys [exit] :as output} (sh migration-shell-command :dir service)]
      (print-process-output output)

      (when (and (has-changes? service)
                 (zero? exit))
        (push-to-github! client service default-branch migration-name))

      (shutdown-agents)
      (System/exit exit))))
