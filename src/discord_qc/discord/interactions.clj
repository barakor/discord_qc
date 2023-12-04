(ns discord-qc.discord.interactions
  (:require 
            [slash.gateway :as sg]
            
            [discord-qc.discord.interactions.command :refer [command-interaction]]
            [discord-qc.discord.interactions.component :refer [component-interaction]]))


;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command #'command-interaction
         :message-component #'component-interaction))

