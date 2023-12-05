(ns discord-qc.elo
    (:require 
            [discord-qc.handle-db :as db]
            [discord-qc.quake-stats :as quake-stats]))
            

(def mode-names {:sacrifice "sacrifice" 
                 :sacrifice-tournament "sacrifice-tournament" 
                 :ctf "ctf"
                 :slipgate "slipgate"
                 :tdm "tdm" 
                 :tdm-2v2 "tdm-2v2"
                 :ffa "ffa" 
                 :instagib "instagib" 
                 :duel "duel"
                 :killing "killing"
                 :objective "objective"})

(def empty-elomap {:sacrifice 0.0
                   :sacrifice-tournament 0.0
                   :ctf 0.0
                   :slipgate 0.0
                   :tdm 0.0
                   :tdm-2v2 0.0
                   :ffa 0.0
                   :instagib 0.0
                   :duel 0.0
                   :killing 0.0
                   :objective 0.0})

(defn quake-name->elo-map [quake-name]
  (try
    (if-let [elo-map (db/quake-name->elo-map quake-name)]
      elo-map
      (quake-stats/quake-name->elo-map quake-name))
    (catch Exception e (assoc empty-elomap :quake-name quake-name))))

(defn quake-name->mode-elo [quake-name mode]
  (let [elo-map (quake-name->elo-map quake-name)]
    (mode elo-map)))

