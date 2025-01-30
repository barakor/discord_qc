(ns discord-qc.elo
  (:require
   [discord-qc.handle-db :as db]))

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

(defn create-elo-map [quake-name score]
  (->> mode-names
       (keys)
       (map #(hash-map % score))
       (into {})
       (#(assoc % :quake-name quake-name))))
