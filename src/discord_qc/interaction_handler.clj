(ns discord-qc.interaction-handler
  (:require
   [clojure.edn :as edn]
   [clojure.core.async :refer [chan close!]]
   [discljord.messaging :as discord-rest]
   [discljord.connections :as discord-ws]
   [discljord.formatting :refer [mention-user]]
   [discljord.events :refer [message-pump!]]

   [slash.command.structure :as scs]

   [discord-qc.quake-stats :as quake-stats]
   [discord-qc.balancing :as balancing]
   [discord-qc.elo :as elo]))




(defn interaction-message-response [content components]
  {:type 4 
   :data {:content content 
          :components components}})
