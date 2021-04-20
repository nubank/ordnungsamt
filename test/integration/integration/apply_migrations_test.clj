(ns integration.apply-migrations-test
  (:require [cheshire.core :as json]
            clojure.string
            [clojure.test :refer :all]
            [clj-github.repository :as repository]
            [clj-github.state-flow-helper :refer [mock-github-flow]]
            [integration.aux.helpers :refer [files-absent? files-present? with-github-client]]
            [integration.aux.init :as aux.init]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [defflow flow match?] :as flow]))

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

(def cleanup
  {:title       "cleanup"
   :command     ["../../test-resources/cleanup.sh"]})

(def migrations {:migrations [migration-a
                              failing-migration
                              migration-c
                              migration-d]
                 :post       [cleanup]})

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

(defn- migrations-present-in-log? [expected-migration-id-set]
  (flow "was the .migrations.edn file updated with the expected migrations?"
    [migrations-contents (with-github-client
                           #(repository/get-content!
                             % org repository core/applied-migrations-file {:ref migration-branch}))]
    (match? expected-migration-id-set
      (set (map :id (read-string migrations-contents))))))

(defflow applying+skipping-migrations
  {:init       (aux.init/setup-service-directory! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-service-directory! base-dir repository)}
  [:let [initial-files ["4'33" "clouds.md" "fanon.clj" core/applied-migrations-file]]]
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-a migration-c]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository initial-files repo-dir))

                    (files-present? org repository "master" initial-files)

                    (with-github-client
                      #(core/run-migrations! % org repository "master" migration-branch base-dir migrations))

                    (migrations-present-in-log? #{0 1 3 4})

                    (files-present? org repository migration-branch ["clouds.md"
                                                                     "frantz_fanon.clj"
                                                                     "cleanup-log"])
                    (files-absent? org repository migration-branch ["fanon.clj" "angela" "4'33"])))

(defflow creating-registry+applying-migrations
  {:init       (aux.init/setup-service-directory! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-service-directory! base-dir repository)}
  (flow "before we start: remove file tracking applied migrations"
    (flow/invoke #(aux.init/run-commands! [["rm" core/applied-migrations-file :dir repo-dir]])))
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-d]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repo-dir))

                    (flow "running migrations creates the .migrations.edn file when it doesn't already exist"
                      (files-absent? org repository "master" [core/applied-migrations-file])
                      (with-github-client
                        #(core/run-migrations! % org repository "master" migration-branch base-dir {:migrations [migration-d]}))
                      (files-present? org repository migration-branch [core/applied-migrations-file]))

                    (migrations-present-in-log? #{4})))


(defflow failing-migration-doesnt-run-post-steps
  {:init       (aux.init/setup-service-directory! base-dir repository)
   :fail-fast? true
   :cleanup    (aux.init/cleanup-service-directory! base-dir repository)}
  (mock-github-flow {:initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repo-dir))

                    (flow "running a failing migration means the post step is skipped"
                      (with-github-client
                        #(core/run-migrations! % org repository "master" migration-branch base-dir
                                               {:migrations [failing-migration]
                                                :post       [cleanup]}))
                      (files-absent? org repository migration-branch ["cleanup-log"]))))
