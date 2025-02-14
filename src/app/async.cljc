(ns app.async
  (:require [cljs.core.async]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn promise? [obj]
  #?(:clj false)
  #?(:cljs (= js/Promise (type obj))))

(defn all [& promises]
  #?(:clj  nil)
  #?(:cljs (.all js/Promise (apply array promises))))

(defmacro async
  [& code]
  `(js/Promise.
    (fn [resolve!# reject!#]
      (cljs.core.async/take!
       (cljs.core.async/go
         (try {:status :ok :val (do ~@code)}
              (catch :default error# {:status :error :val error#})))

          ;; take! callback
       (fn [{status# :status val# :val}]
         (case status#
           :ok    (resolve!# val#)
           :error (reject!# val#)))))))

(defmacro await!
  [expr]
  `(cljs.core.async.interop/<p!
    (let [p# ~expr]
      (if (promise? p#)
        p#
        (js/Promise.resolve p#)))))

(defmacro let-await
  [bindings & body]
  (let [keep-when-index (fn [f coll] (keep-indexed #(when (f %1) %2) coll))
        catch-form?     (fn [form] (and (seq? form) (= 'catch (first form))))

        catch-form  (first (filter catch-form? body))
        body        (remove (partial = catch-form) body)

        bindings  (interleave
                   (->> bindings
                        (keep-when-index even?))

                   (->> bindings
                        (keep-when-index odd?)
                        (map (fn [code] `(await! ~code)))))]

    (if-not (empty? catch-form)
      `(.catch
        (async (let [~@bindings] ~@body))
        (fn [err#]
          (try
            (throw err#)
            ~catch-form)))
      `(async
        (let [~@bindings] ~@body)))))
