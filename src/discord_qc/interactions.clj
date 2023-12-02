(ns discord-qc.interactions
  (:require [clojure.string :refer [lower-case]]

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


(defn map-interaction-options [interaction]
  (into {} (map #(hash-map (:name %) (:value %)) (get-in interaction [:data :options]))))  


(defmulti handle-command-interaction 
  (fn [interaction] (get-in interaction [:data :name])))


(defmethod handle-command-interaction "query" [interaction]
  (let [quake-name (lower-case (get (map-interaction-options interaction) "quake-name"))]

    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (srsp/update-message {:content (pr-str elo)})) ; takes too long, need to fork out and reply later
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))
 

(defmethod handle-command-interaction "register" [interaction]
  (let [quake-name (lower-case (get (map-interaction-options interaction) "quake-name"))
        user-id (s/select-first [:member :user :id] interaction)]
    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (db/save-discord-id->quake-name user-id quake-name)
          (srsp/update-message {:content (pr-str elo)})) ; takes too long, need to fork out and reply later
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))


(defn get-voice-channel-members [channel-id]
  (get-in @discord-state* [:voice-channels channel-id]))


(defn user-in-voice-channel? [user-id]
  (get-in @discord-state* [:discljord.events.state/users user-id :voice :channel-id]))


(defn find-registered-users [user-ids]
  (->> user-ids
    (map #(hash-map % (when-let [quake-name (db/discord-id->quake-name %)] 
                        {:quake-name quake-name :registered true}))) 
    (apply merge)))

(defn get-user-display-name [user-id]
  (when-let [display-name (get-in @discord-state* [:discljord.events.state/users user-id :display-name])]
    (lower-case display-name)))

(defn find-unregistered-users [users]
  (->> users 
    (map #(if (nil? (second %))
            (hash-map (first %) {:quake-name (get-user-display-name (first %)) :registered false})
            %))
    (apply merge)))

(defmethod handle-command-interaction "balance" [interaction]
  (let [interaction-options (map-interaction-options interaction)
        game-mode (get interaction-options "game-mode")
        quake-names (-> interaction-options
                      (dissoc "game-mode")
                      (vals)
                      (map lower-case))
        user-id (s/select-first [:member :user :id] interaction)

        voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        
        found-players (->> voice-channel-members 
                        (find-registered-users)
                        (find-unregistered-users))]

    ; create components for each player (toggle buttons), add players (button, if you forgot someone, should open a modal I think?)
    ; logic to figure who're the players and call the balance func on their name->elo map
    ; parse messages with https://autocode.com/tools/discord/embed-builder/
        ; automatically-polled]
    (println found-players)

    (srsp/update-message {:content (str (pr-str found-players))})))

(db/save-discord-id->quake-name "88533822521507840" nil)

(db/discord-id->quake-name "88533822521507840")

(let [  user-id "88533822521507840"
        voice-channel-id (user-in-voice-channel? user-id)
        voice-channel-members (get-voice-channel-members voice-channel-id)
        
        found-players (->> voice-channel-members 
                        (find-registered-users)
                        (find-unregistered-users))]
  (println found-players))



;; Component interactions
(defmulti handle-component-interaction
  (fn [interaction] (get-in interaction [:data :custom-id])))

(def num-signups (atom 0))
(defmethod handle-component-interaction "sign-up"
  [interaction]
  (swap! num-signups inc)
  (srsp/update-message {:content (str "i have updated this message " @num-signups " times")}))

;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command #'handle-command-interaction
         :message-component #'handle-component-interaction))




; (defn interaction-message-response [& {:keys [content components]}]
;   {:type 4 
;    :data {:content content 
;           :components components}})

; (defmulti handle-interaction
;   "interaction handling multi method. Dispatches on the name of the interaction."
;   (let [interaction-types {1 :ping
;                            2 :application-command
;                            3 :message-component
;                            4 :application-command-autocomplete
;                            5 :modal-submit}])
;   (fn [{{name :name } :data}] name))

; (defmethod handle-interaction :cmd/register
;   [interaction]
;   (let [components [(scomp/action-row
;                      (scomp/button :success "sign-up" :label "Sign up"))]]
;     (interaction-message-response :content "this is a test of indie" :components components)))
  
; (defmethod handle-interaction :default [interaction]
;   (println "interaction: " interaction))
