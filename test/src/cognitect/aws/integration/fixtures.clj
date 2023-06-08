(ns cognitect.aws.integration.fixtures)

(defn ensure-test-profile
  "Ensure AWS_PROFILE env var is aws-api-test

  To use it, add this at beginning of your test namespace:

  (use-fixtures :once aux.integration/ensure-test-profile)"
  [f]
  (if (= "aws-api-test" (System/getenv "AWS_PROFILE"))
    (f)
    (println "AWS_PROFILE is not configured, so not running integration tests.")))
