(ns discord-qc.state
   (:require
    [clojure.edn :as edn]))


(def discord-state* (atom nil))

(def state* (atom nil))

(let [token (->> "secret.edn" (slurp) (edn/read-string) (:token))]
  (def config (-> "config.edn"
                (slurp)
                (edn/read-string)
                (assoc :token token))))

