(ns integration.aux.git
  (:require [clojure.java.shell :refer [sh]]
            [ordnungsamt.core :as core]))

(defn git-commit-messages [repo-dir]
  (core/out->list (sh "git" "log" "--pretty=format:%s" :dir repo-dir)))

(defn git-files-changed [repo-dir]
  ;; TODO allow range
  (core/out->list (sh "git" "log" "--name-status" "--pretty=format:" "HEAD" :dir repo-dir)))

