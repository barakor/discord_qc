(ns discord-qc.handle-db
  (:require [clojure.edn :as edn]
            [clojure.java.io :refer [writer]]  
            [clojure.pprint :refer [pprint]]
            [com.rpl.specter :as s]
            [discljord.messaging :refer [get-guild!]]
            [datomic.client.api :as d]
            
            [discord-qc.elos :refer [get-quake-elo]]))


(def client (d/client {:server-type :datomic-local
                       :system "dev"
                       :storage-dir "/tmp/dataomic"}))


(def discord->quake-name-schema [{:db/ident :discord/user-id
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "The discord id of the user"}
                                 
                                 {:db/ident :discord/quake-name
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db/doc "The quake name of the user"}])


(def elo-schema [{:db/ident :elo/quake-name
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one
                  :db/doc "The quake name of the player"}
                 
                 {:db/ident :elo/killing
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/ranked-duel
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/tdm
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/sacrifice-tournament
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/instagib
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/slipgate
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/tdm-2v2
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/objective
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/sacrifice
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/duel
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/ctf
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}

                 {:db/ident :elo/ffa
                  :db/valueType :db.type/float
                  :db/cardinality :db.cardinality/one
                  :db/doc "The elo of the mode for the player"}])


(defn init-conn [client db-name & {:keys [schema]}]
  (try
    (d/connect client {:db-name db-name})
    (catch Exception e (let [_ (d/create-database client {:db-name db-name})
                             conn (d/connect client {:db-name db-name})
                             _ (when schema (d/transact conn {:tx-data schema}))]
                         conn))))


(defn discord-quakename-conn [client]
  (init-conn client "discord-quakename" discord->quake-name-schema))


(defn quake-scores-conn [client]
  (init-conn client "quake-scores" elo-schema))


(defn prepare-elo-map [elo-map]
  (s/transform [s/MAP-KEYS] #(keyword (str "elo/" (name %))) elo-map))


(defn prepare-discord-quake-map [discord-quake-map]
  (s/transform [s/MAP-KEYS] #(keyword (str "discord/" (name %))) discord-quake-map))


(defn elo-map->db [elo-map]
  (let [m (prepare-elo-map elo-map)
        conn (quake-scores-conn client)] 
    (d/transact conn {:tx-data [m]})))

(elo-map->db (get-quake-elo "bamb1"))

(defn db->elo-map [quake-name]
  (let [conn (quake-scores-conn client)
        db (d/db conn)
        query '[:find (pull ?entry [*])
                :in $ ?quake-name
                :where [?entry :elo/quake-name ?quake-name]]
        entries (d/q query db quake-name)]
    (first (last entries))))


(defn discord-quake-player->db [discord-quake-map]
  (let [m (prepare-discord-quake-map discord-quake-map)
        conn (discord-quakename-conn client)] 
    (d/transact conn {:tx-data [m]})))

discord->quake-name-schema
(discord-quake-player->db {:user-id "88533822521507840" :quake-name "lezyes"})

(d/transact
  (discord-quakename-conn client)
  {:tx-data [(prepare-discord-quake-map {:user-id "88533822521507840" :quake-name "lezyes"})]})


(let [db-name "discord-quakename"
      schema discord->quake-name-schema]
  (try
      (d/connect client {:db-name db-name})
      (catch Exception e (when-let [db-created (d/create-database client {:db-name db-name})]
                           (let [conn (d/connect client {:db-name db-name})
                                 _ (when schema (d/transact conn {:tx-data schema}))]
                             conn)))))
(d/create-database client {:db-name "discord-quakename"})
(d/connect client {:db-name "discord-quakename"})

(d/q '[:find (pull ?e [*]) :where [?e :elo/quake-name ?]] db)
(def db (d/db conn))

(d/q '[:find (pull ?e [*])
       :in $ ?quake-name
       :where [?e :elo/quake-name ?quake-name]]
      db "rapha")

(d/q '[:find (pull ?e [*])
       :where [?e :elo/quake-name "lezyes"]]
   db)

(def entries (into [] (map #(prepare-elo-map (assoc (get-quake-elo %) :quake-name %)) ["lezyes" "xtortion" "rapha" "bamb1" "iikxii"])))

entries

(d/transact conn {:tx-data entries})



(def db (d/db conn))

(d/q '[:find ?ffa
       :where [?quake-name :elo/quake-name "rapha"]
              [?quake-name :elo/ffa ?ffa]]
  db)

(def r (d/q '[:find (pull ?e [*])
              :where [?e :elo/quake-name "rapha"]]
        db))

(last r)

(def db (atom nil))

(def db-file-path "db.edn")

(def db-comment "; {\n;   server-id {\n;     :name \"server name\"\n;     :roles-rules {\n;       role-id {\n;         :type :named-activity/:else\n;         :activity-names [\"a\" \"b\" \"c\"] \n;         :role-name \"role name\"\n;         :comment \"comment\"\n;       }\n;     }\n;   }\n;  }\n; \n")

(defn- write-db! [db db-file-path]
  (with-open [w (writer db-file-path)]
   (binding [*out* w
             *print-length* false]
     (println db-comment)
     (pprint db))))

(defn save-db! []
  (write-db! @db db-file-path))


(defn- read-db! [db-file-path] (edn/read-string (slurp db-file-path)))


(defn load-db! []
  (reset! db (read-db! db-file-path)))


(defn- name-stuff [rest-connection [guild-id roles-map]]
  (let [guild-data @(get-guild! rest-connection guild-id)]
    (if-let [guild-name (:name guild-data)]
      (let [roles-names (apply merge (map #(hash-map  (:id %)  (:name %)) (:roles guild-data)))
            name-roles  (fn [roles-names [role-id role-map]] (list role-id (assoc role-map :name (get roles-names role-id))))
            named-roles-map  (->> roles-map
                               (#(assoc % :name guild-name))
                               (s/transform [:roles-rules s/ALL] (partial name-roles roles-names)))]
        (list guild-id named-roles-map))
      (list guild-id roles-map))))
        
(defn update-db-with-names [rest-connection]
  (reset! db (s/transform [s/ALL ] (partial name-stuff rest-connection) @db)))