(ns rewig.util)

(defn map-map [key-fn val-fn hashmap]
  (->> hashmap
       (map #(hash-map (key-fn (first %)) (val-fn (second %))))
       (apply merge)))

(defn map-keys [fn m]
  (map-map fn identity m))

(defn map-vals [fn m]
  (map-map identity fn m))

(defn v->px [v]
  (if (number? v) (str v "px") v))

(defn size-map [size]
  (cond
    (map? size) (map-vals v->px size)
    (vector? size) {:width (v->px (first size)) :height (v->px (second size))}
    :single-value  {:width (v->px size) :height (v->px size)}))

(defn sides-map [v]
  (cond
    (map? v) (map-vals v->px v)
    (and (vector? v)
         (= 4 (count v)) {:top (v->px (get v 0))
                          :bottom (v->px (get v 1))
                          :left (v->px (get v 2))
                          :right (v->px (get v 3))})
    (and (vector? v)
         (= 2 (count v)) {:top (v->px (first v))
                          :bottom (v->px (first v))
                          :left (v->px (second v))
                          :right (v->px (second v))})

    :single-value  {:top (v->px v)
                    :bottom (v->px v)
                    :left (v->px v)
                    :right (v->px v)}))

(defn named-sides-map [sides-name m]
  (map-keys #(keyword (str sides-name "-" (name %))) (sides-map m)))
