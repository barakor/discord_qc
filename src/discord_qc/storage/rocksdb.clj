(ns discord-qc.storage.rocksdb 
  (:require
       [byte-streams :as bs]
       [taoensso.nippy :refer [freeze thaw]]
       [clojure.edn :as edn]

       [taoensso.timbre :as timbre :refer [log]])
  (:import [org.rocksdb RocksDB]
           [org.rocksdb Options]))


(defonce ^:private db* (atom nil))


(defn- rocksdb-serialize-value-bs [value]
  (bs/to-byte-array (pr-str value)))


(defn- rocksdb-deserialize-value-bs [value]
   (when-let [val (bs/to-string value)]
     (try 
       (edn/read-string val)
       (catch Exception _ val))))


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

    (if-let [record (some->> k
                      (rocksdb-serialize-value)
                      (.get db)
                      (rocksdb-deserialize-value))]
      record
      (some->> k
        (rocksdb-serialize-value-bs)
        (.get db)
        (rocksdb-deserialize-value-bs)))))


(defn stop! []
  (when-let [db @db*]
    (reset! db* nil)
    (.close db)))


(defn start! [{db-path :db-path}]
  (when @db*
    (stop!))

  (let [opts (-> (Options.)
               (.setCreateIfMissing true)
               (.setErrorIfExists   false))]

    (reset! db* (RocksDB/open opts db-path))
    (log :info "[storage.rocksdb]: started " db-path)))


(start! {:db-path "/tmp/rocksdb"})
