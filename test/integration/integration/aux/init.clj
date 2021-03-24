(ns integration.aux.init
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [common-github.changeset :as changeset]
            [common-github.httpkit-client :as client]
            [ordnungsamt.core :as core]))

(defn run-commands! [commands]
  (reduce (fn [_previous-result command]
            (let [{:keys [exit err] :as result} (apply sh command)]
              (if (zero? exit)
                (core/out->list result)
                (do (println (str "FAILED running command:\n" command "\nerror message:\n" err))
                    (reduced nil)))))
          nil
          commands))

(defn cleanup-fake-service-repo! [base-dir repository]
  (fn [_state] (run-commands! [["rm" "-rf" (str base-dir repository)]])))

(defn seed-mock-git-repo! [mock-client org repo files repo-dir]
  (let [file-changes (reduce (fn [changeset filepath]
                               (let [contents (->> filepath (str repo-dir "/") io/file slurp)]
                                 (changeset/put-content changeset filepath contents)))
                             (changeset/orphan mock-client org repo)
                             files)]
    (-> file-changes
        (changeset/commit! "initial commit")
        (changeset/create-branch! "master"))))

(defn seed-fake-service-repo! [base-dir repository]
  (fn []
    (let [repo-dir    (str base-dir repository)
          mock-client (client/new-client {:token-fn (constantly "token")})]
      (run-commands! [["cp" "-r" "test-resources/example-repo/" base-dir]
                      ["git" "init" "." :dir repo-dir]
                      ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir repo-dir]
                      ["git" "commit" "-m" "initial commit" :dir repo-dir]
                      ["ls" repo-dir]])
      {:system {:github-client mock-client}})))
