(ns discord-qc.discord.interactions.component
  (:require [clojure.string :as string]
            [clojure.set :as set]

            [discljord.messaging :as discord-rest]

            [slash.response :as srsp]
            [slash.component.structure :as scomp]

            [com.rpl.specter :as s]

            [discord-qc.state :refer [state*]]
            [discord-qc.elo :as elo]
            [discord-qc.discord.utils :refer [build-components-action-rows balance-teams-embed]]
            [discord-qc.discord.interactions.utils :refer [divide-hub get-tags-from-custom-id get-tag-from-custom-id-tags]]))

(defn get-custom-id [custom-id]
  (-> custom-id
      (string/split #"/")
      (last)))

(defn get-custom-id-type [custom-id]
  (-> custom-id
      (string/split #"/")
      (first)))

(defmulti handle-component-interaction
  ; To make componenet ids unique, they are namespaced, so "name.of.function/1" and "name.of.function/2" point to the same function
  (fn [interaction]
    (-> interaction
        (get-in [:data :custom-id])
        (get-custom-id-type))))

(defmethod handle-component-interaction "select-all-primary-secondary"
  [interaction]
  (let [primary-secondary-switch (fn [style] (case style
                                               :secondary :primary
                                               :primary :secondary))

        old-content (get-in interaction [:message :content])

        old-components (->> interaction
                            (s/select [:message :components s/ALL :components s/ALL])
                            (map #(update % :style (set/map-invert scomp/button-styles))))

        components (->> old-components
                        (s/transform [(s/filterer #(= (get-custom-id-type (:custom-id %)) "toggle-primary-secondary")) s/ALL]
                                     #(assoc % :style :primary))
                        (map #(scomp/button (:style %) (:custom-id %) :label (:label %)))
                        (build-components-action-rows))]

    (srsp/update-message {:content old-content :components components})))

(defmethod handle-component-interaction "toggle-primary-secondary"
  [interaction]
  (let [primary-secondary-switch (fn [style] (case style
                                               :secondary :primary
                                               :primary :secondary))

        old-content (get-in interaction [:message :content])

        old-components (->> interaction
                            (s/select [:message :components s/ALL :components s/ALL])
                            (map #(update % :style (set/map-invert scomp/button-styles))))

        toggle-componenet-id (s/select-first [:data :custom-id] interaction)

        components (->> old-components
                        (s/transform [(s/filterer #(= (:custom-id %) toggle-componenet-id)) s/ALL]
                                     #(update % :style primary-secondary-switch))
                        (map #(scomp/button (:style %) (:custom-id %) :label (:label %)))
                        (build-components-action-rows))]

    (srsp/update-message {:content old-content :components components})))

(s/select [s/ALL (s/submap [:a :b])] [{:a 1 :b 2} {:a 2 :b 3} {:a 4 :b 5 :c 6}])

(defmethod handle-component-interaction "balance!"
  [interaction]
  (let [game-mode (-> interaction
                      (get-in [:data :custom-id])
                      (string/split #"/")
                      (second)
                      (#(get (set/map-invert elo/mode-names) %)))
        old-content (get-in interaction [:message :content])
        old-components (->> interaction
                            (s/select [:message :components s/ALL :components s/ALL])
                            (map #(update % :style (set/map-invert scomp/button-styles))))
        selected-players (->> old-components
                              (s/select [s/ALL #(= (:style %) :primary) (s/submap [:custom-id :label])])
                              (map #(hash-map (get-custom-id (:custom-id %)) (:label %)))
                              (into {}))

        get-players-elo (fn [did]
                          (if-let [elo (elo/discord-id->Elo did)]
                            (assoc elo :discord-id did)
                            (hash-map :discord-id did :quake-name (get selected-players did))))

        elo-maps (map get-players-elo (keys selected-players))]
    (if (> (count selected-players) 3)
      (srsp/update-message {:content old-content :embeds (balance-teams-embed game-mode elo-maps)})
      (srsp/update-message {:embeds [{:type "rich" :title "No enough players" :color 9896156}]}))))

(defmethod handle-component-interaction "reshuffle!"
  [interaction]
  (let [guild-id (:guild-id interaction)
        user-id (s/select-first [:member :user :id] interaction)
        custom-id (get-in interaction [:data :custom-id])
        custom-id-tags (get-tags-from-custom-id custom-id)
        game-mode (-> custom-id
                      (string/split #"/")
                      (second)
                      (#(get (set/map-invert elo/mode-names) %)))
        sorting-method (-> custom-id
                           (string/split #"/")
                           (nth 2))

        ignored-players (get-tag-from-custom-id-tags custom-id-tags "o")
        manual-entries (get-tag-from-custom-id-tags custom-id-tags "i")]
    (srsp/update-message (divide-hub
                          (str guild-id)
                          (str user-id)
                          game-mode
                          sorting-method
                          manual-entries
                          ignored-players))))

(defn component-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-update-message))
  (let [original-author-id (get-in interaction [:message :interaction :user :id])
        interactor-id (get-in interaction [:member :user :id])]
    (when (= original-author-id interactor-id)
      (let [{:keys [type data]} (handle-component-interaction interaction)]
        @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))))

