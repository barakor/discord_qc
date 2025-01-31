(ns discord-qc.storage.rocksdb
  (:require
   [byte-streams :as bs]
   [taoensso.nippy :refer [freeze thaw]]
   [clojure.edn :as edn]
   [clojure.string :as str]

   [taoensso.timbre :as timbre :refer [log]])
  (:import [org.rocksdb RocksDB]
           [org.rocksdb Options]))

(defonce ^:private db* (atom nil))

(defn- rocksdb-serialize-value [value]
  (freeze value))

(defn- rocksdb-deserialize-value [value]
  (try
    (thaw value)
    (catch Exception _ nil)))

(defn put-record! [k v]
  (when-let [db @db*]
    (log :info  "[storage.rocksdb]: put into key: " k)
    (.put db (rocksdb-serialize-value k) (rocksdb-serialize-value v))))

(defn get-record [k]
  (when-let [db @db*]
    (when-let [record (some->> k
                               (rocksdb-serialize-value)
                               (.get db)
                               (rocksdb-deserialize-value))]
      record)))

(defn get-db-map []
  (when-let [db @db*]
    (let [iterator (.newIterator db)]
      (.seekToFirst iterator)
      (loop [data {}]
        (if (.isValid iterator)
          (do
            (let [data (assoc data (rocksdb-deserialize-value (.key iterator)) (rocksdb-deserialize-value (.value iterator)))]
              (.next iterator)
              (recur data)))
          data)))))

(defn stop! []
  (when-let [db @db*]
    (reset! db* nil)
    (.close db)))

(defn start! [{db-path :db-path}]
  (when @db*
    (stop!))

  (let [opts (-> (Options.)
                 (.setCreateIfMissing true)
                 (.setErrorIfExists false)
                 (.setKeepLogFileNum 1)
                 ; (.setInfoLogLevel RocksDB/InfoLogLevel/WARN)
                 ; (.setDisableWal true)  ;; Disable WAL if not needed
                 (.setDeleteObsoleteFilesPeriodMicros (* 60 1000000)))]

    (reset! db* (RocksDB/open opts db-path))
    (log :info "[storage.rocksdb]: started " db-path)))

(start! {:db-path "/tmp/rocksdb"})
