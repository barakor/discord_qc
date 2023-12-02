(ns discord-qc.interactions
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


(defn map-command-interaction-options [interaction]
  (into {} (map #(hash-map (:name %) (:value %)) (get-in interaction [:data :options]))))  


(defmulti handle-command-interaction 
  (fn [interaction] (get-in interaction [:data :name])))


(defmethod handle-command-interaction "query" [interaction]
  (let [quake-name (lower-case (get (map-command-interaction-options interaction) "quake-name"))]

    (if-let [elo (quake-stats/quake-name->elo-map quake-name)]
      (do 
          (srsp/update-message {:content (pr-str elo)})) ; takes too long, need to fork out and reply later
      (srsp/update-message {:content (str "couldn't find quake name " quake-name)}))))
 

(defmethod handle-command-interaction "register" [interaction]
  (let [quake-name (lower-case (get (map-command-interaction-options interaction) "quake-name"))
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


(defn build-components-action-rows [components]
  (->> components
    (partition-all 5)
    (map #(apply scomp/action-row %))
    (into [])))

  ; (println (partition 5 [0 1 2 3 4 5 6 7 8 9])))


(defmethod handle-command-interaction "balance" [interaction]
  (let [interaction-options (map-command-interaction-options interaction)
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
                        (find-unregistered-users))

        component-id (atom 0)

        components (build-components-action-rows
                      (map #(scomp/button :secondary 
                                          (str "toggle-primary-secondary/" (str "toggle-primary-secondary/" (swap! component-id inc))) 
                                          :label (:quake-name %)) 
                        (vals found-players)))]
    ; create components for each player (toggle buttons), add players (button, if you forgot someone, should open a modal I think?)
    ; logic to figure who're the players and call the balance func on their name->elo map
    ; parse messages with https://autocode.com/tools/discord/embed-builder/
        ; automatically-polled]
    ; (println "sending components: " (pr-str components))
    ; (println "type: " (type (:components (first components))))
    ; (println (pr-str interaction))
    (srsp/channel-message {:content (str (pr-str found-players)) :components components})))


;; Component interactions
(defmulti handle-component-interaction
  ; To make componenet ids unique, they are namespaced, so "name.of.function/1" and "name.of.function/2" point to the same function
  (fn [interaction] 
    (-> interaction
      (get-in [:data :custom-id])
      (string/split #"/")
      (first))))


(defmethod handle-component-interaction "toggle-primary-secondary"
  [interaction]
  (let [primary-secondary-switch (fn [style] (case style 
                                               :secondary :primary 
                                               :primary :secondary))

        old-content (get-in interaction [:message :content])
        
        old-components (->> interaction
                         (s/select [:message :components s/ALL :components s/ALL])
                         (map #(update % :style (set/map-invert scomp/button-styles))))
        
        toggle-componenet-id (s/select-first [:data :custom-id] interaction)

        components (->> old-components
                           (s/transform [(s/filterer #(= (:custom-id %) toggle-componenet-id)) s/ALL] 
                              #(update % :style primary-secondary-switch)) 
                           (map #(scomp/button (:style %) (:custom-id %) :label (:label %)))
                           (build-components-action-rows))]

    (srsp/update-message {:content old-content :components components})))



; (s/select-first [:data :custom-id] @ci)
; (let [old-components (s/select [:message :components s/ALL :components s/ALL] @ci)
;       components (map #(update % :style (set/map-invert scomp/button-styles)) old-components)
      
;       toggle-componenet-id (s/select-first [:data :custom-id] @ci)

;       primary-secondary-switch (fn [style] (case style 
;                                              :secondary :primary 
;                                              :primary :secondary))
;       components-to-add (->> components
;                            (s/transform [(s/filterer #(= (:custom-id %) toggle-componenet-id)) s/ALL] 
;                               #(update % :style primary-secondary-switch)) 
;                            (map #(scomp/button (:style %) (:custom-id %) :label (:label %)))
;                            (build-components-action-rows))]

;    components-to-add)   

(defn command-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-channel-message)) 
  (let [{:keys [type data]} (handle-command-interaction interaction)]
    ; (println "[command-interaction] responding: "
      @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))


(defn component-interaction [interaction]
  @(discord-rest/create-interaction-response! (:rest @state*) (:id interaction) (:token interaction) (:type srsp/deferred-update-message))
  ; add message_author = interactioner check here, we don't want trolls...
  (let [{:keys [type data]} (handle-component-interaction interaction)]
    ; (println "[component-interaction] responding: "
    @(discord-rest/edit-original-interaction-response! (:rest @state*) (:application-id interaction) (:token interaction) data)))
    




;; Routing
(def interaction-handlers
  (assoc sg/gateway-defaults
         :application-command #'command-interaction
         :message-component #'component-interaction))


(get-original-interaction-response!)


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

