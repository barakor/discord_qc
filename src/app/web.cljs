(ns app.web
  (:require
   [clojure.string :as string :refer [lower-case]]
   [clojure.pprint :refer [pprint]]

   [com.rpl.specter :as s]

   [reagent.core :as r :refer [with-let atom]]
   [reagent.dom :as rdom]

   [rewig.components :refer [box row column gap button label dropdown-select]]
   [rewig.theme.gruvbox :as theme]

   [app.async :refer-macros [let-await]]
   [app.http :refer [http-get]]
   [app.components :refer [input-field on-off-component on-off-renameable-component chip-component radio-component multi-dropdown-selection rotating-loading]]
   [app.utils :refer [drop-nth symmetric-difference change-idx remove-dups drop-or-add-by-id]]

   [clojure.set :as set]))

(def app-options* (atom {}))
(def pages [:calc :test])

(defn score-calculator []
  [label (:data app-options*)])

(defn assoc-await [address atom* k]
  (let-await [data (http-get address)]
             data))

(defn app []
  (with-let [; options (assoc-await "/get_options" app-options* :options)
             db-data (assoc-await "https://raw.githubusercontent.com/barakor/discord_qc/refs/heads/db-data/db-data.edn" app-options* :data)
             page* (r/atom (first pages))]

    (let [page @page*
          db-data (:data @app-options*)]

      [box {:css {:background-color theme/background}
            :size "100%"}
       [[gap :size theme/size-medium]
        [column {:css {:background-color theme/background
                       :size "100%"}}
         [[gap :size theme/size-medium]
          [radio-component pages page (fn [v] (reset! page* v)) {:print! name}]
          [gap :size theme/size-medium]
          (case page
            :calc 
            [score-calculator])]]]])))

(defn ^:export main []
  (rdom/render [app]
               (.getElementById js/document "app")))

(main)
