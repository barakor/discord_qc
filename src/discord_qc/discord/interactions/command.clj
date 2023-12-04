(ns discord-qc.discord.interactions.command
  (:require [clojure.string :as string :refer [lower-case]]

            [discljord.messaging :as discord-rest]

            [slash.response :as srsp]
            [slash.component.structure :as scomp]

            [com.rpl.specter :as s]

            [discord-qc.state :refer [state* discord-state*]]
            [discord-qc.quake-stats :as quake-stats]
            [discord-qc.elo :as elo]
            [discord-qc.handle-db :as db]
            [discord-qc.discord.utils :refer [get-voice-channel-members user-in-voice-channel? build-components-action-rows]]))


(defn map-command-interaction-options [interaction]
  (into {} (map #(hash-map (:name %) (:value %)) (get-in interaction [:data :options]))))  


(defn find-registered-users [user-ids]
  (->> user-ids
    (map #(hash-map % (when-let [quake-name (db/discord-id->quake-name %)] 
                        {:quake-name quake-name :registered true}))) 
    (apply merge)))


(defn get-user-display-name [user-id]
  (when-let [display-name (get-in @discord-state* [:discljord.events.state/users user-id :display-name])]
    (lower-case display-name)))


(defn find-unregistered-users [users]
  (->> users 
    (map #(if (nil? (second %))
            (hash-map (first %) {:quake-name (get-user-display-name (first %)) :registered false})
            %))
    (apply merge)))


(defn elo-map->embed [elo-map]
  (let [format-map-entry #(hash-map :name ((first %) elo/mode-names) :value (str (format "%.3f" (second %))))]
    [{:type "rich" :title "Quake ELO for:" :description (:quake-name elo-map) :color 9896156
      :fields (concat
                (map format-map-entry (select-keys elo-map (keys elo/mode-names))))}]))


;; Command interactions
(defmulti handle-command-interaction 
  (fn [interaction] (get-in interaction [:data :name])))


(defmethod handle-command-interaction "query" [interaction]
  (let [quake-name (lower-case (get (map-command-interaction-options interaction) "quake-name"))]

    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (srsp/update-message {:content "" :embeds (elo-map->embed elo)}))
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))
 

(defmethod handle-command-interaction "register" [interaction]
  (let [quake-name (lower-case (get (map-command-interaction-options interaction) "quake-name"))
        user-id (s/select-first [:member :user :id] interaction)]
    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do
          (db/save-discord-id->quake-name user-id quake-name)
          (srsp/update-message {:content "" :embeds (elo-map->embed elo)}))
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))


(defmethod handle-command-interaction "balance" [interaction]
  (let [interaction-options (map-command-interaction-options interaction)
        game-mode (get interaction-options "game-mode")
        quake-names (->> (dissoc interaction-options "game-mode")
                      (vals)
                      (map lower-case))
        user-id (s/select-first [:member :user :id] interaction)

        voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        
        found-players (->> voice-channel-members 
                        (find-registered-users)
                        (find-unregistered-users))
        unregistered-users (s/select [s/MAP-VALS #(= (:registered %) false) :quake-name] found-players)

        component-id (atom 0)

        quake-name-button (fn [quake-name] (scomp/button :secondary 
                                                         (str "toggle-primary-secondary/" (str "toggle-primary-secondary/" (swap! component-id inc))) 
                                                         :label quake-name))
        components (build-components-action-rows
                      (concat 
                        (->> found-players
                          (vals)
                          (map :quake-name)
                          (map quake-name-button))
                        (map quake-name-button quake-names)
                        [(scomp/button :danger  "select-all-primary-secondary" :label "Select All")
                         (scomp/button :success  (str "balance!/" game-mode) :label "Balance!")]))
        content (string/join "\n"
                    [(when (not-empty unregistered-users) 
                        (str "Unregistered Users: " (string/join ", " unregistered-users)))
                     (str "Balancing for " game-mode)])]
                     
    (srsp/channel-message {:content content :components components})))


(defn command-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-channel-message)) 
  (let [{:keys [type data]} (handle-command-interaction interaction)]
    ;; for debugging
    ; (println "[command-interaction] responding: "
      @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))

