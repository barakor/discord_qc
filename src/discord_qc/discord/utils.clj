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
            [discord-qc.handle-db :as db]))


(defn get-voice-channel-members [channel-id]
  (get-in @discord-state* [:voice-channels channel-id]))

(defn user-in-voice-channel? [user-id]
  (get-in @discord-state* [:discljord.events.state/users user-id :voice :channel-id]))


(defn build-components-action-rows [components]
  (->> components
    (partition-all 5)
    (map #(apply scomp/action-row %))
    (into [])))

