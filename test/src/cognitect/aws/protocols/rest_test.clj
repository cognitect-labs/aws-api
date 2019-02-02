(ns cognitect.aws.protocols.rest-test
  (:require [clojure.test :refer :all]
            [cognitect.aws.protocols.rest :as protocols.rest]))

(deftest test-serialize-url
  (testing "ensures no double-slash"
    (is (= "/a/b/c/d/e/f"
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {:Foo "a/b/c" :Bar "d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {:Foo "a/b/c" :Bar "/d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {:Foo "/a/b/c" :Bar "/d/e/f"})
           (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" {:Foo "/a/b/c" :Bar "d/e/f"})))))
