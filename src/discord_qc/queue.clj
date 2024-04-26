(ns discord-qc.queue
  (:require [clojure.math.combinatorics :refer [combinations]]
            [com.rpl.specter :as s]))



(def queues (atom {})) ;; {"serverid" {game-mode [player1 player2]}}

(defn add-to-queue [quake-name guild-id game-mode]
    (swap! queues (fn [queues] (s/transform [guild-id game-mode] #(conj % quake-name) queues))))


(add-to-queue "Yora" "123" "Sac")

@queues