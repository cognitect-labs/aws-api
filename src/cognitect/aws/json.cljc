(ns ^:skip-wiki cognitect.aws.json
  "Impl, don't call directly."
  (:require #?(:bb  [cheshire.core]
               :clj [clojure.data.json])))

(defn read-str [string & {:as options}]
  #?(:bb  (cheshire.core/parse-string string (:key-fn options))
     :clj (clojure.data.json/read-str string options)))

(defn write-str [x]
  #?(:bb  (cheshire.core/generate-string x)
     :clj (clojure.data.json/write-str x)))
