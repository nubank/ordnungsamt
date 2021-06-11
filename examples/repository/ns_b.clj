(ns ns-b
  (:require [clojure.string :as str]
            [clojure.set :refer [difference]]))

(def soft-hello (str/lower-case "HELLO WORLD!"))
(def loud-hello (str/upper-case "hello world!"))
(def some-diffs (difference #{:a :b} #{:a :c}))
