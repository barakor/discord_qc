(ns app.web
  (:require
   [clojure.string :as string :refer [lower-case starts-with?]]
   [clojure.pprint :refer [pprint]]
   [clojure.edn :as edn]
   [clojure.set :as set]

   [com.rpl.specter :as s]

   [reagent.core :as r :refer [with-let atom]]
   [reagent.dom :as rdom]

   [rewig.components :refer [box row column gap button label dropdown-select]]
   [rewig.theme.gruvbox :as theme]
   [app.async :refer-macros [let-await]]
   [app.http :refer [http-get]]
   [app.components :refer [input-field input-field-autocomplete on-off-component on-off-renameable-component chip-component radio-component multi-dropdown-selection rotating-loading]]
   [app.utils :refer [drop-nth symmetric-difference change-idx remove-dups drop-or-add-by-id]]))

(def app-options* (atom {}))
(def pages [:calc :test])

(defn score-calculator []
  (with-let [loading-autocomplete [:<>
                                   [gap :size theme/size-medium]
                                   [rotating-loading {:size theme/size-large}]
                                   [gap :size theme/size-medium]
                                   [label {} "loading autocomplete"]]

             mode* (atom nil)]
    ; (let [v (count (or data []))]
    (let [db-data (get @app-options* :data)
          modes  (->> db-data
                      (s/select [s/MAP-VALS #(map? %) s/MAP-KEYS])
                      (set)
                      (#(set/difference % #{:quake-name})))
          mode (or @mode* (first modes))
          players  (->> db-data
                        (s/select [s/MAP-VALS #(map? %) :quake-name])
                        (set)
                        (sort))
          players->elos (->> db-data
                           (s/select [s/MAP-VALS #(map? %)])
                           (map #(hash-map (:quake-name %) %))
                           (into {}))
          

          player-1 (:player-1 @app-options*)]
      ; (println mode)
      [:<> {}
       (when (empty? db-data)
         [row {} [loading-autocomplete]])
       [radio-component modes mode (fn [v] (reset! mode* v)) {:print! name}]

       [gap :size theme/size-medium]

       [label (str (count players))]
       ; (println players)

       [gap :size theme/size-medium]

       [row  [[input-field-autocomplete
               "Quake name"
               (:player-1 @app-options*)
               #(do
                  (swap! app-options* assoc :player-1 %))
               {:options-list players}]
              (when (empty? players)
                loading-autocomplete)]]

       [gap :size theme/size-medium]
       (println (contains? players player-1))

       (when (contains? (set players) player-1)
         [label (s/select [player-1 mode] players->elos)])])))

(contains? #{1 2 3} 1)
(s/select [nil] {:a 1 :b 2})
; (let [db-data (:data @app-options*)
;       players->elos (->> db-data
;                            (s/select [s/MAP-VALS #(map? %)])
;                            (map #(hash-map (:quake-name %) %))
;                            (into {}))]
;   (s/select [(:player-1 @app-options*) nil] players->elos))
; ; (let [x (map #(dissoc % :quake-name) d)]

;     ; (map #(dissoc % :quake-name))))
; (map #(keys %) x)

(defn assoc-await [address atom* k & {:keys [cast!] :or {cast! edn/read-string}}]
  (let-await [data (http-get address)
              parsed-data (cast! data)]
             (swap! atom* assoc k parsed-data)))

(defn app []
  (with-let [; options (assoc-await "/get_options" app-options* :options)
             ; db-data (assoc-await "https://raw.githubusercontent.com/barakor/discord_qc/refs/heads/db-data/db-data.edn" app-options* :data)
             page* (r/atom (first pages))

             a "./resources/db-data.edn"
             data (assoc-await a app-options* :data)]

    (let [page @page*
          db-data  (:data @app-options*)]

      [box {:css {:background-color theme/background}
            :size "100%"}
       [[gap :size theme/size-medium]
        [column {:css {:background-color theme/background
                       :size "100%"}}
         [[gap :size theme/size-medium]
          [radio-component pages page (fn [v] (reset! page* v)) {:print! name}]
          [gap :size theme/size-medium]
          (case page
            [score-calculator])]]]])))

(defn ^:export main []
  (rdom/render [app]
               (.getElementById js/document "app")))

(main)
