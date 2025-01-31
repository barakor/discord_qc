(ns discord-qc.discord.interactions.utils
  (:require [clojure.string :as string :refer [lower-case]]
            [clojure.set :as set]

            [com.rpl.specter :as s]

            [slash.component.structure :as scomp]

            [discord-qc.elo :as elo]
            [discord-qc.discord.utils :refer [get-voice-channel-members
                                              user-in-voice-channel?
                                              build-components-action-rows
                                              get-user-display-name
                                              get-sibling-voice-channels-names
                                              divide-hub-embed]]))
; (use 'debux.core)
(def base-chars "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz<.>?;\"'[{]}!@#$%^&*()-_")

(defn encode-base [n]
  (let [base (count base-chars)
        n (str n)]
    (loop [num (Long/parseLong n)
           result ""]
      (if (zero? num)
        (if (empty? result) "0" result)
        (recur (quot num base)
               (str (nth base-chars (mod num base)) result))))))

(defn decode-base [s]
  (let [base (count base-chars)]
    (reduce (fn [acc char]
              (+ (* acc base) (string/index-of base-chars char)))
            0
            s)))

(defn tag-custom-id [custom-key values]
  (map #(str ":" custom-key ":" (encode-base %)) values))

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

(defn get-tag-from-custom-id-tags [custom-id-tags tag]
  (set (map decode-base (get custom-id-tags tag))))

(defn map-command-interaction-options [interaction]
  (into {} (map #(hash-map (:name %) (:value %)) (get-in interaction [:data :options]))))

(defn divide-hub [guild-id user-id game-mode manual-entries ignored-players]
  (let [voice-channel-id (user-in-voice-channel? user-id)
        lobbies-names (get-sibling-voice-channels-names guild-id voice-channel-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)

        active-players (->> (concat manual-entries voice-channel-members)
                            (filter #(not (contains? ignored-players %)))
                            (into #{}))

        elos (map #(assoc (elo/discord-id->Elo %) :discord-id %) active-players)

        unregistered-users (s/select [s/ALL #(not (:quake-name %)) :discord-id] elos)
        unregistered-users-names (into {} (map #(hash-map % (get-user-display-name guild-id %)) unregistered-users))

        reshuffle-gen-id (create-custom-id (into ["reshuffle!" (name game-mode)]
                                                 (concat (tag-custom-id "o" ignored-players)
                                                         (tag-custom-id "i" manual-entries))))
        components (build-components-action-rows
                    [(scomp/button :success
                                   (if (< (count reshuffle-gen-id) 100)
                                     reshuffle-gen-id
                                     (create-custom-id ["reshuffle!" (name game-mode)]))
                                   :label "Reshuffle!")]) ;;;;;;; TODO: MAKE THIS SHORTER
        content    (string/join "\n"
                                (filter some?
                                        [(when (not-empty unregistered-users)
                                           (str "Unregistered Users: " (string/join ", " unregistered-users)))
                                         (str "Balancing for " (name game-mode))
                                         (str "Found " (count elos) " players")
                                         (when (<= (count elos) 3)
                                           "Not Enough players to divide into teams")]))

        embeds     (if (> (count elos) 3)
                     (divide-hub-embed game-mode elos lobbies-names ignored-players)
                     [])]
    {:content content :embeds embeds :components components}))
