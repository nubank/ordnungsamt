(ns integration.aux.helpers
  (:require [cats.core :as m]
            [clj-github.repository :as repository]
            [clj-github.state-flow-helper :refer [with-github-client]]
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

