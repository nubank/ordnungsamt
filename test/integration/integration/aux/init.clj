(ns integration.aux.init
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clj-github.changeset :as changeset]
            [clj-github.httpkit-client :as client]
            [ordnungsamt.core :as core]))

(defn run-commands!
  "executes shell commands and returns the results from the last command a list"
  [commands]
  (reduce (fn [_previous-result command]
            (let [{:keys [exit err] :as result} (apply sh command)]
              (if (zero? exit)
                (core/out->list result)
                (do (println (str "FAILED running command:\n" command "\nerror message:\n" err))
                    (reduced nil)))))
          nil
          commands))

(defn cleanup-service-directory! [base-dir repository]
  (fn [_state] (run-commands! [["rm" "-rf" (str base-dir repository)]])))

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
        (changeset/create-branch! "master"))))

(defn setup-service-directory!
  "copies the directory into place and sets up the local git server (needed to provide change information after running a migration)"
  [base-dir repository]
  (fn []
    (let [repo-dir    (str base-dir repository)
          _ (println repo-dir)
          mock-client (client/new-client {:token-fn (constantly "token")})]
      (run-commands! [["mkdir" "-p" repo-dir]
                      ["cp" (str "test-resources/" repository "/*") repo-dir]
                      ["git" "init" "." :dir repo-dir]
                      ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir repo-dir]
                      ["git" "-c" "commit.gpgsign=false" "commit" "-m" "initial commit" :dir repo-dir]])
      {:system {:github-client mock-client}})))

(defn print-ls [_state]
  (println (clojure.string/join "\n" (run-commands! [["ls" "-lRs" "target"]]))))