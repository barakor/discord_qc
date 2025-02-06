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
   [app.components :refer [input-field input-field-autocomplete num-field on-off-component on-off-renameable-component chip-component radio-component multi-dropdown-selection rotating-loading]]
   [app.utils :refer [drop-nth symmetric-difference change-idx remove-dups drop-or-add-by-id]]))

(def app-options* (atom {}))
(def pages [:calc])

(defn score-calculator []
  (with-let [loading-autocomplete [:<>
                                   [gap :size theme/size-medium]
                                   [rotating-loading {:size theme/size-large}]
                                   [gap :size theme/size-medium]
                                   [label {} "loading autocomplete"]]

             mode* (atom :sacrifice-tournament)

             selected-players* (atom {})]

    (let [db-data (get @app-options* :data)
          modes  (->> db-data
                      (s/select [s/MAP-VALS #(map? %) s/MAP-KEYS])
                      (set)
                      (#(set/difference % #{:quake-name})))
          mode @mode*
          selected-players @selected-players*

          players  (->> db-data
                        (s/select [s/MAP-VALS #(map? %) :quake-name])
                        (set)
                        (sort))
          players->elos (->> db-data
                             (s/select [s/MAP-VALS #(map? %)])
                             (map #(hash-map (:quake-name %) %))
                             (into {}))

          score-factor (->> selected-players
                            (s/select [s/MAP-VALS]))

          avg-score-factor (->> @selected-players*
                                (s/select [s/MAP-VALS #(contains? players->elos (:name %))])
                                (map (fn [{qname :name score :score}] (/ score (s/select-one [qname mode] players->elos))))
                                (#(/ (reduce + %) (count %))))]

      [:<> {}
       (when (empty? db-data)
         [row {} [loading-autocomplete]])
       [radio-component modes mode (fn [v] (reset! mode* v)) {:print! name}]

       [gap :size theme/size-medium]

       [label (str "avg-score-factor " avg-score-factor)]

       [gap :size theme/size-medium]

       (for [n (range  8)
             :let [player (get selected-players n "")
                   player-name (:name player)
                   player-score (:score player)

                   player-exists (contains? (set players) player-name)
                   marked-for-calc false
                   player-elo (when (and (some? mode)
                                         player-exists)
                                (s/select-one [player-name mode] players->elos))
                   player-score-factor (/ player-score player-elo)
                   player-suggested-score (/ player-score avg-score-factor)]]
                                          

         ^{:key (str "quake-player-input-field" n)}
         [column
          [[row
            [[input-field-autocomplete
              "Quake name"
              player-name
              #(swap! selected-players* assoc-in [n :name] %)
              {:options-list players}]

             [gap :size theme/size-small]

             [num-field
              "Game Score"
              player-score
              #(swap! selected-players* assoc-in [n :score] %)]

             [gap :size theme/size-small]
             (when (empty? players)
               loading-autocomplete)
             [gap :size theme/size-small]
             [label (str "ELO: " player-elo)]
             [gap :size theme/size-small]
             [label (str "Score: " player-score)]
             [gap :size theme/size-small]
             [label (str "factor: " player-score-factor)]
             [gap :size theme/size-small]
             [label (str "suggested: " player-suggested-score)]]]
           [gap :size theme/size-small]]])

       [gap :size theme/size-medium]

       [gap :size theme/size-medium]])))


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
