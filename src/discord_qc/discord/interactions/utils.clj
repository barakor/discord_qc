(ns discord-qc.discord.interactions.utils
  (:require [clojure.string :as string :refer [lower-case]]
            [clojure.set :as set]

            [com.rpl.specter :as s]

            [slash.component.structure :as scomp]

            [discord-qc.handle-db :as db]
            [discord-qc.discord.utils :refer [get-voice-channel-members 
                                              user-in-voice-channel? 
                                              build-components-action-rows 
                                              get-user-display-name
                                              get-sibling-voice-channels-names
                                              divide-hub-embed]]))
(use 'debux.core)


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


(defn divide-hub [guild-id user-id game-mode ignored-users]
  (let [voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        lobbies-names (get-sibling-voice-channels-names guild-id voice-channel-id)

        find-unregistered-users (partial find-unregistered-users guild-id)
        found-players (->> voice-channel-members
                        (filter #(not (contains? ignored-users %)))
                        (find-registered-users)
                        (find-unregistered-users))
        players (map :quake-name (vals found-players))

        unregistered-users (s/select [s/MAP-VALS #(= (:registered %) false) :quake-name] found-players)
        
        components (build-components-action-rows
                     [(scomp/button :success  
                                    (string/join "/" (into ["reshuffle!" (name game-mode)] ignored-users))
                                    :label "Reshuffle!")])
        content    (string/join "\n"
                     [(when (not-empty unregistered-users) 
                         (str "Unregistered Users: " (string/join ", " unregistered-users)))
                      (str "Balancing for " game-mode)
                      (str "Found " (count players) " players")])

        embeds     (divide-hub-embed game-mode players lobbies-names ignored-users)]
    {:content content :embeds embeds :components components}))
