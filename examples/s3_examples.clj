;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[clojure.core.async :as a]
         '[clojure.spec.alpha :as s]
         '[clojure.spec.gen.alpha :as gen]
         '[clojure.java.io :as io]
         '[clojure.repl :as repl]
         '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.client.api.async :as aws.async])

;; make a client
(def s3 (aws/client {:api :s3}))

;; guard against invalid :request map
(aws/validate-requests s3 true)

;; what can it do?
(aws/ops s3)
;; op names
(-> (aws/ops s3) keys sort)

;; op doc-data
(-> (aws/ops s3) :CreateBucket)
;; a little more friendly
(aws/doc s3 :CreateBucket)

;; specs
(aws/request-spec-key s3 :CreateBucket)
(s/describe (aws/request-spec-key s3 :CreateBucket))
(gen/sample (s/gen (aws/request-spec-key s3 :CreateBucket)))

(aws/response-spec-key s3 :CreateBucket)
(s/describe (aws/response-spec-key s3 :CreateBucket))
(gen/sample (s/gen (aws/response-spec-key s3 :CreateBucket)))

;; * use this bucket-name to avoid collisions with other devs
;; * don't forget to delete the bucket when done exploring!
(def bucket-name (str "cognitect-aws-test-" (.getEpochSecond (java.time.Instant/now))))

;; see how submit works
(repl/doc aws/invoke)

;; doit
(aws/invoke s3 {:op :ListBuckets})

;; http-request and http-response are in metadata
(meta *1)

(aws/invoke s3 {:op :CreateBucket :request {:Bucket bucket-name}})

;; now you should see the bucket you just added
(aws/invoke s3 {:op :ListBuckets})

;; no objects yet ...
(aws/invoke s3 {:op :ListObjectsV2 :request {:Bucket bucket-name}})

;; Body is blob type, for which we accept a byte-array or an InputStream
(aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name :Key "hello.txt"
                                         :Body (.getBytes "Oh hai!")}})

(aws/invoke s3 {:op :PutObject :request {:Bucket bucket-name :Key "hello.txt"
                                         :Body (io/input-stream (.getBytes "Oh hai!"))}})

;; now you should see the object you just added
(aws/invoke s3 {:op :ListObjectsV2 :request {:Bucket bucket-name}})

;; Body is a blob type, which always returns an InputStream
(aws/invoke s3 {:op :GetObject :request {:Bucket bucket-name :Key "hello.txt"}})

;; check it!
(slurp (:Body *1))

(aws/invoke s3 {:op :DeleteObject :request {:Bucket bucket-name :Key "hello.txt"}})

(aws/invoke s3 {:op :DeleteObjects, :request {:Delete {:Objects [{:Key "hello.txt"}]}, :Bucket bucket-name}})

;; poof, the object is gone!
(aws/invoke s3 {:op :ListObjectsV2 :request {:Bucket bucket-name}})

(aws/invoke s3 {:op :DeleteBucket :request {:Bucket bucket-name}})

;; poof, the bucket is gone!
(aws/invoke s3 {:op :ListBuckets})

;;;;;;;;;;;;;;;;;;;;;;;

;; see how submit works w/ async
(clojure.repl/doc aws.async/invoke)

;; async!
(def c (aws.async/invoke s3 {:op :ListBuckets}))

(a/<!! c)

(meta *1)

;; supply your own channel
(let [ch (a/chan)]
  (aws.async/invoke s3 {:op :ListBuckets
                        :ch ch})
  (a/<!! ch))
