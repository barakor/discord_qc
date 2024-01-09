(ns discord-qc.discord.events
  (:require
    [discljord.connections :as discord-ws]
    [slash.core :as sc]
    [discljord.events.state :as discord-state]

    [discord-qc.state :refer [state* config]]
    [discord-qc.discord.interactions :refer [interaction-handlers]]
    [discord-qc.discord.interactions.message :refer [balance-pubobot-queue]]
    
    [taoensso.timbre :as timbre :refer [log]]))
   

(defmulti handle-event
  "Event handling multi method. Dispatches on the type of the event."
  (fn [type _data] type))


(defmethod handle-event :message-create [_ {:keys [channel-id author embeds mentions] :as event-data}]
  (try
    (when (and (not-empty embeds) (re-find (re-pattern "has started") (get  (first embeds) :title "")))
      (balance-pubobot-queue event-data))
    (catch Exception e (log :error (str "Couldn't parse message: " e)))))
  ; does nothing rn

(defmethod handle-event :ready
  [_ _]
  (discord-ws/status-update! (:gateway @state*) :activity (discord-ws/create-activity :name (:playing config))))
  ;take easter egg from other bot and make quake role every where, manage quake role here instead (integrate them?)

(defmethod handle-event :default [type data])


(defmethod handle-event :interaction-create
  [_ event-data]
  (sc/route-interaction interaction-handlers event-data))

(defn voice-state-channel-update [_ {:keys [user-id guild-id channel-id] :as voice} state*]
  (when-let [old-channel-id (get-in @state* [:discljord.events.state/users user-id :voice :channel-id])]
    (swap! state* update-in [:voice-channels  old-channel-id] #(clojure.set/difference % #{user-id})))
  (when channel-id
    (swap! state* update-in [:voice-channels channel-id] #(clojure.set/union % #{user-id}))))
  
(defn voice-state-update-wrapper [ _ voice state*]
  (voice-state-channel-update  _ voice state*)
  (discord-state/voice-state-update _ voice state*))


;TODO : ADD A :guild-crete event wrapper to start the :voice-channels state

(def caching-handlers (assoc discord-state/caching-handlers :voice-state-update [#'voice-state-update-wrapper]))

