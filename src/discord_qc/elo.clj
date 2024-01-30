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


(defn quake-name->elo-map [quake-name]
  (try
    (or (db/quake-name->elo-map quake-name)
        (quake-stats/quake-name->elo-map quake-name))
    (catch Exception _ (quake-stats/get-empty-elo-map quake-name))))

(defn quake-name->mode-elo [quake-name mode]
  (let [elo-map (quake-name->elo-map quake-name)]
    (mode elo-map)))

