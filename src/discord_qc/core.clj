(ns discord-qc.core
  (:require
   [clojure.core.async :as async :refer [chan close!]]
   [discljord.messaging :as discord-rest]
   [discljord.connections :as discord-ws]
   [discljord.events :refer [message-pump!]]
   [discljord.events.state :refer [caching-transducer]]

   [discord-qc.state :refer [state* discord-state* config]]
   [discord-qc.discord.events :refer [handle-event caching-handlers]]
   [discord-qc.discord.commands :refer [application-commands admin-commands owner-commands]]

   [taoensso.telemere.timbre :as timbre :refer [log]]
   [taoensso.timbre.tools.logging :refer [use-timbre]]))

(timbre/set-min-level! :debug)
(use-timbre)

(def bot-id (atom nil))

(defn start-bot!
  "Start a discord bot using the token specified in `config.edn`.

  Returns a map containing the event channel (`:events`), the gateway connection (`:gateway`) and the rest connection (`:rest`)."
  [token & {:keys [intents]}]
  (log :info intents)
  (let [caching (caching-transducer discord-state* caching-handlers)
        event-channel (chan (async/sliding-buffer 100000) caching)
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

(defn -main [& args]
  (reset! state* (start-bot! (:token config) :intents (:intents config)))
  (reset! bot-id (:id @(discord-rest/get-current-user! (:rest @state*))))
  (try
    @(discord-rest/bulk-overwrite-guild-application-commands! (:rest @state*) @bot-id "1104894380080365710" (concat application-commands admin-commands owner-commands))
    @(discord-rest/bulk-overwrite-global-application-commands! (:rest @state*) @bot-id (concat application-commands admin-commands))
    (log :info "updated application slash commands ")
    (catch Exception e (log :error e)))
  (try
    (message-pump! (:events @state*) handle-event)
    (finally (stop-bot! @state*))))

; (-main)
