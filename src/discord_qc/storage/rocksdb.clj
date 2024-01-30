(ns discord-qc.storage.rocksdb 
  (:require
       [taoensso.nippy :refer [freeze thaw]]
       [clojure.edn :as edn]

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
    (some->> k
      (rocksdb-serialize-value)
      (.get db)
      (rocksdb-deserialize-value))))


(defn stop! []
  (when-let [db @db*]
    (reset! db* nil)
    (.stop db)))


(defn start! [{db-path :db-path}]
  (when @db*
    (stop!))

  (let [opts (-> (Options.)
               (.setCreateIfMissing true)
               (.setErrorIfExists   false))]

    (reset! db* (RocksDB/open opts db-path))
    (log :info "[storage.rocksdb]: started " db-path)))


(start! {:db-path "/tmp/rocksdb"})
