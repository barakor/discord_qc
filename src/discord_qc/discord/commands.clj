(ns discord-qc.discord.commands
  (:require
   [slash.command.structure :as scs]))

; (def refresh-db-command
;   (scs/command
;    "refresh-db"
;    "refresh all stats in the db"))

(def db-stats-command
  (scs/command
   "db-stats"
   "Get stats about the db"))

(def query-command
  (scs/command
   "query"
   "Query Quake player's stats"
   :options
   [(scs/option "discord-id" "Tag a registered discord user" :string :required true)]))

(def rename-command
  (scs/command
   "rename"
   "Rename Quaker"
   :options
   [(scs/option "quake-name" "Quake Name" :string :required true)]))

(def rename-other-command
  (scs/command
   "rename-other"
   "Rename Quaker"
   :options
   [(scs/option "discord-id" "Tag a discord user" :string :required true)
    (scs/option "quake-name" "Quake Name" :string :required true)]))

(def register-command
  (scs/command
   "register"
   "Register Quaker"
   :options
   [(scs/option "discord-id" "Tag a discord user" :string :required true)
    (scs/option "quake-name" "Quake Name" :string :required true)
    (scs/option "score" "Player's score for all modes" :number :required true)]))

(def game-mode-choices [{:name "Sacrifice", :value "sacrifice"}
                        {:name "Objective" :value "objective"}
                        {:name "Killing" :value "killing"}
                        {:name "Sacrifice Tournament", :value "sacrifice-tournament"}
                        {:name "Slipgate", :value "slipgate"}
                        {:name "CTF", :value "ctf"}
                        {:name "TDM", :value "tdm"}
                        {:name "TDM 2V2", :value "tdm-2v2"}])

(def adjust-command
  (scs/command
   "adjust"
   "Adjust Quaker Mode Score"
   :options
   [(scs/option "discord-id" "Tag a discord user" :string :required true)
    (scs/option "game-mode" "Game Mode" :string :required true :choices game-mode-choices)
    (scs/option "score" "Player's score for all modes" :number :required true)]))

(def balance-command
  (scs/command
   "balance"
   "Balance a Quake Champions lobby"
   :options
   [(scs/option "game-mode" "Game Mode" :string
                :required true
                :choices game-mode-choices)

    (scs/option "player-tag1" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag2" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag3" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag4" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag5" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag6" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag7" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag8" "Manually add tagged discord user to lobby" :string)]))

(def divide-command
  (scs/command
   "divide"
   "Divide hub inhabitants to other lobbies"
   :options
   [(scs/option "game-mode" "Game Mode" :string
                :required true
                :choices game-mode-choices)
    (scs/option "spectator-tag1" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag2" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag3" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag4" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag5" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag6" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag7" "Manually tag discord user as a spectator" :string)
    (scs/option "spectator-tag8" "Manually tag discord user as a spectator" :string)
    (scs/option "player-tag1" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag2" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag3" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag4" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag5" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag6" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag7" "Manually add tagged discord user to lobby" :string)
    (scs/option "player-tag8" "Manually add tagged discord user to lobby" :string)]))

(def application-commands [rename-command query-command balance-command divide-command])

(def admin-commands [db-stats-command register-command adjust-command rename-other-command])
                     ; refresh-db-command 

