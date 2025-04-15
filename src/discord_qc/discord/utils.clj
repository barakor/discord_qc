(ns discord-qc.discord.utils
  (:require [clojure.string :as string]

            [discljord.messaging :as discord-rest]
            [slash.component.structure :as scomp]

            [discord-qc.state :refer [state* discord-state*]]
            [discord-qc.handle-db :as db]
            [discord-qc.elo :as elo]
            [discord-qc.balancing :as balancing]))

(defn split-into-groups-at [col sizes]
  (drop-last
   (reduce (fn [col take] (into (vec (drop-last col)) (split-at take (last col))))
           [col] sizes)))

(defn get-voice-channel-members [channel-id]
  (get-in @discord-state* [:voice-channels channel-id]))

(defn user-in-voice-channel? [user-id]
  (get-in @discord-state* [:discljord.events.state/users user-id :voice :channel-id]))

(defn get-sibling-voice-channels-names [guild-id channel-id]
  (let [channels (get-in @discord-state* [:discljord.events.state/guilds guild-id :channels])
        channel-parent-id (get-in channels [channel-id :parent-id])
        sibling-voice-channels (filter #(and (= (:type %) 2)
                                             (= (:parent-id %) channel-parent-id))
                                       (vals channels))
        lobbies-channels-names (->> sibling-voice-channels
                                    (sort-by :position)
                                    (drop 1) ;; first one is the HUB channel and it's used as a gathering lobby...
                                    (map :name)
                                    (partition 2))]

    lobbies-channels-names))

(defn get-user-display-name [guild-id user-id]
  (if-let [display-name (get-in @discord-state* [:discljord.events.state/users user-id :display-name])]
    (string/lower-case display-name)
    (->> @(discord-rest/get-guild-member! (:rest @state*) guild-id user-id)
         (#(or (:nick %) (get-in % [:user :global-name]) (get-in % [:user :username])))
         (string/lower-case))))

(defn get-user-quake-name [guild-id user-id]
  (if-let [quake-name (:quake-name (db/discord-id->elo-map user-id))]
    quake-name
    (get-user-display-name guild-id user-id)))

(defn build-components-action-rows [components]
  (->> components
       (partition-all 5)
       (map #(apply scomp/action-row %))
       (into [])))

(defn format-team-option-msg [team-option & {:keys [option-number title-prefix]}]
  (let [title (str title-prefix " Team Option"
                   (when option-number (str " #" option-number)))

        divider "\n------------------------------VS------------------------------\n"
        team1 (->> team-option
                   :team1
                   (sort-by val >)
                   (keys)
                   (string/join ", "))
        team1-txt (str team1 " |  Team ELO: " (format "%.3f" (:team1-elo-sum team-option)))
        team2 (->> team-option
                   :team2
                   (sort-by val >)
                   (keys)
                   (string/join ", "))
        team2-txt (str team2 " |  Team ELO: " (format "%.3f" (:team2-elo-sum team-option)))]

    {:name title :value (str team1-txt divider team2-txt)}))

(defn format-lobby-players-msg [team-option team1-name team2-name]
  (let [title (str team1-name " VS " team2-name)
        divider "\n------------------------------VS------------------------------\n"
        team1 (->> team-option
                   :team1
                   (sort-by val >)
                   (keys)
                   (string/join ", "))
        team1-txt (str team1-name ": " team1) ;" |  Team ELO: " (format "%.3f" (:team1-elo-sum team-option)))
        team2 (->> team-option
                   :team2
                   (sort-by val >)
                   (keys)
                   (string/join ", "))
        team2-txt (str team2-name ": "  team2)] ;" |  Team ELO: " (format "%.3f" (:team2-elo-sum team-option)))]

    {:name title :value (str team1-txt divider team2-txt)}))

(defn balance-teams-embed [game-mode elo-maps]
  (let [players-elo-map (->> elo-maps
                             (map #(hash-map (:quake-name %) (get % game-mode elo/default-score)))
                             (into {}))
        balanced-team-options (take 3 (balancing/weighted-allocation players-elo-map))
        ; hybrid-team-option (first (balancing/hybrid-draft-weighted-allocation players-elo-map))
        ; drafted-team-option (balancing/draft-allocation players-elo-map)
        ; random-team-option (balancing/shuffle-list players-elo-map)
        team-option-counter (atom 0)

        format-weighted-team (fn [team-option] (format-team-option-msg team-option
                                                                       :option-number (swap! team-option-counter inc)
                                                                       :title-prefix "ELO Weighted "))]
    [{:type "rich"
      :title "Balance Options"
      :description (str "Suggested Teams for " (name game-mode) ":")
      :color 9896156
      :fields (concat
               (map format-weighted-team balanced-team-options)
               [; (format-team-option-msg hybrid-team-option :title-prefix "Hybrid Balance ")
                 ; (format-team-option-msg drafted-team-option :title-prefix "Draft Pick ")
                 ; (format-team-option-msg random-team-option :title-prefix "Random Pick ") ;; dropping it but I am not ready to delete it just yet
                {:name "Players ELOs:"
                 :value (string/join ", "
                                     (map #(str (first %) ": " (format "%.3f" (second %)))
                                          players-elo-map))}])}]))

(defn divide-hub-embed [game-mode sort-method elos lobbies-names spectators]
  (let [team-sizes (balancing/division-into-lobbies-opt (count elos))
        lobby-balance! (fn [elos] (some->> elos
                                           (map #(hash-map (:quake-name %) (get % game-mode elo/default-score)))
                                           (apply merge)
                                           (balancing/hybrid-draft-weighted-allocation)
                                           (first)))
        sort-fn (get balancing/sorting-methods sort-method)

        shuffled-players (sort-fn elos game-mode)
        spectators (if (= (rem (count elos) 2) 1)
                     (conj spectators (last shuffled-players))
                     spectators)
        spectators-names (map :quake-name spectators)

        lobbies-players (split-into-groups-at shuffled-players team-sizes)
        lobbies (zipmap lobbies-names (map lobby-balance! lobbies-players))

        sepctator-field {:name "Spectators" :value (string/join ", " spectators-names)}

        fields (map #(format-lobby-players-msg (second %) (first (first %)) (second (first %))) lobbies)

        msg-fields (if (not-empty spectators)
                     (concat fields [sepctator-field])
                     fields)]

    [{:type "rich"
      :title "Balance Options"
      :description (str "Suggested lobbies teams for " (name game-mode) ":")
      :color 9896156
      :fields msg-fields}]))

