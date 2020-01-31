(ns clojure.data.json)

;; TODO implemenent necessary API

(defn read-str [s & {:keys [key-fn]}]
  ;; FIXME key-fn is not exactly keywordize-keys?
  (js->clj (.parse js/JSON) (if key-fn true false)))
  
(defn write-str [data]
  (.stringify js/JSON (clj->js data)))