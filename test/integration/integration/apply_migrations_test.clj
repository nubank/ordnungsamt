(ns integration.apply-migrations-test
  (:require [cheshire.core :as json]
            [clj-github.state-flow-helper :refer [mock-github-flow with-github-client]]
            clojure.string
            [clojure.test :refer :all]
            [integration.aux.data :refer [migrations-dir org repository repository-dir]]
            [integration.aux.helpers :refer [branch-absent? files-absent? files-present? migrations-present-in-log?]]
            [integration.aux.init :as aux.init :refer [defflow]]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [flow match?] :as flow]))

(def migrations (#'core/read-migrations! "test-resources/service-migrations"))

(defn- find-with-id
  [desired-id {:keys [migrations]}]
  (first (filter (fn [{:keys [id]}] (= desired-id id)) migrations)))

(def remove-file-migration (find-with-id 1 migrations))
(def failing-migration     (find-with-id 2 migrations))
(def rename-file-migration (find-with-id 3 migrations))
(def noop-migration        (find-with-id 4 migrations))
(def cleanup               (first (:post migrations)))

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

(defn- seed-files! [initial-files]
  (with-github-client
    #(aux.init/seed-mock-git-repo! % org repository initial-files repository-dir)))

(defn- run-migrations! [migrations]
  (with-github-client
    #(core/run-migrations! % org repository "main" migration-branch repository-dir migrations)))

(def ^:private remove-dot-migrations-file!
  (flow "before we start: remove file tracking applied migrations"
    (flow/invoke #(aux.init/run-commands! [["rm" core/applied-migrations-file :dir repository-dir]]))))

(defflow applying+skipping-migrations
  [:let [initial-files ["4'33" "clouds.md" "fanon.clj" core/applied-migrations-file]]]
  (mock-github-flow
   {:responses [(create-pr-request? "[Auto] Refactors -" [remove-file-migration]) "{\"number\": 2}"
                (add-label-request? 2) "{}"]
    :initial-state {:orgs [{:name org
                            :repos [{:name           repository
                                     :default_branch "main"}]}]}}

   (seed-files! initial-files)
   (files-present? org repository "main" initial-files)
   (run-migrations! migrations)
   (migrations-present-in-log? org repository migration-branch #{0 1 3})

   (flow "migration renaming `fanon.clj` file was skipped"
     (files-present? org repository migration-branch ["clouds.md" "fanon.clj" "cleanup-log"])
     (files-absent? org repository migration-branch ["franz_fanon.clj" "angela" "4'33"]))))

(defflow creating-registry+applying-migrations
  remove-dot-migrations-file!
  (mock-github-flow
   {:responses [(create-pr-request? "[Auto] Refactors -" [rename-file-migration]) "{\"number\": 2}"
                (add-label-request? 2) "{}"]
    :initial-state {:orgs [{:name org
                            :repos [{:name           repository
                                     :default_branch "main"}]}]}}

   (seed-files! ["fanon.clj"])

   (flow "running migrations creates the .migrations.edn file when it doesn't already exist"
     (files-absent? org repository "main" [core/applied-migrations-file])
     (run-migrations! {:migrations [rename-file-migration]})
     (files-present? org repository migration-branch ["frantz_fanon.clj" core/applied-migrations-file]))

   (migrations-present-in-log? org repository migration-branch #{3})))

(defflow failing-migration-doesnt-run-post-steps
  (mock-github-flow
   {:initial-state {:orgs [{:name org
                            :repos [{:name           repository
                                     :default_branch "main"}]}]}}

   (seed-files! ["4'33" "clouds.md" "fanon.clj"])

   (flow "running a failing migration means the post step is skipped"
     (run-migrations! {:migrations [failing-migration]
                       :post       [cleanup]})
     (files-absent? org repository migration-branch ["cleanup-log"])

     (branch-absent? org repository migration-branch))))

(defflow empty-migration-doesnt-run-post-steps
  remove-dot-migrations-file!
  (mock-github-flow
   {:initial-state {:orgs [{:name org
                            :repos [{:name           repository
                                     :default_branch "main"}]}]}}

   (seed-files! ["4'33" "clouds.md" "fanon.clj"])

   (flow "running a failing migration means the post step is skipped"
     (run-migrations! {:migrations [noop-migration]
                       :post       [cleanup]})
     (files-absent? org repository migration-branch ["cleanup-log"])
     (branch-absent? org repository migration-branch))))

(defflow applying-opt-in-migrations
  remove-dot-migrations-file!
  (mock-github-flow
   {:responses [(create-pr-request? "[Auto] Refactors -" [remove-file-migration]) "{\"number\": 2}"
                (add-label-request? 2) "{}"]
    :initial-state {:orgs [{:name org
                            :repos [{:name           repository
                                     :default_branch "main"}]}]}}

   (seed-files! ["4'33" "clouds.md" "fanon.clj"])

   (files-present? org repository "main" ["4'33"])
   (flow "applies migrations where repository has opt-in"
     (run-migrations! {:migrations [(assoc rename-file-migration :opt-in #{"another-repository"})
                                    (assoc remove-file-migration :opt-in #{repository})]})
     (flow "the remove-file migration ran, but the rename-file migration didn't"
       (files-present? org repository migration-branch ["fanon.clj"])
       (files-absent? org repository migration-branch ["4'33" "frantz_fanon.clj"])))))

(defflow run-main-locally
  (flow "test main entry point for doing a local dry-run"
    ;; test doesn't have assertions but shouldn't raise an exception
    (flow/invoke
     (with-redefs [core/exit! (constantly (fn [] 0))]
       (core/-main "nubank" repository "main" repository-dir migrations-dir nil true)))))
