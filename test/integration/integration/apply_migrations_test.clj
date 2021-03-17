(ns integration.apply-migrations-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :refer [sh]]
            [state-flow.api :refer [flow match?] :as flow]
            [ordnungsamt.core :as core]))

(def base-dir "target/")
(def repository "example-repo")
(def repo-dir (str base-dir repository))

(defn- run-commands! [commands]
  (loop [[command & rest-commands] commands]
    (when command
      (let [{:keys [exit out err]} (apply sh command)]
        (if (zero? exit)
          (recur rest-commands)
          (println (str "FAILED running command:\n" command "\nerror message:\n" err)))))))

(defn cleanup-fake-service-repo! [state]
  (run-commands! [["rm" "-rf" repo-dir]]))

(defn seed-fake-service-repo! []
  (run-commands! [["cp" "-r" "test-resources/example-repo/" base-dir]
                  ["git" "init" "." :dir repo-dir]
                  ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir repo-dir]
                  ["git" "commit" "-m" "initial commit" :dir repo-dir]
                  ["git" "status" :dir repo-dir]])
  {})

(defmacro defflow
  [name & flows]
  `(flow/defflow ~name {:init       seed-fake-service-repo!
                        :fail-fast? true
                        :cleanup    cleanup-fake-service-repo!}

     ~@flows))

(def client nil) ;; TODO

(def migrations
  [{:title       "Uncage silence"
    :description "Silence doesn't need a container"
    :created-at  "2021-03-16"
    :command     ["migration-a.sh"]}
   {:title       "Failing migration"
    :description "Change some things then fail"
    :created-at  "2021-03-17"
    :command     ["migration-b.sh"]}
   {:title       "move file + update contents"
    :description "Renames a file and also alters its contents"
    :created-at  "2021-03-17"
    :command     ["migration-c.sh"]}])

(defn- git-commit-messages []
  (core/out->list (sh "git" "log" "--pretty=format:%s" :dir repo-dir)))

(defn- git-files-changed []
  ;; TODO allow range
  (core/out->list (sh "git" "log" "--name-status" "--pretty=format:" "HEAD" :dir repo-dir)))

(defflow apply-two-migrations
  ; (core/run-migrations! client "nubank" repository "master" base-dir migrations)

  (match? ["initial commit"]
          (git-commit-messages))

  (match? ["A\t4'33"
           "A\tclouds.md"
           "A\tfanon.clj"]
          (git-files-changed)))
