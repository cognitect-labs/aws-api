(ns bb-test-runner
  (:require [clojure.test :as t]
            [clojure.edn :as edn]))

; Load dev dependencies from deps.edn
(require '[babashka.deps :as deps])
(deps/add-deps (edn/read-string (slurp "deps.edn"))
               {:aliases [:dev]})

; NOTE: some tests won't run in babashka:
; cognitect.aws.http.default-test - all reify instances start with `babashka.impl.reify`, test won't pass
; cognitect.aws.signers-test - requires loading AWS SDK, which is not supported (no Java libs)
; cognitect.client.test-double-test - test double not supported in babashka
(def test-namespaces
  ['cognitect.aws.api-test
   'cognitect.aws.client.shared-test
   'cognitect.aws.config-test
   'cognitect.aws.credentials-test
   'cognitect.aws.ec2-metadata-utils-test
   'cognitect.aws.endpoint-test
   'cognitect.aws.http-test
   'cognitect.aws.http.java-test
   'cognitect.aws.integration.error-codes-test
   'cognitect.aws.integration.s3-test
   'cognitect.aws.interceptors-test
   'cognitect.aws.protocols-test
   'cognitect.aws.protocols.rest-test
   'cognitect.aws.region-test
   'cognitect.aws.retry-test
   'cognitect.aws.shape-test
   'cognitect.aws.util-test
   'cognitect.client.impl-test])

(defn run-tests [& _args]
  (apply require test-namespaces)

  (let [{:keys [fail error]}
        (apply t/run-tests test-namespaces)]
    (when (or (pos? fail)
              (pos? error))
      (System/exit 1))))
