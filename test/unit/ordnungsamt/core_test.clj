(ns ordnungsamt.core-test
  (:require [clojure.test :refer :all]
            [ordnungsamt.core :as core]))

(deftest test-filter-opt-in
  (let [filter (core/filter-opt-in "serviceA")]
    (testing "passes if the service is in the opt-in"
      (is (true? (filter {:opt-in #{"serviceA" "serviceB"}}))))

    (testing "passes if opt-in is nil"
      (is (true? (filter {}))))

    (testing "filtered out if the service is not in the opt-in"
      (is (false? (filter {:opt-in #{"serviceB"}}))))))

(deftest test-compose-filters
  (is (true? ((core/compose-filters []) {})))

  (is (true? ((core/compose-filters [(partial = "a")]) "a")))

  (is (false? ((core/compose-filters [(partial = "a")]) "b")))

  (is (false? ((core/compose-filters [(constantly false)
                                      (fn [_] (throw (Exception. "this exception shouldn't happen")))]) {}))))

