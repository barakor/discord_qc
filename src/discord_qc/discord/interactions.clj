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
            [discord-qc.discord.utils :refer [get-voice-channel-members user-in-voice-channel? build-components-action-rows]]))


;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command #'command-interaction
         :message-component #'component-interaction))


(def pubo-mock-msg @(discord-rest/get-channel-message! (:rest @state*) "613557878330228736" "1180375751665651784"))
(println (pr-str pubo-mock-msg))


(keys pubo-mock-msg)

(:value (first (:fields (first (:embeds pubo-mock-msg)))))
; (defn balance-pubobot-queue [])

; (:embeds pubo-mock-msg)
; (boolean (re-find #"has started" ))

