(ns discord-qc.handle-db
  (:require [discord-qc.storage.rocksdb :as rocksdb]
            [clojure.set :as set]))


(def all-quake-names-in-db (atom (rocksdb/get-record "all-quake-names-in-db")))


(defn save-discord-id->quake-name [discord-id quake-name]
  (rocksdb/put-record! (str "discord-id->quake-name/" discord-id) quake-name))


(defn discord-id->quake-name [discord-id]
  (rocksdb/get-record (str "discord-id->quake-name/" discord-id)))


(defn save-quake-name->elo-map [quake-name elo-map]
  (rocksdb/put-record! (str "quake-name->elo-map/" quake-name) elo-map)
  (when (not (contains? @all-quake-names-in-db quake-name))
    (swap! all-quake-names-in-db set/union #{quake-name})
    (rocksdb/put-record! "all-quake-names-in-db" @all-quake-names-in-db)))


(defn quake-name->elo-map [quake-name]
  (rocksdb/get-record (str "quake-name->elo-map/" quake-name)))


(defn save-quake-name->quake-stats [quake-name quake-stats]
  (rocksdb/put-record! (str "quake-name->quake-stats/" quake-name) quake-stats))


(defn quake-name->quake-stats [quake-name]
  (rocksdb/get-record (str "quake-name->quake-stats/" quake-name)))

