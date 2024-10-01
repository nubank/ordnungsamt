(ns ordnungsamt.render
  (:require [clojure.string]
            [ordnungsamt.utils :as utils]
            [selmer.parser]
            [selmer.util :refer [without-escaping]]))

(defn render-title [context]
  (if (-> context :migrations count (= 1))
    (selmer.parser/render "[Auto] {{title}}" (-> context :migrations first))
    (selmer.parser/render "[Auto] Refactors - {{date}}" context)))

(def migration-template
  "## {{title}} [{{created-at}}]

{{description}}")

(defn render-migration [context]
  (without-escaping
   (selmer.parser/render migration-template context)))

(def pr-template
  "**Ordnungsamt** applied some migrations:

{% for migration in migrations %}
{{migration|safe}}
{% endfor %}")

(defn render-pr [context]
  (clojure.string/trim
   (selmer.parser/render pr-template {:migrations (map render-migration (:migrations context))})))

(defn render-pr-description! [migration-details]
  (let [context {:date       (utils/today)
                 :migrations migration-details}]
    {:pr-title       (render-title context)
     :pr-description (render-pr context)}))
