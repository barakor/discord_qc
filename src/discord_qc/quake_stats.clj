(ns discord-qc.quake-stats
  (:require 
            [clojure.set :refer [rename-keys]]
            [clojure.string :as string]

            [org.httpkit.client :as http]
            [cheshire.core :refer [parse-string]]
            [camel-snake-kebab.core :as csk]

            [com.rpl.specter :as s]

            [discord-qc.handle-db :as db]))
            


(defn- http-get [path]
  (let [response @(http/get path)
        body (:body response)
        res (parse-string body csk/->kebab-case-keyword)]
    (case (:code res)
      404 nil
      res)))


(defn- pull-stats [quake-name]
  (let [url (str "https://quake-stats.bethesda.net/api/v2/Player/Stats?name=" (string/replace quake-name #" " "%20"))]
    (http-get url)))


(defonce ^:private objective-modes #{:game-mode-obelisk :game-mode-obelisk-pro :game-mode-ctf})


(defonce ^:private killing-modes #{:game-mode-slipgate :game-mode-team-deathmatch :game-mode-team-deathmatch-2vs-2
                                   :game-mode-ffa :game-mode-instagib :game-mode-duel})


(defn modes-agg-stats [stats mode-name]
  (let [game-modes-key-path [:player-profile-stats :champions s/MAP-VALS :game-modes]]
    (->> stats
      (s/select [game-modes-key-path mode-name])
      (map #(select-keys % [:won :lost :tie :kills :deaths :life-time :time-played :score]))
      (apply merge-with +))))


(defn- calc-mode-score [[game-mode-name mode-agg-stats]]
  (let [wins                       (:won mode-agg-stats)
        losses                     (:lost mode-agg-stats)
        ties                       (:tie mode-agg-stats)
        games-count                (+ wins losses ties)
        kills                      (:kills mode-agg-stats)
        deaths                     (:deaths mode-agg-stats)
        total-play-time-in-minutes (/ (:time-played mode-agg-stats) 60000)
        total-score                (:score mode-agg-stats)
        win-ratio                  (cond 
                                     (contains? killing-modes game-mode-name) (/ kills (max 1 deaths))
                                     :else (/ wins (max 1 losses)))
        win-ratio-factor           (if (> games-count 100)
                                     (max (min 1.2 win-ratio) 1)
                                     1)
        avg-score-per-minute       (/ total-score (max 1 total-play-time-in-minutes))
        mode-score                 (float (* avg-score-per-minute win-ratio-factor))]

    {game-mode-name mode-score}))


(defn collective-elo [elos game-modes]
  (let [scores (->> elos
                 (#(select-keys % game-modes))
                 (vals)
                 (remove #(< % 1)))]

    (->> scores
      (#(reduce + %))
      (#(/ % (max 1 (count scores)))))))


(defn- calc-elos [stats]
  (let [modes-renames       {:game-mode-obelisk :sacrifice 
                             :game-mode-obelisk-pro :sacrifice-tournament 
                             :game-mode-ctf :ctf
                             :game-mode-slipgate :slipgate
                             :game-mode-team-deathmatch :tdm 
                             :game-mode-team-deathmatch-2vs-2 :tdm-2v2 
                             :game-mode-ffa :ffa 
                             :game-mode-instagib :instagib 
                             :game-mode-duel :duel 
                             :game-mode-duel-pro :ranked-duel}
        game-modes-key-path [:player-profile-stats :champions s/MAP-VALS :game-modes]
        modes-list          (->> stats
                              (s/select [game-modes-key-path s/MAP-KEYS])
                              (distinct))
        modes-agg-stats     (partial modes-agg-stats stats)
        stats-per-mode      (apply merge (map #(hash-map % (modes-agg-stats %)) modes-list))
        elos                (->> stats-per-mode
                              (map calc-mode-score)
                              (apply merge))
        objectives-elo      (collective-elo elos objective-modes)
        killings-elo        (collective-elo elos killing-modes)]
     
     (-> elos
       (assoc :killing killings-elo)
       (assoc :objective objectives-elo)
       (rename-keys modes-renames))))


(defn get-empty-elo-map [quake-name]
  (let [empty-elomap {:sacrifice 0.0
                      :sacrifice-tournament 0.0
                      :ctf 0.0
                      :slipgate 0.0
                      :tdm 0.0
                      :tdm-2v2 0.0
                      :ffa 0.0
                      :instagib 0.0
                      :duel 0.0
                      :killing 0.0
                      :objective 0.0}]

    (assoc empty-elomap :quake-name quake-name)))


(defn quake-name->elo-map [quake-name]
  (if-let [stats (pull-stats quake-name)]
    (do
      (db/save-quake-name->quake-stats quake-name stats)
      (let [elo-map (assoc (calc-elos stats) :quake-name quake-name)]
        (db/save-quake-name->elo-map quake-name elo-map)
        elo-map))
    (get-empty-elo-map quake-name)))
