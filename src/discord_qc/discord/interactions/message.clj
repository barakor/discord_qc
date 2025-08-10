(ns discord-qc.discord.interactions.message
  (:require [clojure.string :as string :refer [lower-case]]

            [discljord.messaging :as discord-rest]

            [com.rpl.specter :as s]

            [discord-qc.state :refer [state* discord-state*]]
            [discord-qc.elo :as elo]
            [discord-qc.discord.utils :refer [balance-teams-embed get-user-quake-name]]))

(def pubobot-queues {"sac" :sacrifice
                     "sac-tourney" :sacrifice-tournament
                     "tdm" :tdm
                     "slipgate" :slipgate
                     "ca2v2" :killing
                     "ca4v4" :killing
                     "ctf" :ctf
                     "ffa" :ffa
                     "2v2" :tdm-2v2})

(defn balance-pubobot-queue [msg-event]
  (let [title-msg (s/select-first [:embeds s/FIRST :title] msg-event)]
    (when (re-find (re-pattern "has started") title-msg)
      (let [guild-id (:guild-id msg-event)
            channel-id (:channel-id msg-event)
            msg-id (:id msg-event)
            game-mode (-> title-msg
                          (string/split #"\*")
                          (nth 2)
                          (string/lower-case)
                          (#(get pubobot-queues %)))
            discord-ids (->> msg-event
                             (s/select-first [:embeds s/FIRST :fields s/FIRST :value])
                             (filter #(not (contains? #{\@ \> \<} %)))
                             (apply str)
                             (#(string/split % #" "))
                             (filter #(> (count %) 1))
                             (set))
            playes    (map #(hash-map :discord-id (get-user-quake-name guild-id %)) discord-ids)

            get-players-elo (fn [did]
                              (let [elo (elo/discord-id->Elo did)]
                                (if elo
                                  (assoc elo :discord-id did)
                                  (hash-map :discord-id did :quake-name (get-user-quake-name guild-id did)))))

            elo-maps   (map get-players-elo discord-ids)

            embed-msg (balance-teams-embed game-mode elo-maps)]
        (discord-rest/create-message! (:rest @state*) channel-id :message-reference {:message-id msg-id 
         "message_id" msg-id} :embeds embed-msg)))))
