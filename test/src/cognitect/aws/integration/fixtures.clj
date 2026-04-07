(ns cognitect.aws.integration.fixtures
  (:require [clojure.string :as s]))

(defn ensure-test-profile
  "Ensure AWS_PROFILE env var is aws-api-test

  To use it, add this at beginning of your test namespace:

  (use-fixtures :once aux.integration/ensure-test-profile)"
  [f]
  (if (= "aws-api-test" (System/getenv "AWS_PROFILE"))
    (if (s/blank? (System/getenv "AWS_ACCESS_KEY_ID"))
      (println "AWS_* secret env vars not available, so not running integration tests.")
      (f))
    (println "AWS_PROFILE is not configured, so not running integration tests.")))
