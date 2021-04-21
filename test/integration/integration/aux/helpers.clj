(ns integration.aux.helpers
  (:require [cats.core :as m]
            [clj-github.repository :as repository]
            [state-flow.api :refer [flow match?] :as flow]))

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

