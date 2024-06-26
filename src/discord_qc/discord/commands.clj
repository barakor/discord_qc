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


(def manual-register-command
  (scs/command
   "manual-register"
   "Register Quake name to a discord id manually"
   :options
   [(scs/option "quake-name" "Your Quake Name" :string :required true)
    (scs/option "discord-id" "User's Discord ID" :string :required true)]))


(def game-mode-choices [{:name "Sacrifice", :value "sacrifice"}
                        {:name "Objective" :value "objective"}
                        {:name "Killing" :value "killing"}
                        {:name "Sacrifice Tournament", :value "sacrifice-tournament"}
                        {:name "Slipgate", :value "slipgate"}
                        {:name "CTF", :value "ctf"}
                        {:name "TDM", :value "tdm"}
                        {:name "TDM 2V2", :value "tdm-2v2"}])


(def balance-command
  (scs/command
   "balance"
   "Balance a Quake Champions lobby"
   :options
     [(scs/option "game-mode" "Game Mode" :string 
        :required true 
        :choices game-mode-choices)
             
      (scs/option "quake-name1" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name2" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name3" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name4" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name5" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name6" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name7" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name8" "Manually add quake-name to lobby" :string)]))


(def divide-command
  (scs/command
   "divide"
   "Divide hub inhabitants to other lobbies"
   :options
     [(scs/option "game-mode" "Game Mode" :string 
        :required true 
        :choices game-mode-choices)
      (scs/option "discord-tag1" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag2" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag3" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag4" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag5" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag6" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag7" "Manually tag discord user as a spectator" :string)
      (scs/option "discord-tag8" "Manually tag discord user as a spectator" :string)
      (scs/option "quake-name1" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name2" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name3" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name4" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name5" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name6" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name7" "Manually add quake-name to lobby" :string)
      (scs/option "quake-name8" "Manually add quake-name to lobby" :string)]))



(def application-commands [register-command query-command balance-command divide-command])

(def admin-commands [refresh-db-command db-stats-command manual-register-command])
