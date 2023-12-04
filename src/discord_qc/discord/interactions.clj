(ns discord-qc.discord.interactions
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
            [discord-qc.discord.interactions.command :refer [command-interaction]]
            [discord-qc.discord.interactions.component :refer [component-interaction]]
            [discord-qc.discord.utils :refer [get-voice-channel-members user-in-voice-channel? build-components-action-rows balance-teams-embed]]))


;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command #'command-interaction
         :message-component #'component-interaction))


(def pubobot-queues {"sac" :sacrifice
                     "tdm" :tdm
                     "slipgate" :slipgate
                     "ca2v2" :killing
                     "ca4v4" :killing
                     "ctf" :ctf
                     "ffa" :ffa
                     "2v2" :tdm-2v2})


(defn get-user-quake-name [guild-id user-id]
  (if-let [quake-name (db/discord-id->quake-name user-id)]
    quake-name
    (if-let [display-name (get-in @discord-state* [:discljord.events.state/users user-id :display-name])]
      (lower-case display-name)
      (let [member @(discord-rest/get-guild-member! (:rest @state*) guild-id user-id)
            nick (:nick member)
            global-name (get-in member [:user :global-name])]
        (lower-case (if nick nick global-name))))))
        

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
            players (->> msg-event
                      (s/select-first [:embeds s/FIRST :fields s/FIRST :value])
                      (filter #(not (contains? #{\@ \> \<} %)))
                      (apply str)
                      (#(string/split % #" "))
                      (filter #(> (count %) 1))
                      (set)
                      (map (partial get-user-quake-name guild-id)))
            embed-msg (balance-teams-embed game-mode players)]
        (discord-rest/create-message! (:rest @state*) channel-id :message-reference msg-id :embeds embed-msg)))))



