(ns discord-qc.handle-db
  (:require [discord-qc.storage.rocksdb :refer [get-record put-record!]]))

(defn discord-id->quake-name [discord-id]
  (rocksdb/get-record (str "discord-id->quake-name/" discord-id)))

(defn save-discord-id->quake-name [discord-id quake-name]
  (rocksdb/put-record! (str "discord-id->quake-name/" discord-id) quake-name))

(defn quake-name->elo-map [quake-name]
  (rocksdb/get-record (str "quake-name->elo-map/" quake-name)))

(defn save-quake-name->elo-map [quake-name elo-map]
  (rocksdb/put-record! (str "quake-name->elo-map/" quake-name) elo-map))
