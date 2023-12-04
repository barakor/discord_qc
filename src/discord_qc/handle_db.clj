(ns discord-qc.handle-db
  (:require [discord-qc.storage.rocksdb :as rocksdb]
            [clojure.set :as set]))

(def all-quake-names-in-db (atom (rocksdb/get-record "all-quake-names-in-db")))

(defn discord-id->quake-name [discord-id]
  (rocksdb/get-record (str "discord-id->quake-name/" discord-id)))

(defn save-discord-id->quake-name [discord-id quake-name]
  (rocksdb/put-record! (str "discord-id->quake-name/" discord-id) quake-name))

(defn quake-name->elo-map [quake-name]
  (rocksdb/get-record (str "quake-name->elo-map/" quake-name)))

(defn save-quake-name->elo-map [quake-name elo-map]
  (rocksdb/put-record! (str "quake-name->elo-map/" quake-name) elo-map)
  (when (not (contains? @all-quake-names-in-db quake-name))
    (swap! all-quake-names-in-db set/union #{quake-name})
    (rocksdb/put-record! "all-quake-names-in-db" @all-quake-names-in-db)))

(defn save-quake-name->quake-stats [quake-name quake-stats]
  (rocksdb/put-record! (str "quake-name->quake-stats/" quake-name) quake-stats))



(defn migrate-old-db []
  (let [dcids (read-string (slurp "/home/barakor/gits/discord_qc/old_db_migration/dcid_quakename.edn"))
        qcelo (read-string (slurp "/home/barakor/gits/discord_qc/old_db_migration/qcelo.edn"))]

    (for [[dcid quake-name] dcids]
      (when (not (discord-id->quake-name dcid))
        (save-discord-id->quake-name dcid quake-name)))

    (println "finished migrating dcids")

    (for [[quake-name elo] qcelo]
      (when (not (quake-name->elo-map quake-name))
        (save-quake-name->elo-map quake-name elo)))

    (println "finished migrating qcelo")))

