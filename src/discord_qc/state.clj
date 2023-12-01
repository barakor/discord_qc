(ns discord-qc.state
   (:require
    [clojure.edn :as edn]))

(def state* (atom nil))

(def config (edn/read-string (slurp "config.edn")))

