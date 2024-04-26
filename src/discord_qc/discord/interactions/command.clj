(ns discord-qc.discord.interactions.command
  (:require [clojure.string :as string :refer [lower-case]]
            [clojure.set :as set]

            [discljord.messaging :as discord-rest]

            [slash.response :as srsp]
            [slash.component.structure :as scomp]

            [com.rpl.specter :as s]

            [discord-qc.state :refer [state* discord-state*]]
            [discord-qc.quake-stats :as quake-stats]
            [discord-qc.elo :as elo]
            [discord-qc.handle-db :as db]
            [discord-qc.discord.utils :refer [get-voice-channel-members 
                                              user-in-voice-channel? 
                                              build-components-action-rows 
                                              get-user-display-name
                                              divide-hub-embed
                                              get-sibling-voice-channels-names]]))


(defn map-command-interaction-options [interaction]
  (into {} (map #(hash-map (:name %) (:value %)) (get-in interaction [:data :options]))))  


(defn find-registered-users [user-ids]
  (->> user-ids
    (map #(hash-map % (when-let [quake-name (db/discord-id->quake-name %)] 
                        {:quake-name quake-name :registered true}))) 
    (apply merge)))


(defn find-unregistered-users [guild-id users]
  (->> users 
    (map #(if (nil? (second %))
            (hash-map (first %) {:quake-name (get-user-display-name guild-id (first %)) :registered false})
            %))
    (into {})))


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
        (let [quake-stats-name (get elo :quake-name)]
          (db/save-discord-id->quake-name user-id quake-stats-name)
          (srsp/update-message {:content "" :embeds (elo-map->embed elo)})))
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))


(defmethod handle-command-interaction "balance" [interaction]
  (let [interaction-options (map-command-interaction-options interaction)
        game-mode (get interaction-options "game-mode")
        quake-names (->> (dissoc interaction-options "game-mode")
                      (vals)
                      (map lower-case))
        guild-id (:guild-id interaction)
        user-id (s/select-first [:member :user :id] interaction)

        voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        
        find-unregistered-users (partial find-unregistered-users guild-id)
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


(defmethod handle-command-interaction "divide" [interaction]
  (let [interaction-options (map-command-interaction-options interaction)
        game-mode (get (set/map-invert elo/mode-names) (get interaction-options "game-mode"))
        guild-id (:guild-id interaction)
        user-id (s/select-first [:member :user :id] interaction)

        voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        lobbies-names (get-sibling-voice-channels-names guild-id voice-channel-id)

        find-unregistered-users (partial find-unregistered-users guild-id)
        found-players (->> voice-channel-members 
                        (find-registered-users)
                        (find-unregistered-users))

        unregistered-users (s/select [s/MAP-VALS #(= (:registered %) false) :quake-name] found-players)
   
        content    (string/join "\n"
                     [(when (not-empty unregistered-users) 
                         (str "Unregistered Users: " (string/join ", " unregistered-users)))
                      (str "Balancing for " game-mode)])

        embeds     (divide-hub-embed game-mode found-players lobbies-names)]

    (srsp/channel-message {:content content :embeds embeds})))


;; Admin commands
(defmethod handle-command-interaction "refresh-db" [interaction]
  (let [players-registered @db/all-quake-names-in-db]
        
    (doall (map quake-stats/quake-name->elo-map players-registered))
    (srsp/channel-message {:content (str "refreshed all players stats")})))


(defmethod handle-command-interaction "db-stats" [interaction]
  (let [players-registered @db/all-quake-names-in-db
        db-stats-message (string/join "\n"
                           [(str "\\# Of players registered in db: " (count players-registered))])]
        
    (srsp/channel-message {:content db-stats-message})))


(defn command-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-channel-message)) 
  (let [{:keys [type data]} (handle-command-interaction interaction)]
      @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))

