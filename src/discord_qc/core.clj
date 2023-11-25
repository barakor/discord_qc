;; SPDX-License-Identifier: MIT OR 0BSD
(ns discord-qc.core
  (:require
   [clojure.edn :as edn]
   [clojure.core.async :refer [chan close!]]
   [discljord.messaging :as discord-rest]
   [discljord.connections :as discord-ws]
   [discljord.formatting :refer [mention-user]]
   [discljord.events :refer [message-pump!]]))

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

(defmethod handle-event :default [_ _])

(defn start-bot!
  "Start a discord bot using the token specified in `config.edn`.

  Returns a map containing the event channel (`:events`), the gateway connection (`:gateway`) and the rest connection (`:rest`)."
  [token & intents]
  (let [event-channel (chan 100)
        gateway-connection (discord-ws/connect-bot! token event-channel :intents (set intents))
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

(defn -main [& args]
  (reset! state (start-bot! (:token config)))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state))))
  (try
    (message-pump! (:events @state) handle-event)
    (finally (stop-bot! @state))))

