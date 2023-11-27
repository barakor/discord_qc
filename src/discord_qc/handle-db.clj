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
  (swap! all-quake-names-in-db set/union #{quake-name})
  (rocksdb/put-record! "all-quake-names-in-db" @all-quake-names-in-db))

