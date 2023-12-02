(ns discord-qc.state
   (:require
    [clojure.edn :as edn]))


(def discord-state* (atom nil))

(def state* (atom nil))

(def config (edn/read-string (slurp "config.edn")))

