(ns discord-qc.commands
  (:require
    [slash.command.structure :as scs]))
   

    ; [slash.core :as sc]
    ; [slash.command :as scmd]
    ; [slash.response :as srsp]
    ; [slash.gateway :as sg]
    ; [slash.component.structure :as scomp]))
    
(def query-command
  (scs/command
   "query"
   "Query Quake player's stats"
   :options
   [(scs/option "quake-name" "Quake Name" :string :required true)]))


(def register-command
  (scs/command
   "register"
   "Register Quake name"
   :options
   [(scs/option "quake-name" "Your Quake Name" :string :required true)]))


(def application-commands [register-command query-command])
