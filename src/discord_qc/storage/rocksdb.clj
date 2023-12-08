(ns discord-qc.storage.rocksdb 
  (:require
       [byte-streams :as bs]
       [clojure.edn :as edn])
  (:import [org.rocksdb RocksDB]
           [org.rocksdb Options]))


(defonce ^:private db* (atom nil))


(defn- rocksdb-serialize-value [value]
  (bs/to-byte-array (pr-str value)))


(defn- rocksdb-deserialize-value [value]
   (when-let [val (bs/to-string value)]
     (try 
       (edn/read-string val)
       (catch Exception _ val))))


(defn put-record! [k v]
  (when-let [db @db*]
    (println "[storage.rocksdb]: put into key: " k)
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
    (println "[storage.rocksdb]: started " db-path)))


(start! {:db-path "/tmp/rocksdb"})
