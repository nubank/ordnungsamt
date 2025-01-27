(ns ordnungsamt.core
  (:require [clj-github.changeset :as changeset]
            [clj-github.httpkit-client :as github-client]
            [clj-github.issue :as issue]
            [clj-github.pull :as pull]
            [clj-github.token :as token]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.pprint :refer [pprint]]
            clojure.set
            [ordnungsamt.close-open-prs :refer [close-open-prs!]]
            [ordnungsamt.render :as render]
            [ordnungsamt.run-locally :as run-locally]
            [ordnungsamt.utils :as utils])
  (:gen-class))

(def applied-migrations-file ".migrations.edn")

(defn- create-migration-pr!
  [client
   {:keys [repo branch org] :as _changeset}
   migration-details
   base-branch]
  (let [{:keys [pr-title pr-description]} (render/render-pr-description! migration-details)
        {:keys [number]} (pull/create-pull! client org {:repo   repo
                                                        :title  pr-title
                                                        :branch branch
                                                        :base   base-branch
                                                        :body   pr-description})]
    (issue/add-label! client org repo number "auto-migration")))

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
  (let [sh->set   (fn [& sh-args] (->> (apply sh sh-args)
                                       utils/out->list
                                       (into #{})))
        modified  (sh->set "git" "ls-files" "--modified" "--exclude-standard" :dir dir)
        deleted   (sh->set "git" "ls-files" "--deleted" "--exclude-standard" :dir dir)
        added     (sh->set "git" "ls-files" "--others" "--exclude-standard" :dir dir)]
    {:modified (-> modified
                   (conj applied-migrations-file)
                   (clojure.set/difference deleted)
                   (clojure.set/difference added))
     :deleted  deleted
     :added    added}))

(defn- has-changes? [dir]
  (let [fileset (->> dir
                     files-to-commit
                     vals
                     (apply clojure.set/union))]
    (seq (clojure.set/difference fileset #{applied-migrations-file}))))

(defn- drop-changes! [dir]
  (let [added (utils/out->list (sh "git" "ls-files" "--others" "--exclude-standard" :dir dir))]
    (run! #(sh "rm" % :dir dir) added))
  (sh "git" "stash" :dir dir)
  (sh "git" "stash" "drop" :dir dir))

(defn- local-commit! [title {:keys [modified deleted added]} dir]
  (run! (fn [file] (utils/sh! "git" "add" "-f" file :dir dir))
        (concat modified added))
  (run! (fn [file] (utils/sh! "git" "rm" file :dir dir))
        deleted)
  (utils/sh! "git" "-c" "commit.gpgsign=false" "commit" "--author=\"ordnungsamt <order-department@not-real.com>\"" "-m" (str "migration applied: " title) :dir dir))

(defn- add-file-changes [changeset repo files]
  (reduce (fn [changeset filepath]
            (let [file (io/file (str repo "/" filepath))]
              (if (.exists file)
                (changeset/put-content changeset filepath (slurp file))
                (changeset/delete changeset filepath))))
          changeset
          files))

(defn- print-with-header [event-type message start?]
  (let [header              "------------------------------------------"
        message-with-header (if start?
                              (str header "\n" event-type ": " message "\n")
                              (str "\n" event-type ": " message "\n" header "\n"))]
    (println message-with-header)))

(defn- apply-migration! [{:keys [command title] :as _migration} dir]
  (print-with-header "START" title true)
  (let [{:keys [exit] :as output} (try
                                    (apply sh (concat command [:dir dir]))
                                    (catch java.io.IOException e
                                      {:exit 1
                                       :err (str e)}))
        success?                  (zero? exit)]
    (utils/print-process-output output)
    (when (not success?)
      (print-with-header "ERROR" title false)
      (drop-changes! dir))
    success?))

(defn- apply+commit-migration!
  [repo-dir
   {:keys [branch] :as base-changeset}
   {:keys [title] :as migration}]
  (let [commit-message (str "the ordnungsamt applying " title)
        success?       (apply-migration! migration repo-dir)]
    (when success?
      (if (has-changes? repo-dir)
        (do
          (print-with-header "CHANGES" title false)
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
            (local-commit! title files-for-pr repo-dir)
            changeset'))
        (print-with-header "NO CHANGES" title false)))))

(defn- run-migration! [repo-dir [current-changeset details] migration]
  (if-let [{:keys [changeset description]} (apply+commit-migration! repo-dir current-changeset migration)]
    [changeset (conj details description)]
    [current-changeset details]))

(defn- create-branch+run-base-migrations!
  [github-client organization service default-branch target-branch repo-dir migrations]
  (let [base-changeset (-> github-client
                           (changeset/from-branch! organization service default-branch)
                           (changeset/create-branch! target-branch))]
    (reduce (partial run-migration! repo-dir)
            [base-changeset []]
            migrations)))

(defn- filter-registered-migrations [repo-dir]
  (let [registered-migrations (->> (read-registered-migrations repo-dir)
                                   (map :id)
                                   set)]
    (fn [{:keys [id]}] (not (contains? registered-migrations id)))))

(defn- filter-opt-in [service]
  (fn [{:keys [opt-in]}] (or (nil? opt-in)
                             (contains? opt-in service))))

(defn- compose-filters [filters]
  (reduce (fn [acc f] (fn [value] (and (acc value) (f value)))) (constantly true) filters))

(defn run-migrations!* [github-client organization service default-branch target-branch repo-dir migrations]
  (let [[changeset details] (create-branch+run-base-migrations!
                             github-client organization service default-branch target-branch repo-dir (:migrations migrations))]
    (if (seq details)
      (let [[changeset' _] (reduce (partial run-migration! repo-dir)
                                   [changeset details]
                                   (:post migrations))]
        (create-migration-pr! github-client changeset' details default-branch))
      (changeset/delete-branch! changeset))))

(defn run-migrations! [github-client organization service default-branch target-branch repo-dir migrations]
  (let [migrations-filter (compose-filters [(filter-registered-migrations repo-dir)
                                            (filter-opt-in service)])
        to-run-migrations (filter migrations-filter (:migrations migrations))]
    (run-migrations!* github-client organization service default-branch target-branch repo-dir (assoc migrations :migrations to-run-migrations))))

(defn resolve-token-fn [token-fn]
  (when token-fn
    (let [token-fn' (symbol token-fn)]
      (require (symbol (namespace token-fn')))
      (resolve token-fn'))))

(def default-token-fn
  (token/chain [token/hub-config token/env-var]))

(defn- read-migrations! [migrations-directory]
  (-> migrations-directory
      (str "/migrations.edn")
      slurp
      read-string))

(defn load+run-migrations! [github-client org service default-branch repository-directory migrations-directory]
  (let [target-branch (str "auto-refactor-" (utils/now))
        migrations    (read-migrations! migrations-directory)]
    (run-migrations! github-client org service default-branch target-branch repository-directory migrations)))

(defn- exit! []
  (shutdown-agents)
  (System/exit 0))

;; (in-ns 'clj-github.httpkit-client);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn request
;;   "Sends a synchronous request to GitHub, largely as a wrapper around HTTP Kit.

;;   In addition to normal HTTP Kit keys in the request map, two additional keys are added.

;;   :path - used to create the :url key for HTTP kit; this is the path relative to https://api.github.com.

;;   :throw? - if true (the default), then non-success status codes (codes outside the range of 200 to 204)
;;     result in a thrown exception. If set to false, then the response is returned, regardless and the
;;     caller can decide what to do with failure statuses."
;;   [client req-map]
;;   (let [{:keys [throw?]
;;          :or   {throw? true}} req-map
;;         {:keys [error status body opts] :as response} @(httpkit/request (prepare client req-map))]
;;     (cond
;;       error
;;       (throw (ex-info "Failure sending request to GitHub"
;;                       {:opts opts}
;;                       error))

;;       (and throw?
;;            (not (success-codes status)))
;;       (throw (ex-info "Request to GitHub failed"
;;                       {:response (-> response
;;                                      (assoc-in [:opts :headers "Authorization"] "<REDACTED>")
;;                                      (update-in [:opts :body] #(str "<JSON PAYLOAD WITH " (count %) " BYTES>"))) #_(select-keys response [:status :body])
;;                        :opts     (-> opts
;;                                      (assoc-in [:headers "Authorization"] "<REDACTED>")
;;                                      (update-in [:body] #(str "<JSON PAYLOAD WITH " (count %) " BYTES>")))}
;;                       error))

;;       :else
;;       (assoc response :body (parse-body (content-type response) body)))))

;; (in-ns 'ordnungsamt.core);;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(in-ns 'clj-github.repository)
(defn create-tree!
  "Creates a new tree.

  Look at https://developer.github.com/v3/git/trees/#create-a-tree for details about the parameters and response format"
  [client org repo params]
  (fetch-body! client {:method :post
                       :path (format "/repos/%s/%s/git/trees" org repo)
                       :headers {"Accept" "application/vnd.github+json"
                                 "X-GitHub-Api-Version" "2022-11-28"}
                       :body params}))

(in-ns 'ordnungsamt.core)

(defn -main [& [org service default-branch repository-directory migrations-directory token-fn run-locally?]]
  (if run-locally?
    (run-locally/run-locally!
     org service default-branch (fn [client] (load+run-migrations!
                                              client org service default-branch repository-directory migrations-directory)))
    (let [token-fn      (or (resolve-token-fn token-fn)
                            default-token-fn)
          github-client (github-client/new-client {:token-fn token-fn})]
      (close-open-prs! github-client org service)
      (load+run-migrations! github-client org service default-branch repository-directory migrations-directory)))
  (exit!))
