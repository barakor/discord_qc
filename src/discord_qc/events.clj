(ns discord-qc.events
  (:require
    [discljord.connections :as discord-ws]
    [discljord.messaging :as discord-rest]
    [slash.core :as sc]
    [slash.response :as srsp]
    [discljord.events.state :as discord-state]

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

(defmethod handle-event :default [type data]
  (println "event type: " (pr-str type))
  (println "event data: " (pr-str data)))

(defmethod handle-event :interaction-create
  [_ event-data]
  (discord-rest/create-interaction-response! (:rest @state*) (:id event-data) (:token event-data) (:type srsp/deferred-channel-message)) 
  (let [{:keys [type data] :as a} (sc/route-interaction interaction-handlers event-data)]
    (discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id event-data) (:token event-data) :content (:content data))))


(defn voice-state-channel-update [_ {:keys [user-id guild-id channel-id] :as voice} state*]
  (when-let [old-channel-id (get-in @state* [:discljord.events.state/users user-id :voice :channel-id])]
    (swap! state* update-in [:voice-channels  old-channel-id] #(clojure.set/difference % #{user-id})))
  (when channel-id
    (swap! state* update-in [:voice-channels channel-id] #(clojure.set/union % #{user-id}))))
  
(defn voice-state-update-wrapper [ _ voice state*]
  (voice-state-channel-update  _ voice state*)
  (discord-state/voice-state-update _ voice state*))

(:voice-state-update discord-state/caching-handlers)
(:voice-state-update caching-handlers)
(def caching-handlers (assoc discord-state/caching-handlers :voice-state-update [#'voice-state-update-wrapper]))

(get-in @discord-state* [:discljord.events.state/users user-id :voice :channel-id])
(println (:channel-id {:suppress false, :deaf false, :session-id "cd1922cfbb586b912fca40d98e6073a5", :self-video false, :self-mute true, :user-id "88533822521507840", :request-to-speak-timestamp nil, :guild-id "199524231963344896", :member {:premium-since nil, :deaf false, :nick nil, :pending false, :roles ["199531101281058816" "1105069675794145311"], :joined-at "2016-07-04T13:58:04.111000+00:00", :avatar nil, :flags 0, :user {:avatar-decoration-data nil, :bot false, :global-name "Lezyes", :username "lezyes", :id "88533822521507840", :avatar "4925434d7a86f955f429bfa8d30d0643", :display-name "Lezyes", :public-flags 256, :discriminator "0"}, :communication-disabled-until nil, :mute false}, :self-deaf true, :channel-id nil, :mute false}))