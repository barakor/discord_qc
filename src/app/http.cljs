(ns app.http
  (:require
   [lambdaisland.fetch :as fetch]
   [app.async :refer-macros [let-await]]))

(defn http-get-json [address]
  (let-await [resp (fetch/get address)
              data (-> resp
                       :body
                       (#(js->clj %)))]

             data))

(defn http-get [address]
  (let-await [resp (fetch/get address)
              data (-> resp
                       :body)]

             data))

(defn http-post-json [address payload]
  (let [options {:body        (js/JSON.stringify (clj->js payload))
                 :headers     {"Content-Type" "application/json"}}]
    (let-await [resp (fetch/post address options)
                data (-> resp
                         :body
                         (#(js->clj %)))]

               data)))
