(ns integration.aux
  (:require [clojure.test :refer :all]))

(defn ensure-test-profile
  [f]
  (if (= "aws-api-test" (System/getenv "AWS_PROFILE"))
    (f)
    (println "AWS_PROFILE is not configured, so not running integration tests. See README.")))
