(ns cognitect.aws.util-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cognitect.aws.util :as util]))

(deftest test-input-stream->byte-array
  (is (= "hi" (slurp (util/input-stream->byte-array (io/input-stream (.getBytes "hi"))))))
  (testing "resets input-stream so it can be read again"
    (let [stream (io/input-stream (.getBytes "hi"))]
      (is (= (seq (.getBytes "hi")) (seq (util/input-stream->byte-array stream))))
      (is (= "hi" (slurp stream))))))

(deftest test-sha-256
  (testing "returns sha for empty string if given nil"
    (is (= (seq (util/sha-256 nil))
           (seq (util/sha-256 ""))
           (seq (util/sha-256 (.getBytes ""))))))
  (testing "accepts string, byte array, or input stream"
    (is (= (seq (util/sha-256 "hi"))
           (seq (util/sha-256 (.getBytes "hi")))
           (seq (util/sha-256 (io/input-stream (.getBytes "hi"))))))))
