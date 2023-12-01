(ns discord-qc.elo
    (:require 
            [discord-qc.handle-db :as db]
            [discord-qc.quake-stats :as quake-stats]
            
            [clojure.pprint :refer [pprint]]))
            

(def mode-names {:sacrifice "Sacrifice" 
                 :sacrifice-tournament "Sacrifice Tournament" 
                 :ctf "CTF"
                 :slipgate "Slipgate"
                 :tdm "TDM" 
                 :tdm-2v2 "TDM 2v2"
                 :ffa "Deathmatch" 
                 :instagib "instagib" 
                 :duel "Duel"
                 :killing "Killing"
                 :objective "Objective"})
; (clojure.set/map-invert mode-names)


(defn quake-name->elo-map [quake-name]
  (if-let [elo-map (db/quake-name->elo-map quake-name)]
    elo-map
    (quake-stats/quake-name->elo-map quake-name)))


(defn quake-name->mode-elo [quake-name mode]
  (let [elo-map (quake-name->elo-map quake-name)]
    (mode elo-map)))

