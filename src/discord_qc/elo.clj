(ns discord-qc.elo
  (:require
   [discord-qc.handle-db :as db]
   [clojure.pprint :refer [pprint]]))


(def default-score 5.0)

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

(defrecord Elo [^String quake-name
                ^Double sacrifice
                ^Double sacrifice-tournament
                ^Double ctf
                ^Double slipgate
                ^Double tdm
                ^Double tdm-2v2
                ^Double ffa
                ^Double instagib
                ^Double duel
                ^Double killing
                ^Double objective])

(defn Elo->map [^Elo elo]
  (into {} elo))

(defn create-elo-map [quake-name score]
  (->> mode-names
       (keys)
       (map #(hash-map % score))
       (into {:quake-name quake-name})
       (map->Elo)))

(defn discord-id->Elo [^String discord-id]
  (when-let [elo-map (db/discord-id->elo-map discord-id)]
    (map->Elo elo-map)))

(defn save-discord-id->Elo [^String discord-id ^Elo elo]
  (db/save-discord-id->elo-map discord-id (Elo->map elo)))

