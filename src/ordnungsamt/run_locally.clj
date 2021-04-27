(ns ordnungsamt.run-locally
  (:require [clj-github-mock.core :as mock.core]
            [clj-github.changeset :as changeset]
            [clj-github.httpkit-client :as github-client]
            [clj-github.repository :as repository]
            [clj-github.test-helpers :as test-helpers]
            [org.httpkit.fake :as fake]))

(defmacro with-client [[client initial-state responses] & body]
  `(fake/with-fake-http
     ~(into
       []
       (concat (test-helpers/build-spec responses)
               `[#"^https://api.github.com/.*" (mock.core/httpkit-fake-handler {:initial-state ~initial-state})]))
     (let [~client (github-client/new-client {:token-fn (constantly "token")})]
       ~@body)))

(defn- commit-empty!
  "adaptation of clj-github.changeset/commit! that instead of skipping commits with
  empty changesets, sends them to the server"
  [{:keys [client org repo base-revision changes] :as changeset} message]
  (let [{:keys [sha]} (repository/commit!
                       client org repo base-revision {:message message :tree []})]
    (-> changeset
        (dissoc :changes)
        (assoc :base-revision sha))))

(defn run-locally!
  "Run migrations without communicating with GitHub.

  This will change local files and make local git commits"
  [org service default-branch run-fn]
  (let [pulls-path? (fn [{:keys [path]}] (= path (str "/repos/" org "/" service "/pulls")))
        issues-req? (fn [{:keys [path]}] (clojure.string/starts-with?
                                          path (str "/repos/" org "/" service "/issues/")))]
    (with-client [client
                  {:orgs [{:name org :repos [{:name           service
                                              :default_branch default-branch}]}]}
                  [pulls-path?  "{\"number\": 2}"
                   issues-req?  "{}"]]
      (-> (changeset/orphan client org service)
          (commit-empty! "initial commit")
          (changeset/create-branch! default-branch))
      (run-fn client))))
