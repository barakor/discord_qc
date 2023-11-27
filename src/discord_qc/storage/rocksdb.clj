(ns discord-qc.storage.rocksdb 
  (:require
       [byte-streams :as bs]
       [clojure.edn :as edn])
  (:import [org.rocksdb RocksDB]
           [org.rocksdb Options]))


(defonce ^:private db (atom nil))


(defonce status* (atom {:status :off
                        :config {:db-path "/tmp/rocksdb"}}))


(defn is-alive? []
  (= :on (:status @status*)))


(defn- init-rocksdb! []
  (let [db-path (get-in @status* [:config :db-path])
        opts (-> (Options.)
               (.setCreateIfMissing true)
               (.setErrorIfExists false))
        db   (RocksDB/open opts db-path)]
    db))


(defn start! [ & {:keys [config] :or {config {:db-path "/tmp/rocksdb"}}}] ;;this dir is set as a docker volume in compose for persistency
                     
  (try
    (when (not-empty config)
      (swap! status* assoc :config config))
    (when (not (is-alive?)) ;; otherwise will fail if already up
      (reset! db (init-rocksdb!)))
    (println "[storage.rocksdb]: started")
    (swap! status* assoc :status :on)
    :on
    (catch Exception e (do 
                         (println "[storage.rocksdb]" e)
                         (swap! status* assoc :status :error)
                         :error))))


(defn- rocksdb-serialize-value [value]
  (bs/to-byte-array (pr-str value)))


(defn- rocksdb-deserialize-value [value]
   (if-let [val (bs/to-string value)]
     (try 
       (edn/read-string val)
       (catch Exception e val))
     nil))


(defn put-record! [k v]  
  (when (is-alive?)
    (println "[storage.rocksdb]: put into key: " k)
    (.put @db (rocksdb-serialize-value k) (rocksdb-serialize-value v))))


(defn get-record [k]
  (when (is-alive?)
    (if-let [byte-array-val (.get @db (rocksdb-serialize-value k))]
      (rocksdb-deserialize-value byte-array-val) 
      nil)))

(start!)
