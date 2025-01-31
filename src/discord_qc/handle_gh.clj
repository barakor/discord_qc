(ns discord-qc.handle-gh
  (:require [discord-qc.storage.rocksdb :as rocksdb]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.java.io :refer [writer]]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.client :as http]
            [discord-qc.state :refer [config]]
            [taoensso.timbre :as timbre :refer [log]]
            [cheshire.core :as json])

  (:import java.util.Base64))

(def repo "barakor/discord_qc")
(def branch "db-data")
(def file-path "db-data.edn")
(def commit-message (str "Update " file-path " contents"))

(defn get-file-sha [repo branch file-path]
  (let [url (str "https://api.github.com/repos/" repo "/contents/" file-path "?ref=" branch)
        response @(http/get url {:headers {"Authorization" (str "token " (:gh-pat config))
                                           "Accept" "application/vnd.github.v3+json"}})]
    (if (= 200 (:status response))
      (-> response :body json/parse-string (get "sha"))
      (throw (ex-info "Failed to get file SHA" {:response response})))))

(defn base-64-encoder [to-encode]
  (.encodeToString (Base64/getEncoder) (.getBytes to-encode)))

(defn update-file [repo branch file-path content]
  (let [sha (get-file-sha repo branch file-path)
        url (str "https://api.github.com/repos/" repo "/contents/" file-path)
        body (json/generate-string {:message commit-message
                                    :content (base-64-encoder content)
                                    :sha sha
                                    :branch branch})
        res   @(http/put url {:headers {"Authorization" (str "token " (:gh-pat config))
                                        "Accept" "application/vnd.github.v3+json"
                                        "Content-Type" "application/json"}
                              :body body})]
    (log :debug res)
    res))

(defn update-db-file [content] (update-file repo branch file-path content))
