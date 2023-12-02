(ns discord-qc.core
  (:require
    [clojure.edn :as edn]
    [clojure.core.async :refer [chan close!]]
    [discljord.messaging :as discord-rest]
    [discljord.connections :as discord-ws]
    [discljord.formatting :refer [mention-user]]
    [discljord.events :refer [message-pump!]]
    [discljord.events.state :refer [caching-middleware caching-transducer voice-state-update]]

    [discord-qc.state :refer [state* discord-state* config]]
    [discord-qc.discord.events :refer [handle-event caching-handlers]]
    [discord-qc.discord.commands :refer [application-commands]]
    [discord-qc.quake-stats :as quake-stats]
    [discord-qc.balancing :as balancing]
    [discord-qc.elo :as elo]))


(def bot-id (atom nil))



(defn start-bot!
  "Start a discord bot using the token specified in `config.edn`.

  Returns a map containing the event channel (`:events`), the gateway connection (`:gateway`) and the rest connection (`:rest`)."
  [token & {:keys [intents]}]
  (println intents)
  (let [caching (caching-transducer discord-state* caching-handlers)
        event-channel (chan 100 caching)
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



(def guild-id "1104894380080365710")
(defn -main [& args]
  (reset! state* (start-bot! (:token config) :intents (:intents config)))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state*))))
  (try 
    @(discord-rest/bulk-overwrite-guild-application-commands! (:rest @state*) @bot-id guild-id application-commands)
    (println "updated guild commands")
    (catch Exception e (println e)))
  (try
    (message-pump! (:events @state*) handle-event)
    (finally (stop-bot! @state*))))

(-main)
; (reset! state* (start-bot! (:token config) :intents (:intents config)))
