(ns migration
  "Given a directory and a set of variables to replace, find all files that
  reference those variables and run the replacement on them, updating the
  files with the results."
  (:require [grasp.api :as g]
            [umschreiben-clj.variables :as variables]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

(defn replace-symbols-with [target-ns replacements using]
  (map (fn [[find-symbol replace-with]] {:find-symbol  (symbol (str target-ns "/" find-symbol))
                                         :replace-with replace-with
                                         :using        using})
       replacements))

(def replacements
  [(replace-symbols-with 'clojure.string
                         [['upper-case 'my-upper-case]
                          ['lower-case 'my-lower-case]]
                         '[my.helpers :as helpers])

   (replace-symbols-with 'clojure.set
                         [['difference 'my-difference]]
                         '[my.helpers :as helpers])])

(defn find-usages [path symbols]
  (let [matches-symbol? (fn [sym]
                          (when (symbol? sym)
                            (contains? symbols (g/resolve-symbol sym))))]
    (->> (g/grasp path matches-symbol?)
         (map (comp :url meta))
         set)))

(defn files->nodes [files]
  (let [add-parsed-file (fn [acc file] (assoc acc file (-> file slurp p/parse-string-all)))]
    (reduce add-parsed-file {} files)))

(defn run-replaces-over-file [node replacements]
  (let [rename-in-node (fn [node {:keys [find-symbol replace-with using]}]
                         (variables/rename node find-symbol replace-with using))]
    (reduce rename-in-node node replacements)))

(defn collect-changes
  [search-directory replacements]
  (let [all-find-symbols  (set (map :find-symbol replacements))
        all-found-files   (find-usages search-directory all-find-symbols)
        file-nodes        (files->nodes all-found-files)
        acc-node-replaces (fn [file-nodes-acc [file node]]
                            (assoc file-nodes-acc file (run-replaces-over-file node replacements)))]
    (reduce acc-node-replaces {} file-nodes)))

(defn bulk-spit! [files+updated-nodes]
  (run! (fn [[file node]] (spit file (n/string node))) files+updated-nodes))

(defn -main [& [search-directory]]
  (bulk-spit! (collect-changes search-directory replacements)))
