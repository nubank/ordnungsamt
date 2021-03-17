(ns ordnungsamt.render
  (:require [clojure.string]
            [selmer.parser :as selmer]
            [selmer.util :refer [without-escaping]]))

(defn render-title [context]
  (selmer.parser/render "[Auto] Refactors - {{date}}" context))

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
