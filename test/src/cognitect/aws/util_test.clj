(ns cognitect.aws.util-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
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
    (let [parsed (util/xml-read (java.io.ByteArrayInputStream.
                                 (.getBytes "<outer>
                                  outer-value
                                  <inner>
                                     inner-value
                                  </inner>
                                </outer>")))]
      (is (= 2 (count (-> parsed :content))))
      (is (re-matches #"\n\s+outer-value\s+" (-> parsed :content first)))
      (is (= 1 (count (-> parsed :content last :content))))
      (is (re-matches #"\n\s+inner-value\s+" (-> parsed :content last :content first))))))

(deftest hex-encode
  (is (= (util/hex-encode (byte-array (range -128 128)))
        "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")))

(s/fdef util/uri-encode
  :args (s/cat :string          (s/and string? (complement str/blank?))
               :exclude-slashes (s/? #{true}))
  :fn   (fn [{:keys [args ret]}]
          (= (:string args)
             (java.net.URLDecoder/decode ret)))
  :ret string?)

(deftest test-uri-encode
  (testing "regression for issue-71"
    ;; See https://github.com/cognitect-labs/aws-api/issues/71
    (testing "with *unchecked-math* true"
      (binding [*unchecked-math* true]
        (require '[cognitect.aws.util :as util] :reload)
        (let [res (:clojure.spec.test.check/ret (first (stest/check `util/uri-encode)))]
          (is (true? (:result res))
              res))))

    (testing "with *unchecked-math* false"
      (binding [*unchecked-math* false]
        (require '[cognitect.aws.util :as util] :reload)
        (let [res (:clojure.spec.test.check/ret (first (stest/check `util/uri-encode)))]
          (is (true? (:result res))
              res))))))


(comment
  (run-tests)

  )
