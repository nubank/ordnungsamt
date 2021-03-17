(ns integration.apply-migrations-test
  (:require [clojure.test :refer :all]
            [state-flow.api :refer [flow] :as flow]
            [ordnungsamt.core :as core]))

(defn cleanup-fake-service-repo! [state]
  nil)

(defn seed-fake-service-repo! []
  {})

(defmacro defflow
  [name & flows]
  `(flow/defflow ~name {:init       seed-fake-service-repo!
                        :fail-fast? true
                        :cleanup    cleanup-fake-service-repo!}

     ~@flows))

(defflow apply-two-migrations

  )
