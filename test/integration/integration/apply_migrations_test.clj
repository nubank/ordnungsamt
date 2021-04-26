(ns integration.apply-migrations-test
  (:require [cheshire.core :as json]
            [clj-github.repository :as repository]
            [clj-github.state-flow-helper :refer [mock-github-flow with-github-client]]
            clojure.string
            [clojure.test :refer :all]
            [integration.aux.helpers :refer [files-absent? files-present?]]
            [integration.aux.init :as aux.init :refer [defflow]]
            [integration.aux.data :refer [repository repository-dir migrations-dir org]]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [flow match?] :as flow]))

(def migration-a
  {:title       "Uncage silence"
   :description "Silence doesn't need a container"
   :created-at  "2021-03-16"
   :id          1
   :command     ["../service-migrations/migration-a.sh"]})

(def failing-migration
  {:title       "Failing migration"
   :description "Change some things then fail"
   :created-at  "2021-03-17"
   :id          2
   :command     ["../service-migrations/migration-b.sh"]})

(def migration-c
  {:title       "move file + update contents"
   :description "Renames a file and also alters its contents"
   :created-at  "2021-03-17"
   :id          3
   :command     ["../service-migrations/migration-c.sh"]})

(def migration-d
  {:title       ""
   :description ""
   :id          4
   :created-at  "2021-03-29"
   :command     ["../service-migrations/migration-d.sh"]})

(def cleanup
  {:title       "cleanup"
   :command     ["../service-migrations/cleanup.sh"]})

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
  [:let [initial-files ["4'33" "clouds.md" "fanon.clj" core/applied-migrations-file]]]
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-a migration-c]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "main"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository initial-files repository-dir))

                    (files-present? org repository "main" initial-files)

                    (with-github-client
                      #(core/run-migrations! % org repository "main" migration-branch repository-dir migrations))

                    (migrations-present-in-log? #{0 1 3 4})

                    (files-present? org repository migration-branch ["clouds.md"
                                                                     "frantz_fanon.clj"
                                                                     "cleanup-log"])
                    (files-absent? org repository migration-branch ["fanon.clj" "angela" "4'33"])))

(defflow creating-registry+applying-migrations
  (flow "before we start: remove file tracking applied migrations"
    (flow/invoke #(aux.init/run-commands! [["rm" core/applied-migrations-file :dir repository-dir]])))
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-d]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "main"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repository-dir))

                    (flow "running migrations creates the .migrations.edn file when it doesn't already exist"
                      (files-absent? org repository "main" [core/applied-migrations-file])
                      (with-github-client
                        #(core/run-migrations! % org repository "main" migration-branch repository-dir {:migrations [migration-d]}))
                      (files-present? org repository migration-branch [core/applied-migrations-file]))

                    (migrations-present-in-log? #{4})))

(defflow failing-migration-doesnt-run-post-steps
  (mock-github-flow {:initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "main"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repository-dir))

                    (flow "running a failing migration means the post step is skipped"
                      (with-github-client
                        #(core/run-migrations! % org repository "main" migration-branch repository-dir
                                               {:migrations [failing-migration]
                                                :post       [cleanup]}))
                      (files-absent? org repository migration-branch ["cleanup-log"])
                      (flow "and the branch is deleted"
                        (match? {:status 404}
                          (with-github-client
                            #(try (repository/get-branch! % org repository migration-branch)
                                  (catch clojure.lang.ExceptionInfo e
                                    (:response (ex-data e))))))))))

(defflow applying-opt-in-migrations
  (flow "before we start: remove file tracking applied migrations"
    (flow/invoke #(aux.init/run-commands! [["rm" core/applied-migrations-file :dir repository-dir]])))
  (mock-github-flow {:responses [(create-pr-request? "[Auto] Refactors -" [migration-d]) "{\"number\": 2}"
                                 (add-label-request? 2) "{}"]
                     :initial-state {:orgs [{:name org
                                             :repos [{:name           repository
                                                      :default_branch "main"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repository-dir))

                    (flow "applies migrations where repository has opt-in"
                      (with-github-client
                        #(core/run-migrations! % org repository "main" migration-branch repository-dir
                                               {:migrations [(assoc migration-c :opt-in #{"another-repository"})
                                                             (assoc migration-d :opt-in #{repository})]}))

                      (files-present? org repository migration-branch ["4'33"])
                      (files-absent? org repository migration-branch ["frantz_fanon.clj"]))))

(defflow run-main-locally
  (flow/invoke
    (with-redefs [core/exit! (constantly (fn [] nil))]
      (core/-main "nubank" repository "main" repository-dir migrations-dir nil true))))
