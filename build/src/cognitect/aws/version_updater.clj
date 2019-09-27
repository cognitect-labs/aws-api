(ns cognitect.aws.version-updater
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell])
  (:import (java.util Date)))

(set! *print-namespace-maps* false)

(defn version-prefix []
  (read-string (slurp (io/file "VERSION_PREFIX"))))

(defn git-revision []
  (-> (:out (shell/sh "build/revision"))
      (str/split #"\n")
      first))

(defn version []
  (str (version-prefix) "." (git-revision)))

(defn update-file [fname* xform]
  (let [[fname ext] (str/split fname* #"\.")
        f  (io/file fname*)
        cp (java.io.File/createTempFile fname (str "." ext))]
    (io/copy f cp)
    (with-open [r (io/reader cp)
                w (io/writer f)]
      (doseq [l (line-seq r)]
        (.write w (xform l))
        (.newLine w)))))

(defn latest-releases []
  (-> (io/file "latest-releases.edn")
      slurp
      edn/read-string))

(defn update-version-in [fname latest libname]
  (let [version (get-in latest [(symbol "com.cognitect.aws" libname) :mvn/version])]
    version
    (update-file fname
                 #(if (re-find (re-pattern (str "com.cognitect.aws\\/" libname "\\s+\\{:mvn\\/version")) %)
                    (str/replace-first % (re-pattern "\\d+(.\\d+)+") version)
                    %))))

(defn update-versions-in-readme []
  (let [latest (latest-releases)]
    (doseq [svc ["api" "endpoints" "s3"]]
      (update-version-in "README.md" latest svc))))

(defn update-versions-in-deps []
  (let [latest (latest-releases)]
    (doseq [svc ["endpoints" "dynamodb" "ec2" "iam" "lambda" "s3" "ssm" "sts"]]
      (update-version-in "deps.edn" latest svc))))

(defn update-changelog [version]
  (update-file "CHANGES.md"
               #(if (re-find (re-pattern "## DEV") %)
                  (str/replace-first %
                                     (re-pattern "DEV")
                                     (str version " / " (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (Date.))))
                  %)))

(defn update-api-version-in-latest-releases [version]
  (let [f    (io/file "latest-releases.edn")
        data (edn/read-string (slurp f))]
    (binding [*print-namespace-maps* false
              *print-length*         500]
      (spit f
            (with-out-str
              (clojure.pprint/pprint
               (->> (assoc-in data ['com.cognitect.aws/api :mvn/version] version)
                    (into (sorted-map-by (-> (fn [a b]
                                               (cond (= "api" (name a)) -1
                                                     (= "api" (name b)) 1
                                                     :else              0))
                                             (.thenComparing
                                              (fn [a b]
                                                (cond (= "endpoints" (name a)) -1
                                                      (= "endpoints" (name b)) 1
                                                      :else                    (compare a b))))))))))))))

(defn -main [& argv]
  (let [args (set argv)
        v (version)]
    (when (contains? args "--update-latest-releases")
      (prn "Updating api version in latest releases")
      (update-api-version-in-latest-releases v))
    (when (contains? args "--update-changelog")
      (println "Updating changelog")
      (update-changelog v))
    (when (contains? args "--update-readme")
      (println "Updating README")
      (update-versions-in-readme))
    (when (contains? args "--update-deps")
      (println "Updating deps.edn")
      (update-versions-in-deps)))
  (System/exit 0))

(comment
  (version)

  (git-revision)

  )
