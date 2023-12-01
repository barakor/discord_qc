(ns discord-qc.core
  (:require
   [clojure.edn :as edn]
   [clojure.core.async :refer [chan close!]]
   [discljord.messaging :as discord-rest]
   [discljord.connections :as discord-ws]
   [discljord.formatting :refer [mention-user]]
   [discljord.events :refer [message-pump!]]

   [slash.core :as sc]
   [slash.command :as scmd]
   [slash.command.structure :as scs]
   [slash.response :as srsp]
   [slash.gateway :as sg]
   [slash.component.structure :as scomp]

   [discord-qc.quake-stats :as quake-stats]
   [discord-qc.balancing :as balancing]
   [discord-qc.elo :as elo]))

(def state (atom nil))

(def bot-id (atom nil))

(def config (edn/read-string (slurp "config.edn")))

(defmulti handle-event
  "Event handling multi method. Dispatches on the type of the event."
  (fn [type _data] type))

(defn random-response [user]
  (str (rand-nth (:responses config)) ", " (mention-user user) \!))

(defmethod handle-event :message-create
  [_ {:keys [channel-id author mentions] :as _data}]
  (when (some #{@bot-id} (map :id mentions))
    (discord-rest/create-message! (:rest @state) channel-id :content (random-response author))))

(defmethod handle-event :ready
  [_ _]
  (discord-ws/status-update! (:gateway @state) :activity (discord-ws/create-activity :name (:playing config))))


(defmethod handle-event :default [type data]
  (println "event type: " type)
  (println "event data: " data))



(defn interaction-message-response [& {:keys [content components]}]
  {:type 4 
   :data {:content content 
          :components components}})

(defmulti handle-interaction
  "interaction handling multi method. Dispatches on the name of the interaction."
  (fn [{{name :name } :data}] name))

(defmethod handle-interaction :cmd/register
  [interaction]
  (let [components [(scomp/action-row
                     (scomp/button :success "sign-up" :label "Sign up"))]]
    (interaction-message-response :content "this is a test of indie" :components components)))
  
(defmethod handle-interaction :default [interaction]
  (println "interaction: " interaction))





(defmethod handle-event :interaction-create
  [_ event-data]
  (let [{:keys [type data] :as a} (handle-interaction event-data)]
    (println @(discord-rest/create-interaction-response! (:rest @state) (:id event-data) (:token event-data)  type :data data))))






(defn start-bot!
  "Start a discord bot using the token specified in `config.edn`.

  Returns a map containing the event channel (`:events`), the gateway connection (`:gateway`) and the rest connection (`:rest`)."
  [token & {:keys [intents]}]
  (println intents)
  (let [event-channel (chan 100)
        gateway-connection (discord-ws/connect-bot! token event-channel :intents intents)
        rest-connection (discord-rest/start-connection! token)]
    {:events  event-channel
     :gateway gateway-connection
     :rest    rest-connection}))

(defn stop-bot!
  "Takes a state map as returned by [[start-bot!]] and stops all the connections and closes the event channel."
  [{:keys [rest gateway events] :as _state}]
  (discord-rest/stop-connection! rest)
  (discord-ws/disconnect-bot! gateway)
  (close! events))



; (require '[slash.command :as cmd] 
;          '[slash.response :as rsp :refer [channel-message ephemeral]]) ; The response namespace provides utility functions to create interaction responses
(def input-option (scs/option "input" "Your input" :string :required true))
(def register-command
  (scs/command
   "register"
   "Register Quake name"
   :options
   [input-option]))
; (cmd/defhandler register-handler
;   ["register"] ; Command path
;   _interaction ; Interaction binding - whatever you put here will be bound to the entire interaction
;   [input] ; Command options - can be either a vector or a custom binding (symbol, map destructuring, ...)
;   (channel-message {:content input}))
; (cmd/defpaths command-paths
;   (cmd/group ["reg"] ; common prefix for all following commands
;     register-handler))


; (require '[discljord.messaging :as rest]
;          '[discljord.connections :as gateway]
;          '[discljord.events :as events]
;          '[clojure.core.async :as a]
;          '[slash.gateway :refer [gateway-defaults wrap-response-return]]
;          '[slash.core :refer [route-interaction]])


(def guild-id "1104894380080365710")
(defn -main [& args]
  (reset! state (start-bot! (:token config) :intents (:intents config)))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state))))
  (discord-rest/bulk-overwrite-guild-application-commands! (:rest @state) @bot-id guild-id [register-command])
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))



(-main)

; event-handler (-> slash.core/route-interaction
;                 (partial (assoc gateway-defaults :application-command command-paths))
;                 (wrap-response-return (fn [id token {:keys [type data]}]
;                                         (rest/create-interaction-response! (:rest @state) id token type :data data))))
; (message-pump! event-channel (partial events/dispatch-handlers {:interaction-create [#(event-handler %2)]}))



; (-main) 


; (reset! state (start-bot! (:token config) :intents (:intents config)))




