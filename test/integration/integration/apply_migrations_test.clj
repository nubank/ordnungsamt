(ns integration.apply-migrations-test
  (:require [cats.core :as m]
            [cheshire.core :as json]
            clojure.string
            [clojure.test :refer :all]
            [common-github.repository :as repository]
            [common-github.state-flow-helper :refer [mock-github-flow]]
            [integration.aux.init :as aux.init]
            [matcher-combinators.standalone :as standalone]
            [ordnungsamt.core :as core]
            [state-flow.api :refer [defflow flow match?] :as flow]))

;; github client stuff ------------------
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

;; tests ------------------

(def base-dir "target/")
(def repository "example-repo")
(def repo-dir (str base-dir repository))
(def org "nubank")

(defn file-exists? [branch file]
  (flow (str "file '" file "' is present")
    (match? (comp not nil?)
      (with-github-client #(repository/get-content! % org repository file {:branch branch})))))

(defn file-absent? [branch file]
  (flow (str "file '" file "' is absent")
    (match? nil?
      (with-github-client #(repository/get-content! % org repository file {:branch branch})))))

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

(defn includes-migration-info? [text migration]
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
                     :repos
                     {:orgs [{:name org
                              :repos [{:name           repository
                                       :default_branch "master"}]}]}}

                    (with-github-client
                      #(aux.init/seed-mock-git-repo! % org repository ["4'33" "clouds.md" "fanon.clj"] repo-dir))

                    (file-exists? "master" "clouds.md")
                    (file-exists? "master" "4'33")
                    (file-exists? "master" "fanon.clj")

                    (with-github-client
                      #(core/run-migrations! % org repository "master" migration-branch base-dir migrations))

                    (file-exists? migration-branch "clouds.md")
                    (file-exists? migration-branch "frantz_fanon.clj")

                    (file-absent? migration-branch "fanon.clj")
                    (file-absent? migration-branch "angela")
                    (file-absent? migration-branch "4'33")))

