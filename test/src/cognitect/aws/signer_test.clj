;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.signer-test
  "See http://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cognitect.aws.client :as client]
            [cognitect.aws.signers :as signers])
  (:import [java.io ByteArrayInputStream]
           [org.apache.commons.io.input BOMInputStream]))

;; These tests are not well-formed.
(def exclude-test?
  #{"post-vanilla-query-nonunreserved"})

(defn parse-request-line
  [request-line]
  (let [[request-method uri & rest] (str/split request-line #" ")
        [path query-string] (str/split uri #"\?" 2)]
    {:request-method (keyword (str/lower-case request-method))
     :uri path
     :query-string query-string}))

(defn parse-headers
  [lines]
  (loop [[line & rest] lines
         headers {}
         current-header-name nil]
    (if-not line
      {:headers headers}
      (let [[k v] (str/split line #":")
            header-name (str/lower-case k)]
        (if v
          (recur rest
                 (update-in headers [header-name] #(if % (str % "," (str/trim v)) (str/trim v)))
                 header-name)
          (if-not current-header-name
            (throw (ex-info "Cannot parse headers."
                            {:lines lines}))
            (recur rest
                   (update-in headers [current-header-name] #(str % "," (str/trim k)))
                   current-header-name)))))))

(defn parse-request
  "Parse the string and return a ring-like request object.

  I can't use a proper HTTP parser since some of the files are
  deliberately broken or not even parsed properly by their test
  suite (e.g. multiple line headers)."
  [s]
  (let [[request-line & rest] (with-open [rdr (io/reader (-> (.getBytes s)
                                                             (ByteArrayInputStream.)
                                                             (BOMInputStream.)))]
                                (into [] (line-seq rdr)))
        [headers [empty-line & rest]] (split-with (complement empty?) rest)
        body (str/join "\n" rest)]
    (merge {:body (java.nio.ByteBuffer/wrap (.getBytes ^String body "UTF-8"))}
           (parse-request-line request-line)
           (parse-headers headers))))

(def suffix-handlers
  {"req"   [:request           parse-request]
   "creq"  [:canonical-request identity]
   "sts"   [:string-to-sign    identity]
   "authz" [:authorization     identity]
   "sreq"  [:signed-request    parse-request]})

(defn suffix
  [f]
  (last (str/split (.getName f) #"\.")))

(defn sub-directories
  [dir]
  (->> dir (.listFiles) (filter #(.isDirectory %))))

(defn read-tests
  [dir]
  (map (fn [test-directory]
         (reduce #(let [[kw parser] (suffix-handlers (suffix %2))]
                    (assoc %1 kw (parser (slurp %2))))
                 {:name (.getName test-directory)}
                 (.listFiles test-directory)))
       (sub-directories dir)))

(def credentials
  {:aws/access-key-id "AKIDEXAMPLE"
   :aws/secret-access-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"})

(deftest test-aws-sign-v4
  (let [service {:metadata {:signatureVersion "v4" :endpointPrefix "service"}}]
    (doseq [{:keys [name request authorization]} (read-tests (io/file (io/resource "aws4_testsuite")))]
      (when-not (exclude-test? name)
        (testing name
          (let [signed-request (client/sign-http-request service :us-east-1 request credentials)]
            (is (= (get-in signed-request [:headers "authorization"])
                   authorization))))))))

(comment
  (run-tests)

  )
