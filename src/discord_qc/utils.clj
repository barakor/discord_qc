(ns discord-qc.utils
  (:require [clojure.string :as string]
            [com.rpl.specter :as s]))


(defn dissoc-keys-starting-with [hmap key-start]
  (let [keys-to-remove (filter #(string/starts-with? (name %) key-start) (keys hmap))]
    (apply (partial dissoc hmap) keys-to-remove)))


(defn get-keys-starting-with [hmap key-start]
  (let [keys-to-keep (->> hmap 
                       (keys)
                       (filter #(string/starts-with? (name %) key-start))
                       (set))]
    (select-keys hmap keys-to-keep)))

