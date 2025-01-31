(ns discord-qc.state
   (:require
    [clojure.edn :as edn]))


(defonce discord-state* (atom nil))

(defonce state* (atom nil))

(let [secret (->> "secret.edn" (slurp) (edn/read-string))
      token (:token secret)
      gh-token (:gh-pat secret)]
      
  (def config (-> "config.edn"
                (slurp)
                (edn/read-string)
                (assoc :token token)
                (assoc :gh-pat gh-token))))
