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


