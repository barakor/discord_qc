#!/usr/bin/env bb
;; Convert the legacy Clojure `db-data.edn` into the Rust bot's `db.json`
;; (the `qc_core::Db` schema: {"elos": {id -> PlayerElo}, "admins": [ids]}).
;;
;; Usage: bb scripts/edn->json.clj [input.edn] [output.json]
;;   - input  defaults to src/db-data.edn
;;   - output defaults to db.json (omit-able; pass "-" to force stdout)

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[cheshire.core :as json])

(def elo-prefix "discord-id->elo-map/")
(def default-score 5.0) ; qc_core::DEFAULT_SCORE

;; PlayerElo fields, in (edn-keyword -> json-key) form. Missing modes in a
;; source entry are filled with default-score since the Rust struct has no
;; serde defaults for them.
(def modes
  {:killing "killing"
   :ranked-duel "ranked_duel"
   :tdm "tdm"
   :sacrifice-tournament "sacrifice_tournament"
   :instagib "instagib"
   :slipgate "slipgate"
   :duel "duel"
   :ctf "ctf"
   :ffa "ffa"
   :sacrifice "sacrifice"
   :objective "objective"
   :tdm-2v2 "tdm_2v2"})

(defn numeric-id? [s] (some? (re-matches #"\d+" s)))

(defn ->player-elo [m]
  (reduce (fn [acc [k json-key]] (assoc acc json-key (get m k default-score)))
          {"quake_name" (:quake-name m)}
          modes))

(let [[in out] *command-line-args*
      in   (or in "src/db-data.edn")
      out  (or out "db.json")
      data (edn/read-string (slurp in))
      elos (into (sorted-map)
                 (for [[k v] data
                       :when (and (string? k) (str/starts-with? k elo-prefix))
                       :let [id (subs k (count elo-prefix))]
                       :when (numeric-id? id)]
                   [id (->player-elo v)]))
      admins (->> (get data "admin-ids")
                  (filter numeric-id?)
                  (map #(Long/parseLong %))
                  sort
                  vec)
      db   {"elos" elos "admins" admins}
      js   (json/generate-string db {:pretty true})]
  (if (= out "-")
    (println js)
    (spit out js)))
