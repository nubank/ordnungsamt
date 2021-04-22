(ns ordnungsamt.ops.close-all-prs
  (:require [clj-github.httpkit-client :as github-client]
            [ordnungsamt.close-open-prs :refer [close-open-prs!]]))

(defn -main [& [org file-with-repositories]]
  (let [github-client   (github-client/new-client {:token (System/getenv "GITHUB_TOKEN")})
        repositories    (-> file-with-repositories
                            slurp
                            read-string)
        close-for-repo! (fn [repo]
                          (println "closing for" repo)
                          (close-open-prs! github-client org (str repo)))]
    (run! close-for-repo! repositories)))
