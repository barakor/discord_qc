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


(defn ideal-team-elo [players-elos]
  (->> players-elos
    (vals)
    (reduce +)
    (#(/ % 2))))


(defn nth-hightest-elo-player [players-elos rank]
  (first (nth (reverse (sort-by val players-elos)) rank)))


(defn team-elo [team]
  (reduce + (vals team)))


(defn distance-from-ideal-team-elo [ideal-team-elo-sum team]
  (abs (- ideal-team-elo-sum (team-elo team))))


(defn diviation-from-ideal-team-elo [ideal-team-elo-sum team]
  (/ (distance-from-ideal-team-elo ideal-team-elo-sum team) ideal-team-elo-sum))


(defn teams [players-elos team1]
  (let [ideal-team-elo-sum (ideal-team-elo players-elos)
        distance-from-ideal-team-elo (partial distance-from-ideal-team-elo ideal-team-elo-sum)
        diviation-from-ideal-team-elo (partial diviation-from-ideal-team-elo ideal-team-elo-sum)
        enrich-teams (fn [team1]
                       (let [team2 (complementary-team players-elos team1)]
                         {:team1 team1
                          :team1-elo-sum (team-elo team1)
                          :team2 team2
                          :team2-elo-sum (team-elo team2)
                          :distance-from-ideal (distance-from-ideal-team-elo team1)
                          :diviation-from-ideal (diviation-from-ideal-team-elo team1)}))]
    (enrich-teams team1)))


(defn weighted-allocation [players-elos]
  (let [team-size (int (/ (count players-elos) 2))
        highest-elo-player-name (nth-hightest-elo-player players-elos 0)
        ideal-elo (ideal-team-elo players-elos)]
    (->> (combinations players-elos team-size)
      (map #(into {} %))
      (s/select [s/ALL #(get % highest-elo-player-name)])
      (sort-by #(diviation-from-ideal-team-elo ideal-elo %))
      (map #(teams players-elos %)))))


(defn hybrid-draft-weighted-allocation [players-elos]
  (let [team-size (int (/ (count players-elos) 2))
        captain1-name (nth-hightest-elo-player players-elos 0)
        captain2-name (nth-hightest-elo-player players-elos 1)
        ideal-elo (ideal-team-elo players-elos)]
    (->> (combinations players-elos team-size)
      (map #(into {} %))
      (s/select [s/ALL #(and (get % captain1-name) 
                             (not (get % captain2-name)))])
      (sort-by #(diviation-from-ideal-team-elo ideal-elo %))
      (map #(teams players-elos %)))))


(defn shuffle-list [players-elos]
  (let [team-size (int (/ (count players-elos) 2))
        players (->> players-elos
                   (keys)
                   (shuffle)
                   (take team-size))]
    
    (teams  players-elos (select-keys players-elos players))))


(defn draft-allocation [players-elos]
  (->> players-elos
    (sort-by val)
    (partition 2)
    (map second)
    (into {})
    (teams players-elos)))



"
For Every odd N, r = 1, so N = (N-1) + r
Solution for Even N:
- By definition, every modulo operation (6,8) would be even, because N is Even
1) if N%8=0: a = (N//8), b = 0
2) if N%8=2: a = (N//8)-2, b = 3
3) if N%8=4: a = (N//8)-1, b = 2
4) if N%8=6: a = (N//8), b = 1

```
F(x) = 
a = (N//8)-((N%8)%3)
b = (((N%8)%3)+1) if N%8!=0 else 0 
```
"

(defn division-into-lobbies [number-of-players]
  "returns the number of players each lobby should have"
  (let [n (- number-of-players (rem number-of-players 2))
        n%8 (rem n 8)
        n%8%3 (rem n%8 3)
        a (- (quot n 8) n%8%3)
        b (if (= 0 n%8) 0 (+ n%8%3 1))]
    (into [] (concat (repeat a 8) (repeat b 6)))))


(defn division-into-lobbies-opt [number-of-players]
  "returns the number of players each lobby should have"
  (let [n (- number-of-players (rem number-of-players 2))
        n%8 (rem n 8)
        ndiv8 (quot n 8)
        a (case n%8
            0 ndiv8
            2 (- ndiv8 2)
            4 (- ndiv8 1)
            6 ndiv8)
        b (case n%8
            0 0
            2 3
            4 2
            6 1)]
    (into [] (concat (repeat a 8) (repeat b 6)))))

