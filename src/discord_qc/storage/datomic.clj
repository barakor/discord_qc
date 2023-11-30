(ns discord-qc.storage.datomic 
  (:require [datomic.client.api :as d]
            [com.rpl.specter :as s]
            [clojure.edn :as edn]
            [discord-qc.quake-stats :refer [pull-stats]]))


(def client (d/client {:server-type :datomic-local
                       :system "dev"
                       :storage-dir "/tmp/dataomic"}))

(def discord->quake-stats-schema [{:db/ident :quake.stats/name
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/one
                                   :db/doc "The quake name of the user"}

                                  {:db/ident :quake.stats/stats
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/many
                                   :db/doc "The stats from quake api for the player"}])



(defn init-conn [client db-name & {:keys [schema]}]
  (try
    (d/connect client {:db-name db-name})
    (catch Exception e (do (println "[storage.datomic]: couldn't connect to db: " db-name)
                           (d/create-database client {:db-name db-name})
                           (println "[storage.datomic]: created database" db-name)
                           (let [conn (d/connect client {:db-name db-name})]
                             (println "[storage.datomic]: transacted schema: " (pr-str schema))
                             (when schema 
                               (d/transact conn {:tx-data schema}))
                             conn)))))


(defn discord-quakestats-conn [client]
  (init-conn client "quake-stats" :schema discord->quake-stats-schema))


(defn prepare-quake-stats-map [{quake-name :name :as quake-stats}]
  {:quake.stats/name quake-name
   :quake.stats/stats (pr-str quake-stats)})


(defn quake-stats->db [quake-stats]
  (let [m (prepare-quake-stats-map quake-stats)
        conn (discord-quakestats-conn client)]
    (d/transact conn {:tx-data [m]})
    (println "[storage.datomic]: logged data")))






; (defn db->elo-map [quake-name]
;   (let [conn (quake-scores-conn client)
;         db (d/db conn)
;         query '[:find (pull ?entry [*])
;                 :in $ ?quake-name
;                 :where [?entry :elo/quake-name ?quake-name]]
;         entries (d/q query db quake-name)]
;     (first (last entries))))


; discord->quake-name-schema
; (discord-quake-player->db {:user-id "88533822521507840" :quake-name "lezyes"})

; (d/transact
;   (discord-quakename-conn client)
;   {:tx-data [(prepare-discord-quake-map {:user-id "88533822521507840" :quake-name "lezyes"})]})


; (let [db-name "discord-quakename"
;       schema discord->quake-name-schema]
;   (try
;       (d/connect client {:db-name db-name})
;       (catch Exception e (when-let [db-created (d/create-database client {:db-name db-name})]
;                            (let [conn (d/connect client {:db-name db-name})
;                                  _ (when schema (d/transact conn {:tx-data schema}))]
;                              conn)))))
; (d/create-database client {:db-name "discord-quakename"})
; (d/connect client {:db-name "discord-quakename"})

; (d/q '[:find (pull ?e [*]) :where [?e :elo/quake-name ?]] db)
; (def db (d/db conn))

; (d/q '[:find (pull ?e [*])
;        :in $ ?quake-name
;        :where [?e :elo/quake-name ?quake-name]]
;       db "rapha")

; (d/q '[:find (pull ?e [*])
;        :where [?e :elo/quake-name "lezyes"]]
;    db)

; (def entries (into [] (map #(prepare-elo-map (assoc (get-quake-elo %) :quake-name %)) ["lezyes" "xtortion" "rapha" "bamb1" "iikxii"])))

; entries

; (d/transact conn {:tx-data entries})



; (def db (d/db conn))

; (d/q '[:find ?ffa
;        :where [?quake-name :elo/quake-name "rapha"]
;               [?quake-name :elo/ffa ?ffa]]
;   db)

; (def r (d/q '[:find (pull ?e [*])
;               :where [?e :elo/quake-name "rapha"]]
;         db))

; (last r)

