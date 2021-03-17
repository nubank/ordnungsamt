(ns integration.apply-migrations-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :refer [sh]]
            [state-flow.api :refer [flow] :as flow]
            [ordnungsamt.core :as core]))

(defn- run-commands! [commands]
  (loop [[command & rest-commands] commands]
    (when command
      (let [{:keys [exit out err]} (apply sh command)]
        (if (zero? exit)
          (recur rest-commands)
          (println (str "FAILED running command:\n" command "\nerror message:\n" err)))))))

(defn cleanup-fake-service-repo! [state]
  (run-commands! [["rm" "-rf" "target/example-repo"]]))

(defn seed-fake-service-repo! []
  (let [dir "target/example-repo"]
    (run-commands! [["cp" "-r" "test-resources/example-repo/" "target/"]
                    ["git" "init" "." :dir dir]
                    ["git" "add" "4'33" "clouds.md" "fanon.clj" :dir dir]
                    ["git" "commit" "-m" "initial commmit" :dir dir]
                    ["git" "status" :dir dir]]))
  {})

(defmacro defflow
  [name & flows]
  `(flow/defflow ~name {:init       seed-fake-service-repo!
                        :fail-fast? true
                        :cleanup    cleanup-fake-service-repo!}

     ~@flows))

(defflow apply-two-migrations

  )
