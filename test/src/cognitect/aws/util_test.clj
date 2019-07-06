(ns cognitect.aws.util-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cognitect.aws.util :as util])
  (:import [java.nio ByteBuffer]
           [java.util Random]
           [java.util Arrays]))

(deftest test-input-stream->byte-array
  (is (= "hi" (slurp (util/input-stream->byte-array (io/input-stream (.getBytes "hi"))))))
  (testing "works with a 1mb array"
    (let [input (byte-array (int (Math/pow 2 20)))
          rng (Random.)
          _ (.nextBytes rng input)
          output (util/input-stream->byte-array (io/input-stream input))]
      (is (Arrays/equals ^bytes input ^bytes output)))))

(deftest test-sha-256
  (testing "returns sha for empty string if given nil"
    (is (= (seq (util/sha-256 nil))
           (seq (util/sha-256 ""))
           (seq (util/sha-256 (.getBytes ""))))))
  (testing "accepts string, byte array, or ByteBuffer"
    (is (= (seq (util/sha-256 "hi"))
           (seq (util/sha-256 (.getBytes "hi")))
           (seq (util/sha-256 (ByteBuffer/wrap (.getBytes "hi")))))))
  (testing "does not consume a ByteBuffer"
    (let [bb (ByteBuffer/wrap (.getBytes "hi"))]
      (util/sha-256 bb)
      (is (= "hi" (util/bbuf->str bb))))))

(deftest test-xml-read
  (testing "removes whitespace-only nodes, preserving whitespace in single text nodes"
    (let [parsed (util/xml-read "<outer>
                                  outer-value
                                  <inner>
                                     inner-value
                                  </inner>
                                </outer>")]
      (is (= 2 (count (-> parsed :content))))
      (is (re-matches #"\n\s+outer-value\s+" (-> parsed :content first)))
      (is (= 1 (count (-> parsed :content last :content))))
      (is (re-matches #"\n\s+inner-value\s+" (-> parsed :content last :content first))))))

(comment
  (run-tests)

  )
