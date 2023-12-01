(ns discord-qc.commands
  (:require
    [slash.command.structure :as scs]))
   

    ; [slash.core :as sc]
    ; [slash.command :as scmd]
    ; [slash.response :as srsp]
    ; [slash.gateway :as sg]
    ; [slash.component.structure :as scomp]))
    


(def register-command
  (scs/command
   "register"
   "Register Quake name"
   :options
   [(scs/option "quake name" "Your Quake Name" :string :required true)]))


(def application-commands [register-command])
