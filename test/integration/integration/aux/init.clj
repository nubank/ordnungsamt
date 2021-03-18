(ns integration.aux.init
  (:require [clojure.java.shell :refer [sh]]
            [common-github.protocols.client :as protocols.client]
            [common-github.repository :as repository]
            [common-github-mock.core :as mock]
            [common-github-mock.repos :as repos])
    (:import [common_github_mock.core Mock]))

(extend-protocol protocols.client/Client
  Mock
  (request [this req-map]
    (this req-map)))

(defn run-commands! [commands]
  (loop [[command & rest-commands] commands]
    (when command
      (let [{:keys [exit out err]} (apply sh command)]
        (if (zero? exit)
          (recur rest-commands)
          (println (str "FAILED running command:\n" command "\nerror message:\n" err)))))))

(defn cleanup-fake-service-repo! [base-dir repository]
  (fn [state] (run-commands! [["rm" "-rf" (str base-dir repository)]])))

(defn seed-mock-git-repo! [mock-client org repo]
  (let [{tree-sha :sha} (repository/create-tree! mock-client org repo {:tree [{:path "file"
                                                                               :mode "100644"
                                                                               :type "blob"
                                                                               :content "content"}]})
        {:keys [sha]}   (repository/create-commit! mock-client org repo {:message "a message"
                                                                         :tree    tree-sha})]
    (repository/create-reference! mock-client org repo {:ref "heads/master"
                                                        :sha sha})))


(defn seed-fake-service-repo! [base-dir repository]
  (fn []
    (let [repo-dir (str base-dir repository)
          org       "nubank"]
      (run-commands! [["cp" "-r" "test-resources/example-repo/" base-dir]
                      ["git" "init" "." :dir repo-dir]
                      ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir repo-dir]
                      ["git" "commit" "-m" "initial commit" :dir repo-dir]
                      ["git" "status" :dir repo-dir]])
      (let [mock-client (mock/new-mock)]
        (repos/setup-repo! mock-client org repository)
        (seed-mock-git-repo! mock-client org repository)
        {:system {:github-client mock-client}}))))
