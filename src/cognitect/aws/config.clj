;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.config
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

;;; predicates

(defn comment? [s]
  (str/starts-with? s "#"))

(defn start-profile? [s]
  (str/starts-with? s "["))

(defn start-nested? [s]
  (re-find #"^[\w-_\.]+\s*=$" s))

(defn add-profile-kv? [s]
  (re-find #"^[\w-_\.]+\s*=.*\w+" s))

(defn add-nested-kv? [s]
  (re-find #"^\s+[\w-_\.]+\s*=.*\w+" s))

;;; helpers

(defn split-lines [s]
  (transduce (comp (map str/trimr)
                   (remove str/blank?)
                   (remove comment?))
             conj
             []
             (str/split-lines s)))

(defn split-kv [s]
  (->> (str/split s #"=" 2)
       (map str/trim)))

;;; actions

(defn set-profile-path [m line]
  (assoc m :path [:profiles (second (re-find #"\[(?:profile)?\s*(.+)\]" line))]))

(defn ensure-profile-path [m]
  (update m :path (comp vec (partial take 2))))

(defn set-nested-path [m line]
  (update m :path #(conj (vec (take 2 %))
                         (-> line (str/replace #"=" "") (str/trim)))))

(defn add-profile-kv [m line]
  (let [[k v] (split-kv line)]
    (update-in m (take 2 (:path m)) assoc k v)))

(defn add-nested-kv [m line]
  (let [[k v] (split-kv line)]
    (apply update-in m (:path m) assoc (split-kv line))))

;;; main

(defn parse
  "Return the profiles in the configuration file."
  [file]
  (->> file
       slurp
       split-lines
       (reduce (fn [m ^String line]
                 (cond (start-profile? line)
                       (set-profile-path m line)

                       (start-nested? line)
                       (set-nested-path m line)

                       (add-profile-kv? line)
                       (-> m
                           (add-profile-kv line)
                           (ensure-profile-path))

                       (add-nested-kv? line)
                       (add-nested-kv m line)

                       :else
                       (throw (ex-info "Invalid format in config" {:file file}))))
               {:profiles {}})
       :profiles))
