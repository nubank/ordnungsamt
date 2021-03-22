(ns integration.apply-migrations-test
  (:require [cats.core :as m]
            [clojure.test :refer :all]
            [common-github.changeset :as changeset]
            [common-github.repository :as repository]
            [integration.aux.git :as aux.git]
            [integration.aux.init :as aux.init]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [flow match? defflow] :as flow]))

;; github client stuff ------------------
(defn with-resource
  "Gets a value from the state using resource-fetcher and passes it as an
   argument to <user-fn>."
  [resource-fetcher user-fn]
  (assert (ifn? resource-fetcher) "First argument must be callable")
  (assert (ifn? user-fn) "Second argument must be callable")
  (m/mlet [resource (flow/get-state resource-fetcher)]
    (m/return (user-fn resource))))

(def ^:private get-github-client (comp :github-client :system))

(defn with-github-client
  [github-client-fn]
  (with-resource get-github-client github-client-fn))

;; tests ------------------

(def base-dir "target/")
(def repository "example-repo")
(def repo-dir (str base-dir repository))
(def org "nubank")

(defn file-exists? [file]
  (flow (str "file '" file "' is present")
        (match? (comp not nil?)
                (with-github-client #(repository/get-content! % org repository file)))))

(defn file-absent? [file]
  (flow (str "file '" file "' is absent")
        (match? nil?
                (with-github-client #(repository/get-content! % org repository file)))))

(def migrations
  [{:title       "Uncage silence"
    :description "Silence doesn't need a container"
    :created-at  "2021-03-16"
    :command     ["../../test-resources/migration-a.sh"]}
   #_{:title       "Failing migration"
    :description "Change some things then fail"
    :created-at  "2021-03-17"
    :command     ["../../test-resources/migration-b.sh"]}
   #_{:title       "move file + update contents"
    :description "Renames a file and also alters its contents"
    :created-at  "2021-03-17"
    :command     ["../../test-resources/migration-c.sh"]}
   ])

(defflow apply-two-migrations
  {:init       (aux.init/seed-fake-service-repo! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-fake-service-repo! base-dir repository)}

  (file-exists? "clouds.md")
  (file-exists? "4'33")
  (file-exists? "fanon.clj")

  (with-github-client
    #(core/run-migrations! % org repository "master" base-dir migrations))

  (file-exists? "clouds.md")
  (file-absent? "4'33")

  #_(match? ["initial commit"]
          (aux.git/git-commit-messages repo-dir))

  #_(match? ["A\t4'33"
           "A\tclouds.md"
           "A\tfanon.clj"]
          (aux.git/git-files-changed repo-dir)))
