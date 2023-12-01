(ns discord-qc.events
  (:require
    [discljord.connections :as discord-ws]
    [discljord.messaging :as discord-rest]
    [slash.core :as sc]

    [discord-qc.state :refer [state* config]]
    [discord-qc.interactions :refer [interaction-handlers]]))
   

(defmulti handle-event
  "Event handling multi method. Dispatches on the type of the event."
  (fn [type _data] type))


(defmethod handle-event :message-create
  [_ {:keys [channel-id author mentions] :as _data}])
  ; does nothing rn

(defmethod handle-event :ready
  [_ _]
  (discord-ws/status-update! (:gateway @state*) :activity (discord-ws/create-activity :name (:playing config))))
  ;take easter egg from other bot and make quake role every where, manage quake role here instead (integrate them?)

(defmethod handle-event :default [type data])


(defmethod handle-event :interaction-create
  [_ event-data]
  (let [{:keys [type data] :as a} (sc/route-interaction interaction-handlers event-data)]
    (discord-rest/create-interaction-response! (:rest @state*) (:id event-data) (:token event-data) type :data data)))


