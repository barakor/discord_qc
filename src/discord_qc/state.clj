(ns discord-qc.state
   (:require
    [clojure.edn :as edn]))


(defonce discord-state* (atom nil))

(defonce state* (atom nil))

(let [token (->> "secret.edn" (slurp) (edn/read-string) (:token))]
  (def config (-> "config.edn"
                (slurp)
                (edn/read-string)
                (assoc :token token))))

