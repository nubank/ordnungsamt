(ns integration.apply-migrations-test
  (:require [cheshire.core :as json]
            [common-github.repository :as repository]
            clojure.string
            [clojure.test :refer :all]
            [common-github.state-flow-helper :refer [mock-github-flow]]
            [integration.aux.helpers :refer [file-absent? file-contains? file-exists? with-github-client]]
            [integration.aux.init :as aux.init]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [defflow match?] :as flow]))

(def base-dir "target/")
(def repository "example-repo")
(def repo-dir (str base-dir repository))
(def org "nubank")

(def migration-a
  {:title       "Uncage silence"
   :description "Silence doesn't need a container"
   :created-at  "2021-03-16"
   :id          1
   :command     ["../../test-resources/migration-a.sh"]})

(def failing-migration
  {:title       "Failing migration"
   :description "Change some things then fail"
   :created-at  "2021-03-17"
   :id          2
   :command     ["../../test-resources/migration-b.sh"]})

(def migration-c
  {:title       "move file + update contents"
   :description "Renames a file and also alters its contents"
   :created-at  "2021-03-17"
   :id          3
   :command     ["../../test-resources/migration-c.sh"]})

(def migration-d
  {:title       ""
   :description ""
   :id          4
   :created-at  "2021-03-29"
   :command     ["../../test-resources/migration-d.sh"]})

(def migrations [migration-a
                 failing-migration
                 migration-c
                 migration-d
                 ])

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

(defn add-label-request? [number]
  (fn [{:keys [path]}]
    (= path
       (str "/repos/" org "/" repository "/issues/" number))))

(def pr-body string?)

(defflow apply-two-migrations
  {:init       (aux.init/setup-service-directory! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-service-directory! base-dir repository)}
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-a migration-c]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :repos     {:orgs [{:name org
                                         :repos [{:name           repository
                                                  :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj" ".migrations.edn"] repo-dir))

                    (file-exists? org repository "master" ".migrations.edn")
                    (file-exists? org repository "master" "clouds.md")
                    (file-exists? org repository "master" "4'33")
                    (file-exists? org repository "master" "fanon.clj")

                    (with-github-client
                      #(core/run-migrations! % org repository "master" migration-branch base-dir migrations))

                    [migrations-contents (with-github-client
                                           #(repository/get-content! % org repository ".migrations.edn" {:branch migration-branch}))]
                    (match? #{0 1 3 4}
                            (set (map :id (read-string migrations-contents))))

                    (file-exists? org repository migration-branch "clouds.md")
                    (file-exists? org repository migration-branch "frantz_fanon.clj")

                    (file-absent? org repository migration-branch "fanon.clj")
                    (file-absent? org repository migration-branch "angela")
                    (file-absent? org repository migration-branch "4'33")))

