(ns app.components
  (:require
   [reagent.core :as r :refer [with-let atom]]
   [com.rpl.specter :as s]
   [rewig.components :refer [box row column gap button label input-text dropdown-select]]
   [app.utils :refer [drop-nth symmetric-difference change-idx]]
   [rewig.theme.gruvbox :as theme]))

(defn input-field [title value change! & {:keys [enter! options-list] :or {enter! #()}}]
  [row
   {}
   (let [field-id (gensym title)]
     [[label {} title]
      [gap :size 8]
      [input-text {:placeholder title
                   :value value
                   :options-list options-list
                   :type "text"
                   :enter! enter!
                   :size [100 16]
                   :change! #(change! %)}]])])

(defn on-off-component [available-options selected-options click! {:keys [print!] :or {print! #(get % "name")}}]
  [row {}
   (for [option available-options]
     [box {}
      [[button
        {:type :primary
         :not-selected? (not (contains? selected-options option))
         :click! #(click! option)}
        (print! option)]
       [gap :size theme/size-medium]]])])

(defn on-off-renameable-component [available-options
                                   selected-options
                                   click!
                                   rename!
                                   id!
                                   {:keys [print! input-text-print!]
                                    :or {print! #(get % "name")}
                                    input-text-print! #(get % "name")}]
  [row {}
   (for [option available-options
         :let [option-id (id! option)
               selected? (contains? (set (map id! selected-options)) option-id)
               selected-option (s/select-first [s/ALL #(= option-id (id! %))] selected-options)]]
     [box {}
      [[column {}
        [[button
          {:type :primary
           :not-selected? (not selected?)
           :click! #(click! option)}
          (print! option)]
         [gap :size theme/size-xsmall]
         (when selected?
           [input-field "" (input-text-print! selected-option)
            #(rename! option-id %)])]]
       [gap :size theme/size-medium]]])])

(defn radio-component [available-options selected-option click! {:keys [print!] :or {print! #(get % "name")}}]
  [row {}
   (for [option available-options]
     [box {}
      [[button
        {:type :primary
         :not-selected? (not (= selected-option option))
         :click! #(click! option)}
        (print! option)]
       [gap :size theme/size-medium]]])])

(defn chip-component [chips remove-selection! {:keys [print!] :or {print! #(get % "name")}}]
  [row {}
   (for [selection-idx (range (count chips))
         :let [chip (get chips selection-idx)]]
     [row {}
      [[row
        {:type :primary
         :css {:font-size (str theme/font-size "px")
               :color theme/background
               :background-color theme/highlight
               :border "none"
               :border-radius "16px"
               :align-items :center}}
        [:<>
         [gap :size theme/size-small]
         (label {} (print! chip))
         [gap :size theme/size-xxsmall]
         [button {:click! #(remove-selection! selection-idx %)
                  :css {:align-self :center
                        :font-size (str 8 "px")
                        :color theme/background
                        :background-color theme/danger
                        :border-radius "16px"}}
          "✖️"]
         [gap :size theme/size-xsmall]]]
       [gap :size theme/size-small]]])])

(defn multi-dropdown-selection [available-options
                                selected-options
                                change-selection!
                                remove-selection!
                                add-selection!
                                sort-selection!
                                change-selection-pos!
                                {:keys [print!] :or {print! #(get % "name")}}]
  [column {}
   [[column {}
     (for [selection-idx (range (count selected-options))
           :let [selection (get selected-options selection-idx)]]
       [row {}
        [[dropdown-select {:options  (map print! available-options)
                           :selected (print! selection)
                           :change!  #(change-selection! selection-idx %)}]
         [gap :size theme/size-medium]
         [button
          {:type :danger
           :click! #(remove-selection! selection-idx %)}
          "-"]
         [gap :size theme/size-medium]
         (when (> selection-idx 0)
           [box {}
            [[button {:css {:background-color theme/primary}
                      :type :danger
                      :click! #(change-selection-pos! selection-idx (dec selection-idx))}
              "↑"]
             [gap :size theme/size-medium]]])
         (when (< selection-idx (- (count selected-options) 1))
           [box {}
            [[button {:css {:background-color theme/success}
                      :type :danger
                      :click! #(change-selection-pos! selection-idx (inc selection-idx))}
              "↓"]
             [gap :size theme/size-medium]]])]])]
    (when (not-empty selected-options) [gap :size theme/size-medium])
    [row {}
     [[button {:type :danger
               :click! add-selection!}
       "+"]
      [gap :size theme/size-medium]
      [button {:type :danger
               :click! sort-selection!}
       "Sort"]]]]])

(defn rotating-loading [& {:keys [color size]
                           :or {color theme/light-blue size theme/size-medium}}]
  [box
   {:attrs {:class :loader}
    :css   {:border (str (quot size 10) "px solid #ffffff00")
            :border-top (str (quot size 10) "px solid" color)
            :border-bottom (str (quot size 10) "px solid" color)
            :border-radius "100%"
            :width size
            :height size
            :animation "spin 2s linear infinite"}}])
