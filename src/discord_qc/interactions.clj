(ns discord-qc.interactions
  (:require
    [slash.command.structure :as scs]
    [slash.core :as sc]
    [slash.command :as scmd]
    [slash.response :as srsp]
    [slash.gateway :as sg]
    [slash.component.structure :as scomp]

    [com.rpl.specter :as s]
    
    [discord-qc.quake-stats :as quake-stats]
    [discord-qc.handle-db :as db]))
   

(scmd/defhandler query-handler
  ["query"]
  interaction
  []
  (let [
        quake-name (s/select-first [:data :options s/FIRST :value] interaction)]    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (srsp/update-message {:content (pr-str elo)})) ; takes too long, need to fork out and reply later
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))
 

(scmd/defhandler register-handler
  ["register"]
  interaction
  []
  ; (println interaction)
  (let [
        quake-name (s/select-first [:data :options s/FIRST :value] interaction)
        user-id (s/select-first [:member :user :id] interaction)]
    
    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (db/save-discord-id->quake-name user-id quake-name)
          (srsp/update-message {:content (pr-str elo)})) ; takes too long, need to fork out and reply later
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))

(scmd/defpaths command-paths #'register-handler #'query-handler)

;; Component interactions
(defmulti handle-component-interaction
  (fn [interaction] (-> interaction :data :custom-id)))

(def num-signups (atom 0))
(defmethod handle-component-interaction "sign-up"
  [interaction]
  (swap! num-signups inc)
  (srsp/update-message {:content (str "i have updated this message " @num-signups " times")}))

;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command command-paths
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

