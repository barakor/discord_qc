(ns discord-qc.handle-db
  (:require [discord-qc.storage.rocksdb :as rocksdb]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.java.io :refer [writer]]
            [clojure.pprint :refer [pprint]]))

(def all-discord-ids-in-db* (atom (rocksdb/get-record "all-discord-ids-in-db")))

(def admin-ids* (atom (rocksdb/get-record "admin-ids")))

(defn refresh-db-from-gihub []
  (let [db-data-from-github (edn/read-string (slurp "https://raw.githubusercontent.com/barakor/discord_qc/db-data/db-data.edn"))]
    (doall (map (fn [[k v]] (rocksdb/put-record! k v)) db-data-from-github))
    (reset! all-discord-ids-in-db* (rocksdb/get-record "all-discord-ids-in-db"))
    (reset! admin-ids* (rocksdb/get-record "admin-ids"))))

(defn get-db-map []
  (rocksdb/get-db-map))

(defn db->edn []
  (with-out-str (pprint (into (sorted-map) (get-db-map)))))

(defn- write-db-to-file [db-file-path]
  (with-open [w (writer db-file-path)]
    (binding [*out* w
              *print-length* false]
      (println (db->edn)))))

; (write-db-to-file "db-data.edn")

(defn save-discord-id->elo-map [discord-id elo-map]
  (rocksdb/put-record! (str "discord-id->elo-map/" discord-id) elo-map)
  (when (not (contains? @all-discord-ids-in-db* discord-id))
    (swap! all-discord-ids-in-db* set/union #{discord-id})
    (rocksdb/put-record! "all-discord-ids-in-db" @all-discord-ids-in-db*)))

(defn discord-id->quake-name [discord-id]
  (:quake-name (rocksdb/get-record (str "discord-id->elo-map/" discord-id))))

(defn discord-id->elo-map [discord-id]
  (rocksdb/get-record (str "discord-id->elo-map/" discord-id)))

(defn save-admin-id [discord-id]
  (swap! admin-ids* set/union #{discord-id})
  (rocksdb/put-record! "admin-ids" @admin-ids*))

(defn remove-admin-id [discord-id]
  (swap! admin-ids* set/difference #{discord-id})
  (rocksdb/put-record! "admin-ids" @admin-ids*))
