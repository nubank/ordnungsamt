(ns integration.apply-migrations-test
  (:require [cheshire.core :as json]
            clojure.string
            [clojure.test :refer :all]
            [common-github.state-flow-helper :refer [mock-github-flow]]
            [integration.aux.helpers :refer [file-absent? file-exists? with-github-client]]
            [integration.aux.init :as aux.init]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [defflow] :as flow]))

(def base-dir "target/")
(def repository "example-repo")
(def repo-dir (str base-dir repository))
(def org "nubank")

(def migration-a
  {:title       "Uncage silence"
   :description "Silence doesn't need a container"
   :created-at  "2021-03-16"
   :command     ["../../test-resources/migration-a.sh"]})

(def failing-migration
  {:title       "Failing migration"
   :description "Change some things then fail"
   :created-at  "2021-03-17"
   :command     ["../../test-resources/migration-b.sh"]})

(def migration-c
  {:title       "move file + update contents"
   :description "Renames a file and also alters its contents"
   :created-at  "2021-03-17"
   :command     ["../../test-resources/migration-c.sh"]})

(def migrations [migration-a failing-migration migration-c])

(def migration-branch "auto-refactor-2021-03-24")

(defn- includes-migration-info? [text migration]
  (let [migration-texts ((juxt :description :title :created-at) migration)]
    (->> migration-texts
         (map #(clojure.string/includes? text %))
         (every? identity))))

(defn create-pr-request? [title-prefix migrations]
  (fn [request]
    (and (= (:path request)
            (str "/repos/" org "/" repository "/pulls"))
         (standalone/match?
           {:title #(clojure.string/starts-with? % title-prefix)
            :body  (fn [txt] (every? (partial includes-migration-info? txt)
                                     migrations))}
           (json/parse-string (:body request) keyword)))))

(defn add-label-request? [{:keys [path]}]
  (= path
     (str "/repos/" org "/" repository "/issues")))

(def pr-body string?)

(defflow apply-two-migrations
  {:init       (aux.init/seed-fake-service-repo! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-fake-service-repo! base-dir repository)}
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-a migration-c]) "{}"
                                 add-label-request? "{}"]
                     :repos     {:orgs [{:name org
                                         :repos [{:name           repository
                                                  :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repo-dir))

                    (file-exists? org repository "master" "clouds.md")
                    (file-exists? org repository "master" "4'33")
                    (file-exists? org repository "master" "fanon.clj")

                    (with-github-client
                      #(core/run-migrations! % org repository "master" migration-branch base-dir migrations))

                    (file-exists? org repository migration-branch "clouds.md")
                    (file-exists? org repository migration-branch "frantz_fanon.clj")

                    (file-absent? org repository migration-branch "fanon.clj")
                    (file-absent? org repository migration-branch "angela")
                    (file-absent? org repository migration-branch "4'33")))

