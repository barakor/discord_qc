(ns rewig.components
  (:require
            ; [reagent.core :as r :refer [with-let]]
   [rewig.util :refer [size-map named-sides-map]]
   [rewig.theme.gruvbox :as theme]))

(defn box
  ([children]
   (cond (map? children) (box children [])
         :else (box {} children)))
  ([{:keys [element
            align content-align wrap direction display
            hidden? reverse?
            size padding margin
            click!
            attrs
            css] :as props} children]
   (let [element       (or element :div)
         attrs         (merge (or attrs {})
                              {:on-click click!})

         hidden?       (or hidden? false)
         reverse?      (or reverse? false)

         display       (name (or display :flex))
         align         (name (or align :flex-start))
         content-align (name (or content-align :flex-start))
         wrap          (name (or wrap :nowrap))
         direction     (str (name (or direction :row)) (when reverse? "-reverse"))

         css           (merge css
                              (when size (size-map size))
                              (when margin (named-sides-map "margin" margin))
                              (when padding (named-sides-map "padding" padding))

                              {:display display
                               :align-items align
                               :justify-content content-align
                               :flex-wrap wrap
                               :flex-direction direction})

         props (assoc attrs :style (merge (:style attrs) css))

         children (cond
                    (fn? (first children)) [children]
                    (keyword? (first children)) [children]
                    :else children)]

     (into [element props] children))))

(defn row
  ([children]
   (row {} children))
  ([props children]
   (let [props (assoc props :direction :row)]
     (box props children))))

(defn column
  ([children]
   (column {} children))
  ([props children]
   (let [props (assoc props :direction :column)]
     (box props children))))

(defn gap [& {:keys [size flex] :or {size 0}}]
  (box {:css {:flex-grow flex :flex-shrink 0} :size size} nil))

(defn button
  ([children]
   (button {} children))
  ([{:keys [disabled? click! attrs type css not-selected?] :as props} children]
   (let [attrs (merge attrs
                      {:disabled disabled?})
         type (or type :normal)
         css (merge {:cursor "pointer"
                     :border "none"
                     :border-radius (str theme/size-small "px")}
                    (case type
                      :normal  {:background-color theme/bg0
                                :color theme/fg0}

                      :primary {:background-color theme/primary
                                :color theme/fg0}

                      :success {:background-color theme/success
                                :color theme/fg0}

                      :danger  {:background-color theme/danger
                                :color theme/fg0})

                    (when not-selected?
                      {:opacity "0.5"})
                    (when disabled?
                      {:color theme/fg4
                       :cursor "not-allowed"
                       :opacity "0.25"})
                    css)

         props (assoc props :element :button
                      :attrs attrs
                      :css css)]
     (box props children))))

(defn label
  ([text]
   (label {} text))
  ([props text]
   [box (merge {:align-items :center
                :css {:font-size (str theme/font-size "px")
                      :color theme/text}}
               props)
    text]))

(defn input-text [{:keys [css value placeholder size change! blur! enter! disabled? type options-list]
                   :or {type "text"
                        blur! #()
                        change! nil
                        enter! #()
                        value ""
                        placeholder ""
                        disabled? false
                        css {}
                        options-list []
                        size "100%"}
                   :as props}]
  (let [field-id (gensym)
        attrs {:type (name type)
               :placeholder placeholder
               :value value
               :on-change #(change! (.. % -target -value))
               :on-blur #(blur! (.. % -target -value))
               :on-key-press (fn [e] (when (= 13 (.-charCode e)) (enter!)))
               :disabled disabled?
               :list field-id}
        css (merge css
                   (when size (size-map size)))]
    [box {} [[:input (assoc attrs :style css)]
             (into [:datalist {:id field-id}] (map #(vec [:option {:value %}]) options-list))]]))

(defn dropdown-select [{:keys [css options disabled? change! multiple? selected size]}]
  (let [options-list (doall (map #(vector :option (merge {:key % :value %})
                                                         ; (when (= selected %) {:selected true}))
                                          %) options))
        attrs {:value selected
               :on-change #(change! (.. % -target -value))
               :disabled disabled?}
        css (merge css
                   (when size (size-map size)))]
    (into [:select (assoc attrs :style css)] options-list)))
