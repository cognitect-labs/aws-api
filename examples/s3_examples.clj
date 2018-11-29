;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(require '[clojure.core.async :as a]
         '[clojure.spec.alpha :as s]
         '[clojure.spec.gen.alpha :as gen]
         '[clojure.java.io :as io]
         '[cognitect.aws.service :as service]
         '[cognitect.aws.client.api :as aws]
         '[cognitect.aws.client.api.async :as aws.async])

;; make a client
(def s3-client (aws/client {:api :s3}))

;; guard against invalid :request map
(aws/validate-requests s3-client true)

;; what can it do?
(aws/ops s3-client)
;; op names
(-> (aws/ops s3-client) keys)
;; op doc-data
(-> (aws/ops s3-client) :CreateBucket)
;; a little more friendly
(aws/doc s3-client :CreateBucket)

;; specs
(aws/request-spec-key s3-client :CreateBucket)
(s/describe (aws/request-spec-key s3-client :CreateBucket))
(gen/sample (s/gen (aws/request-spec-key s3-client :CreateBucket)))

(aws/response-spec-key s3-client :CreateBucket)
(s/describe (aws/response-spec-key s3-client :CreateBucket))
(gen/sample (s/gen (aws/response-spec-key s3-client :CreateBucket)))

;; * use this bucket-name to avoid collisions with other devs
;; * don't forget to delete the bucket when done exploring!
(def bucket-name (str "cognitect-aws-test-" (.getEpochSecond (java.time.Instant/now))))

;; see how submit works
(clojure.repl/doc aws/invoke)

;; doit
(aws/invoke s3-client {:op :ListBuckets})

;; http-response is in the metadata
(meta *1)

(aws/invoke s3-client {:op :CreateBucket :request {:Bucket bucket-name}})

;; now you should see the bucket you just added
(aws/invoke s3-client {:op :ListBuckets})

;; no objects yet ...
(aws/invoke s3-client {:op :ListObjects :request {:Bucket bucket-name}})

;; Body is blob type, for which we accept a byte-array or an InputStream
(aws/invoke s3-client {:op :PutObject :request {:Bucket bucket-name :Key "hello.txt"
                                                :Body (.getBytes "Oh hai!")}})

(aws/invoke s3-client {:op :PutObject :request {:Bucket bucket-name :Key "hello.txt"
                                                :Body (io/input-stream (.getBytes "Oh hai!"))}})

;; now you should see the object you just added
(aws/invoke s3-client {:op :ListObjects :request {:Bucket bucket-name}})

;; Body is a blob type, which always returns an InputStream
(aws/invoke s3-client {:op :GetObject :request {:Bucket bucket-name :Key "hello.txt"}})

;; check it!
(slurp (io/reader (:Body *1)))

(aws/invoke s3-client {:op :DeleteObject :request {:Bucket bucket-name :Key "hello.txt"}})

;; poof, the object is gone!
(aws/invoke s3-client {:op :ListObjects :request {:Bucket bucket-name}})

(aws/invoke s3-client {:op :DeleteBucket :request {:Bucket bucket-name}})

;; poof, the bucket is gone!
(aws/invoke s3-client {:op :ListBuckets})

;;;;;;;;;;;;;;;;;;;;;;;

;; see how submit works w/ async
(clojure.repl/doc aws.async/invoke)

;; async!
(def c (aws.async/invoke s3-client {:op :ListBuckets}))

(a/<!! c)

(meta *1)

;; supply your own channel
(let [ch (a/chan)]
  (aws.async/invoke s3-client {:op :ListBuckets
                               :ch ch})
  (a/<!! ch))
