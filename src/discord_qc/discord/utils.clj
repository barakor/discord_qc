(ns discord-qc.discord.utils
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
            [discord-qc.handle-db :as db]
            [discord-qc.elo :as elo]
            [discord-qc.balancing :as balancing]))


(defn get-voice-channel-members [channel-id]
  (get-in @discord-state* [:voice-channels channel-id]))

(defn user-in-voice-channel? [user-id]
  (get-in @discord-state* [:discljord.events.state/users user-id :voice :channel-id]))


(defn build-components-action-rows [components]
  (->> components
    (partition-all 5)
    (map #(apply scomp/action-row %))
    (into [])))

(defn format-team-option-msg [team-option & {:keys [option-number title-prefix]}]
  (let [title (str title-prefix " Team Option" 
                   (when option-number (str " #" option-number)) 
                   ", Diviation from ideal: " (format "%.3f" (:diviation-from-ideal team-option)) "%")
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


(defn balance-teams-embed [game-mode players]
  (let [players-elo-map (->> players
                          (map elo/quake-name->elo-map)
                          (map #(hash-map (:quake-name %) (game-mode %)))
                          (apply merge))
        balanced-team-options (take 3 (balancing/weighted-allocation players-elo-map))
        drafted-team-option (balancing/draft-allocation players-elo-map)
        random-team-option (balancing/shuffle-list players-elo-map)
        team-option-counter (atom 0)

        format-weighted-team (fn [team-option] (format-team-option-msg team-option 
                                                 :option-number (swap! team-option-counter inc) 
                                                 :title-prefix "ELO Weighted "))
        embed-msg  [{:type "rich" :title "Balance Options" :description (str "Suggested Teams for " (name game-mode) ":"  ):color 9896156
                     :fields (concat 
                               (map format-weighted-team balanced-team-options)
                               [(format-team-option-msg drafted-team-option :title-prefix "Draft Pick ")
                                (format-team-option-msg random-team-option :title-prefix "Random Pick ")])}]]
    embed-msg))

