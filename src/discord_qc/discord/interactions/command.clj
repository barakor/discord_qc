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
            [discord-qc.utils :refer [get-keys-starting-with]]
            [discord-qc.discord.utils :refer [get-voice-channel-members 
                                              user-in-voice-channel? 
                                              build-components-action-rows 
                                              get-user-display-name]]
            [discord-qc.discord.interactions.utils :refer [map-command-interaction-options
                                                           find-registered-users
                                                           find-unregistered-users
                                                           divide-hub]]))


(defn elo-map->embed [elo-map]
  (let [format-map-entry (fn [[game-mode mode-score]] (hash-map :name (game-mode elo/mode-names) 
                                                                :value (str (format "%.3f" (double mode-score)))))]
    (println elo-map)
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
        discord-id (s/select-first [:member :user :id] interaction)]
    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do
        (let [quake-stats-name (get elo :quake-name)]
          (db/save-discord-id->quake-name discord-id quake-stats-name)
          (srsp/update-message {:content "" :embeds (elo-map->embed elo)})))
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))


(defmethod handle-command-interaction "manual-register" [interaction]
  (let [quake-name (lower-case (get (map-command-interaction-options interaction) "quake-name"))
        discord-id (lower-case (get (map-command-interaction-options interaction) "discord-id"))]
    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do
        (let [quake-stats-name (get elo :quake-name)]
          (db/save-discord-id->quake-name discord-id quake-stats-name)
          (srsp/update-message {:content "" :embeds (elo-map->embed elo)})))
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))


(defmethod handle-command-interaction "balance" [interaction]
  (let [interaction-options (map-command-interaction-options interaction)
        game-mode (get interaction-options "game-mode")
        quake-names (->> (get-keys-starting-with interaction-options "quake-name")
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
        guild-id (:guild-id interaction)
        user-id (s/select-first [:member :user :id] interaction)
        game-mode (get (set/map-invert elo/mode-names) (get interaction-options "game-mode"))
        
        clean-user-id! (fn [user-id] (apply str (filter #(not (contains? #{\@ \> \<} %)) user-id)))

        ignored-players (->> (get-keys-starting-with interaction-options "discord-tag")
                             (vals)
                             (map clean-user-id!)
                             (map db/discord-id->quake-name)
                             (filter some?)
                             (set))

        quake-names (->> (get-keys-starting-with interaction-options "quake-name")
                      (vals)
                      (map lower-case))]

    (srsp/channel-message (divide-hub guild-id user-id game-mode quake-names ignored-players))))


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

