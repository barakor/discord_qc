(ns discord-qc.balancing
  (:require [clojure.math.combinatorics :refer [combinations]]
            [com.rpl.specter :as s]))

; (def mock-players-elo {"iikxii" 5.9680834
;                        "cashedcheck" 3.723866
;                        "cubertt" 8.2246895
;                        "bargleloco" 13.569449
;                        "bamb1" 7.8625717
;                        "lezyes" 4.702424
;                        "xtortion" 6.7005982
;                        "rapha" 0.44074476})

(defn complementary-team [all-players team1]
  (apply dissoc all-players (keys team1)))


(defn ideal-team-elo [players_elos]
  (->> players_elos
    (vals)
    (reduce +)
    (#(/ % 2))))


(defn highest-elo-player [players_elos]
  (first (last (sort-by val players_elos))))


(defn team-elo [team]
  (reduce + (vals team)))


(defn distance-from-ideal-team-elo [ideal-team-elo-sum team]
  (abs (- ideal-team-elo-sum (team-elo team))))


(defn diviation-from-ideal-team-elo [ideal-team-elo-sum team]
  (/ (distance-from-ideal-team-elo ideal-team-elo-sum team) ideal-team-elo-sum))


(defn teams [players_elos team1]
  (let [ideal-team-elo-sum (ideal-team-elo players_elos)
        distance-from-ideal-team-elo (partial distance-from-ideal-team-elo ideal-team-elo-sum)
        diviation-from-ideal-team-elo (partial diviation-from-ideal-team-elo ideal-team-elo-sum)
        enrich-teams (fn [team1]
                       (let [team2 (complementary-team players_elos team1)]
                         {:team1 team1
                          :team1-elo-sum (team-elo team1)
                          :team2 team2
                          :team2-elo-sum (team-elo team2)
                          :distance-from-ideal (distance-from-ideal-team-elo team1)
                          :diviation-from-ideal (diviation-from-ideal-team-elo team1)}))]
    (enrich-teams team1)))


(defn weighted-allocation [players_elos]
  (let [team-size (int (/ (count players_elos) 2))
        highest-elo-player-name (highest-elo-player players_elos)
        ideal-elo (ideal-team-elo players_elos)]
    (->> (combinations players_elos team-size)
      (map #(into {} %))
      (s/select [s/ALL #(get % highest-elo-player-name)])
      (sort-by #(diviation-from-ideal-team-elo ideal-elo %))
      ; (take 3)
      (map #(teams players_elos %)))))


(defn shuffle-list [players_elos]
  (let [team-size (int (/ (count players_elos) 2))
        players (->> players_elos
                   (keys)
                   (shuffle)
                   (take team-size))]
    
    (teams  players_elos (select-keys players_elos players))))


(defn draft-allocation [players_elos]
  (->> players_elos
    (sort-by val)
    (partition 2)
    (map second)
    (into {})
    (teams players_elos)))

