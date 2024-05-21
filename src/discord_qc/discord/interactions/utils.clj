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
; (use 'debux.core)


(defn tag-custom-id [custom-key values]
   (map #(str ":" custom-key ":" %) values))


(defn create-custom-id [values]
  (string/join "/" values))


(defn get-tags-from-custom-id [input]
  (let [sections (string/split input #"/")
        get-kv! (fn [section] 
                  (let [parts (string/split section #":")]
                    (if (not-empty (first parts))
                      {(first parts) #{(first parts)}}
                      {(second parts) #{(string/join ":" (drop 2 parts))}})))]
                    
    (apply merge-with into (map get-kv! sections))))



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


(defn divide-hub [guild-id user-id game-mode quake-names ignored-players]
  (let [voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        lobbies-names (get-sibling-voice-channels-names guild-id voice-channel-id)

        find-unregistered-users (partial find-unregistered-users guild-id)
        found-players (->> voice-channel-members
                        (find-registered-users)
                        (find-unregistered-users))

        players (->> found-players
                  (vals)
                  (map :quake-name)
                  (filter #(not (contains? ignored-players %)))
                  (concat quake-names)
                  (set))
       
        unregistered-users (s/select [s/MAP-VALS #(= (:registered %) false) :quake-name] found-players)
        
        components (build-components-action-rows
                     [(scomp/button :success  
                                    (create-custom-id (into ["reshuffle!" (name game-mode)] 
                                                        (concat (tag-custom-id "out" ignored-players)
                                                                (tag-custom-id "in" quake-names))))
                                    :label "Reshuffle!")])
        content    (string/join "\n"
                     [(when (not-empty unregistered-users) 
                         (str "Unregistered Users: " (string/join ", " unregistered-users)))
                      (str "Balancing for " (name game-mode))
                      (str "Found " (count players) " players")])]

    (if (<= (count players) 3)
      {:content (str content "\n" "Not Enough players to divide into teams") :components components}
      {:content content :embeds (divide-hub-embed game-mode players lobbies-names ignored-players) :components components})))
