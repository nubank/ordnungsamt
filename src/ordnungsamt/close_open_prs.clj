(ns ordnungsamt.close-open-prs
  (:require [clj-github.pull :as pull]
            [clj-github.repository :as repository]))

(defn close-pr-and-delete-branch! [client org service pr]
  (pull/close-pull! client org service (:number pr))
  (repository/delete-reference! client org service (str "heads/" (pull/pull->branch-name pr))))

(defn close-open-prs! [client org service]
  (->> (pull/get-open-pulls! client org service)
       (filter #(pull/has-label? % "auto-migration"))
       (run! (partial close-pr-and-delete-branch! client org service))))
