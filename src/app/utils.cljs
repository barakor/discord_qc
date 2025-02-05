(ns app.utils
  (:require
   [reagent.core :as r :refer [with-let atom]]
   [reagent.dom :as rdom]
   [rewig.components :refer [box row column gap button label input-text dropdown-select]]
   [app.async :refer-macros [let-await await!]]
   [app.http :refer [http-get-json http-post-json]]
   [rewig.theme.gruvbox :as theme]

   [clojure.set :as set]
   [clojure.string :as string :refer [lower-case]]
   [goog.object :as gobj]))

(defn drop-nth [v n]
  (vec (concat (subvec v 0 n) (subvec v (inc n)))))

(defn symmetric-difference [set1 set2]
  (set/union (set/difference set1 set2) (set/difference set2 set1)))

(defn change-idx [v old-idx new-idx]
  (let [item (nth v old-idx)
        [before after] (split-at new-idx (drop-nth v old-idx))]
    (vec (concat before [item] after))))

(defn remove-dups [v]
  (reduce (fn [acc x] (if (some #{x} acc) acc (conj acc x))) [] v))

(defn drop-or-add-by-id [col item id!]
  (let [new-ids (symmetric-difference (set (map id! col)) #{(id! item)})
        options (assoc (apply merge (map #(hash-map (id! %) %) col)) (id! item) item)]
    (set (map #(get options %) new-ids))))
