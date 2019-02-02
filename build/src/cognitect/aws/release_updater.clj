(ns cognitect.aws.release-updater
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell])
  (:import (java.util Date)))

(defn version-prefix []
  (read-string (slurp (io/file "VERSION_PREFIX"))))

(defn git-revision []
  (-> (:out (shell/sh "build/revision"))
      (str/split #"\n")
      first))

(defn version []
  (str (version-prefix) "." (git-revision)))

(defn update-version-in-line [version line]
  (if (re-find (re-pattern (str "com.cognitect.aws\\/api")) line)
    (str/replace-first line (re-pattern "\\d+.\\d+.\\d+") version)
    line))

(defn update-file [fname ext xform]
  (let [f  (io/file (str fname "." ext))
        cp (java.io.File/createTempFile fname (str "." ext))]
    (io/copy f cp)
    (with-open [r (io/reader cp)
                w (io/writer f)]
      (doseq [l (line-seq r)]
        (.write w (xform l))
        (.newLine w)))))

(defn update-readme [version]
  (update-file "README"
               "md"
               #(if (re-find (re-pattern (str "com.cognitect.aws\\/api")) %)
                  (str/replace-first % (re-pattern "\\d+.\\d+.\\d+") version)
                  %)))

(defn update-changelog [version]
  (update-file "CHANGES"
               "md"
               #(if (re-find (re-pattern "## DEV") %)
                  (str/replace-first %
                                     (re-pattern "DEV")
                                     (str version " / " (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (Date.))))
                  %)))

(defn update-latest-releases [version]
  (let [f    (io/file "latest-releases.edn")
        data (edn/read-string (slurp f))]
    (binding [*print-namespace-maps* false
              *print-length*         500]
      (spit f
            (with-out-str
              (clojure.pprint/pprint
               (-> data
                   (assoc-in [:api 'com.cognitect.aws/api] version)
                   (update :services #(into (sorted-map) %)))))))))

(defn -main []
  (let [v (version)]
    (update-readme v)
    (update-changelog v)
    (update-latest-releases v))
  (System/exit 0))
