(ns cognitect.aws.protocols.rest-test
  (:require [clojure.test :refer :all]
            [cognitect.aws.protocols.rest :as protocols.rest]))

(deftest test-serialize-url
  (testing "ensures no double-slash"
    (let [shape {:members {:Foo {:shape "NonEmptyString" :location "uri" :locationName "Foo"}
                           :Bar {:shape "NonEmptyString" :location "uri" :locationName "Bar"}}}]
      (is (= "/a/b/c/d/e/f"
             (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" shape {:Foo "a/b/c" :Bar "d/e/f"} true)
             (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" shape {:Foo "a/b/c" :Bar "/d/e/f"} true)
             (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" shape {:Foo "/a/b/c" :Bar "/d/e/f"} true)
             (protocols.rest/serialize-uri "/{Foo+}/{Bar+}" shape {:Foo "/a/b/c" :Bar "d/e/f"} true)))))

  (testing "throws when required key is missing"
    (let [require-bucket {:members {:Bucket {:location "uri" :locationName "Bucket"}}
                          :required ["Bucket"]}
          require-bucket+key {:members {:Bucket {:location "uri" :locationName "Bucket"}
                                        :Key {:location "uri" :locationName "Key"}}
                              :required ["Bucket" "Key"]}]
      (is (thrown-with-msg? Exception
                            #"missing"
                            (protocols.rest/serialize-uri "/{Bucket}" require-bucket {} false)))
      (is (thrown-with-msg? Exception
                            #"missing"
                            (protocols.rest/serialize-uri "/{Bucket}" require-bucket {:BucketName "wrong key"} false)))
      (is (thrown-with-msg? Exception
                            #"missing"
                            (protocols.rest/serialize-uri "/{Bucket}/{Key+}" require-bucket+key {:Bucket "foo"} false)))))

  (testing "double url encoding"
    (= "/documents%2520and%2520settings/"
       (protocols.rest/serialize-uri "/{Foo}/" {} {:Foo "/documents and settings/"} false)))

  (let [arn "arn:aws:kafka:us-east-1:123456789:configuration/basic/dec4cfbc-a13b-4861-9fb7-627f3c93bb3d"
        config-shape {:members {:Arn {:location "uri" :locationName "arn"}}
                      :required ["Arn"]}
        config-revision-shape {:members {:Arn {:location "uri" :locationName "arn"}
                                         :Revision {:location "uri" :locationName "revision"}}
                               :required ["Arn" "Revision"]}]
    (testing "handles slashes in params"
      (is (= "/v1/configurations/arn%3Aaws%3Akafka%3Aus-east-1%3A123456789%3Aconfiguration%2Fbasic%2Fdec4cfbc-a13b-4861-9fb7-627f3c93bb3d"
           (protocols.rest/serialize-uri "/v1/configurations/{arn}" config-shape {:Arn arn} false))))
    (testing "handles numbers in params"
      (is (= "/v1/configurations/arn%3Aaws%3Akafka%3Aus-east-1%3A123456789%3Aconfiguration%2Fbasic%2Fdec4cfbc-a13b-4861-9fb7-627f3c93bb3d/revisions/1"
             (protocols.rest/serialize-uri "/v1/configurations/{arn}/revisions/{revision}" config-revision-shape {:Arn arn :Revision 1} false)))))

  (testing "uris not matching members"
    (let [arn "arn:aws:kafka:us-east-1:123456789:configuration/basic/dec4cfbc-a13b-4861-9fb7-627f3c93bb3d"
          uri (protocols.rest/serialize-uri "/v1/configurations/{arn}" {:members {:Arn {:location "uri" :locationName "arn"}}
                                                                        :required ["Arn"]}
                                            {:Arn arn} false)]
      (is (re-find #"dec4cfbc" uri))))

  (testing "s3 special cases"
    (= "/Bucket/my-object//example//photo.user"
       (protocols.rest/serialize-uri "/{Bucket}/{Key}/" {} {:Bucket "Bucket" :Key "my-object//example//photo.user"} true))))

(comment
  (run-tests)

  )
