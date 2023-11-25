(ns discord-qc.balancing
  (:require [clojure.math.combinatorics :refer [combinations]]
            [com.rpl.specter :as s]
            [clojure.pprint :refer [pprint]]))

(def mock-players-elo {"iikxii" 5.9680834
                       "cashedcheck" 3.723866
                       "cubertt" 8.2246895
                       "bargleloco" 13.569449
                       "bamb1" 7.8625717
                       "lezyes" 4.702424
                       "xtortion" 6.7005982
                       "rapha" 0.44074476})


(defn weighted-allocation [players_elos]
  (let [ideal-team-elo-sum (->> players_elos
                             (vals)
                             (reduce +)
                             (#(/ % 2)))
        top-player (first (last (sort-by val players_elos)))

        distance-from-ideal (fn [team-elos] (abs (- ideal-team-elo-sum (reduce + (vals team-elos)))))
        team1-combs (->> (combinations players_elos 4)
                      (map #(into {} %))
                      (s/select [s/ALL #(get % top-player)])
                      (map #(assoc % :team-elo-sum (reduce + (vals %))))
                      (map #(assoc % :distance-from-ideal (abs (- ideal-team-elo-sum (:team-elo-sum %)))))
                      (map #(assoc % :diviation-from-ideal (/ (:distance-from-ideal %) ideal-team-elo-sum)))
                      (sort-by :distance-from-ideal)
                      (take 3))]
    team1-combs))

(pprint (random-sample mock-players-elo))
(weighted-allocation mock-players-elo)
(s/select [s/ALL #(get % "bamb1")] (weighted-allocation mock-players-elo))

; async def shuffle_list(players_elo):
;     l = list(players_elo.items())
;     for i in range(len(l) * 2):
;         r = randint(0, len(l) - 1)
;         l.append(l.pop(r))
;     return [(dict(l[0::2]), dict(l[1::2]))]

; async def pick_from_top(players_elo):
;     soreted_by_elo = sorted(players_elo.items(), key=lambda x:x[1], reverse=True)
;     team1_elos = dict(soreted_by_elo[0::2])
;     team2_elos = dict(soreted_by_elo[1::2])
;     return [(team1_elos, team2_elos)]

; async def weighted_player_allocation(players_elo):
;     soreted_by_elo = sorted(players_elo.items(), key=lambda x:x[1], reverse=True)
;     elo_sum = sum(players_elo.values())
;     team1_options = sorted([(comb, abs(elo_sum/2-sum((player[1] for player in comb)))) 
;                              for comb in combinations(soreted_by_elo, len(players_elo)//2) 
;                              if soreted_by_elo[0] in comb],
;                            key=lambda x:x[1])
;     top3_teams = team1_options[:3]
;     teams = []
;     for team1_option in top3_teams:
;         team1_elo = {p[0]:players_elo[p[0]] for p in team1_option[0]}
;         team2_elo = {p:v for p,v in players_elo.items() if p not in team1_elo}
        
;         teams.append((team1_elo,team2_elo))
;     return teams