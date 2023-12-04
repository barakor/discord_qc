(ns discord-qc.discord.interactions.component
  (:require [clojure.string :as string :refer [lower-case]]
            [clojure.set :as set]

            [discljord.messaging :as discord-rest]
            [discljord.connections :as discord-ws]

            [slash.response :as srsp]
            [slash.gateway :as sg]
            [slash.component.structure :as scomp]

            [com.rpl.specter :as s]
            [discljord.events.state :as discord-state]

            [discord-qc.state :refer [state* discord-state*]]
            [discord-qc.quake-stats :as quake-stats]
            [discord-qc.elo :as elo]
            [discord-qc.balancing :as balancing]
            [discord-qc.handle-db :as db]
            [discord-qc.discord.utils :refer [get-voice-channel-members user-in-voice-channel? build-components-action-rows balance-teams-embed]]))


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
        selected-players (s/select [s/ALL #(= (:style %) :primary) :label] old-components)
       
        embed-msg (balance-teams-embed game-mode selected-players)]
    (srsp/update-message {:content old-content :embeds embed-msg})))



(defn component-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-update-message))
  (let [original-author-id (get-in interaction [:message :interaction :user :id])
        interactor-id (get-in interaction [:member :user :id])]
    (when (= original-author-id interactor-id)      
      (let [{:keys [type data]} (handle-component-interaction interaction)]
        ;; for debugging
        ; (println "[component-interaction] responding: "
          @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))))

