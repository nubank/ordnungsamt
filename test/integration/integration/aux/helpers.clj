(ns integration.aux.helpers
  (:require [clj-github.repository :as repository]
            [clj-github.state-flow-helper :refer [with-github-client]]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [flow match?] :as flow]))

(defn files-present? [org repository branch files]
  (flow/for [file files]
    (flow (str "file " file " is present in `" branch "` branch")
      (match? (comp not nil?)
        (with-github-client #(repository/get-content! % org repository file {:ref branch}))))))

(defn files-absent? [org repository branch files]
  (flow/for [file files]
    (flow (str "file " file " is absent in `" branch "` branch")
      (match? nil?
        (with-github-client #(repository/get-content! % org repository file {:ref branch}))))))

(defn branch-absent? [org repository branch]
  (flow "and the branch is deleted"
    (match? {:status 404}
      (with-github-client
        #(try (repository/get-branch! % org repository branch)
              (catch clojure.lang.ExceptionInfo e
                (:response (ex-data e))))))))

(defn migrations-present-in-log? [org repository branch expected-migration-id-set]
  (flow "was the .migrations.edn file updated with the expected migrations?"
    [migrations-contents (with-github-client
                           #(repository/get-content!
                             % org repository core/applied-migrations-file {:ref branch}))]
    (match? expected-migration-id-set
      (set (map :id (read-string migrations-contents))))))

