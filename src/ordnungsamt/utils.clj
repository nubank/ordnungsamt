(ns ordnungsamt.utils
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as string]))

(defn today []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
           (new java.util.Date)))

(defn print-process-output [{:keys [exit out err]}]
  (letfn [(print-lines [output]
            (when output
              (->> (string/split output (re-pattern (System/lineSeparator)))
                   (map println)
                   doall)))]
    (print-lines out)
    (when-not (zero? exit)
      (print-lines err))))

(defn out->list [{:keys [out]}]
  (remove empty? (string/split out #"\n")))

(defn sh! [& args]
  (let [{:keys [exit err] :as result} (apply sh args)]
    (when (not (zero? exit))
      (throw (ex-info (str "FAILED running command:\n" args "\nerror message:\n" err)
                      result)))))
