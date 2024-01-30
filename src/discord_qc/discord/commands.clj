(ns discord-qc.discord.commands
  (:require
    [slash.command.structure :as scs]))


(def refresh-db-command
  (scs/command
   "refresh-db"
   "refresh all stats in the db"))


(def db-stats-command
  (scs/command
   "db-stats"
   "Get stats about the db"))


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


(def balance-command
  (scs/command
   "balance"
   "Balance a Quake Champions lobby"
   :options
     [(scs/option "game-mode" "Game Mode" :string 
        :required true 
        :choices [{:name "Sacrifice", :value "sacrifice"}
                  {:name "Objective" :value "objective"}
                  {:name "Killing" :value "killing"}
                  {:name "Sacrifice Tournament", :value "sacrifice-tournament"}
                  {:name "Slipgate", :value "slipgate"}
                  {:name "CTF", :value "ctf"}
                  {:name "TDM", :value "tdm"}
                  {:name "TDM 2V2", :value "tdm-2v2"}])
             
      (scs/option "quake-name1" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name2" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name3" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name4" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name5" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name6" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name7" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name8" "Manually add quake-name to lobby" :string)]))
                   

(def application-commands [register-command query-command balance-command])

(def admin-commands [refresh-db-command db-stats-command])
