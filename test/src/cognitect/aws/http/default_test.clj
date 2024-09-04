(ns cognitect.aws.http.default-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.aws.http.default :as default-http-client]
            [cognitect.aws.test.utils :as utils]))

(deftest http-client-defaults-parity-with-cognitect-client
  (let [client (default-http-client/create)
        client-class-name (.getName (class client))
        java-version (utils/major-java-version)
        expected (if (< java-version 11)
                   "cognitect.aws.http.cognitect$create"
                   "cognitect.aws.http.java$create")]
    ; NOTE: this is matched via starts-with?, because the returned client
    ;       is created via reify, and the class name is not fully predictable
    ;       (e.g. cognitect.aws.http.java$create$reify__11484)
    (is (str/starts-with? client-class-name expected))))
