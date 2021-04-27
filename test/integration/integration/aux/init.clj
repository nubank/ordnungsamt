(ns integration.aux.init
  (:require [clj-github.changeset :as changeset]
            [clj-github.httpkit-client :as client]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [integration.aux.data :as aux.data]
            [io.aviso.exception :as aviso.exception]
            [ordnungsamt.core :as core]
            [ordnungsamt.utils :as utils]
            [state-flow.api :as flow]
            state-flow.core))

(defn run-commands!
  "executes shell commands and returns the results from the last command a list"
  [commands]
  (reduce (fn [_previous-result command]
            (let [{:keys [exit err] :as result} (apply sh command)]
              (if (zero? exit)
                (utils/out->list result)
                (do (println (str "FAILED running command:\n" command "\nerror message:\n" err))
                    (reduced nil)))))
          nil
          commands))

(defn cleanup-service-directory! [base-dir repository]
  (fn [_state] (run-commands! [["rm" "-rf" base-dir]])))

(defn seed-mock-git-repo!
  "seeds the mock git repository's main branch with the provided files' contents"
  [mock-client org repo files repo-dir]
  (let [file-changes (reduce (fn [changeset filepath]
                               (let [contents (->> filepath
                                                   (str repo-dir "/")
                                                   io/file
                                                   slurp)]
                                 (changeset/put-content changeset filepath contents)))
                             (changeset/orphan mock-client org repo)
                             files)]
    (-> file-changes
        (changeset/commit! "initial commit")
        (changeset/create-branch! "main"))))

(defn setup-migrations-directory!
  "copies the directory of migrations into place"
  [base-dir migrations-directory]
  (run-commands! [["mkdir" "-p" (str base-dir migrations-directory)]
                  ["cp" "-r" (str "test-resources/" migrations-directory "/.") (str base-dir migrations-directory)]]))

(defn setup-service-directory!
  "copies the directory into place and sets up the local git server (needed to provide change information after running a migration)"
  [base-dir repository]
  (fn []
    (setup-migrations-directory! base-dir "service-migrations")
    (let [repo-dir    (str base-dir repository)
          mock-client (client/new-client {:token-fn (constantly "token")})]
      (run-commands! [["mkdir" "-p" repo-dir]
                      ["cp" "-r" (str "test-resources/" repository "/.") repo-dir]
                      ["git" "init" "." :dir repo-dir]
                      ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir repo-dir]
                      ["git" "-c" "commit.gpgsign=false" "commit" "-m" "initial commit" :dir repo-dir]])
      {:system {:github-client mock-client}})))

(defn- bound-log-error [& args]
  (let [default-frame-rules aviso.exception/*default-frame-rules*]
    (binding [aviso.exception/*default-frame-rules* (concat default-frame-rules [[:name #"clojure\.test.*" :hide]
                                                                                 [:name #"state-flow\..*" :hide]])]
      (apply state-flow.core/log-error args))))

(def error-reporting
  (comp
   state-flow.core/throw-error!
   bound-log-error
   (state-flow.core/filter-stack-trace state-flow.core/default-stack-trace-exclusions)))

(defmacro defflow
  [name & flows]
  `(flow/defflow ~name
     {:init       (setup-service-directory! aux.data/base-dir aux.data/repository)
      :fail-fast? true
      :on-error   error-reporting
      :cleanup    (cleanup-service-directory! aux.data/base-dir aux.data/repository)}
     ~@flows))
