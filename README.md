# WIP - none of this really exists yet!

# aws-api

aws-api is a Clojure library which provides programmatic access to AWS
services from your Clojure program.

## Rationale

AWS APIs are data-driven services.  AWS SDKs hide these services
behind language-specific OO interfaces. Absent a Clojure SDK, Clojure
developers lean primarily on the Java SDK either directly or through
wrapper libs.

aws-api is an idiomatic, data-oriented Clojure library for
invoking AWS APIs.  While the library offers some helper and
documentation functions you'll use at development time, the only
functions you ever need at runtime are `client`, which creates a
client for a given service and `invoke`, which invokes an operation on
the service. `invoke` takes a map and returns a map, and works the
same way for every operation on every service.

## Approach

AWS SDKs are described in data (json) which specifies operations, inputs, and
outputs. aws-api uses the same data descriptions to expose a
data-oriented interface, using service descriptions, documentation,
and specs which are generated from the source descriptions.

Each AWS SDK has its own copy of the data
descriptions in their github repos. We use
[aws-sdk-js](https://github.com/aws/aws-sdk-js/) as
the source for these, and release individual artifacts for each api.
The [api descriptors](https://github.com/aws/aws-sdk-js/tree/master/apis)
include the AWS api-version in their filenames (and in their data), for
example you'll see both of the following files listed:

    dynamodb-2011-12-05.normal.json
    dynamodb-2012-08-10.normal.json

Whenever we release com.cognitect.aws/dynamodb, we look for the
descriptor with the most recent API version. If aws-sdk-js-v2.324.0
contains an update to dynamodb-2012-08-10.normal.json, or a new
dynamodb descriptor with a more recent api-version, we'll make a
release whose version number includes the 2.324.0 from the version
of aws-sdk-js.

We also include the revision of our generator in the version. For example,
`com.cognitect.aws/dynamo-db-1234.2.324.0` indicates revision `1234` of the
generator, and tag `v2.324.0` of aws-sdk-js.

The api-version is whichever is the most recent api version as of
`v2.324.0`.  You can check the content of
[https://github.com/aws/aws-sdk-js/tree/v2.324.0/apis](https://github.com/aws/aws-sdk-js/tree/v2.324.0/apis),
to find it.

## Usage

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and the service
of your choice, e.g. `com.cognitect.aws/s3`.

The api library contains all the code you'll invoke and the others
provide data resources used to drive your interactions with the
service.

To use, for example, the s3 api, add the following to deps.edn

``` clojure
{:deps {com.cognitect.aws/api       {:mvn/version "<version>"}
        com.cognitect.aws/endpoints {:mvn/version "<version>"}
        com.cognitect.aws/s3        {:mvn/version "<version>"}}
```

Fire up a repl using that deps.edn, and then you can do things like this:

``` clojure
(require '[cognitect.aws.client.api :as aws]))
```

Create a client:

```clojure
(def s3-client (aws/client {:api :s3}))
```

Ask what ops your client can perform:

``` clojure
(aws/ops s3-client)
```

Look up docs for an operation:

``` clojure
(aws/doc s3-client :CreateBucket)
```

Do stuff:

``` clojure
(aws/invoke s3-client {:op :ListBuckets})
;; http-response is in the metadata
(meta *1)
(aws/invoke s3-client {:op :CreateBucket :request {:Bucket "my-unique-bucket-name"}})
(aws/invoke s3-client {:op :ListBuckets})
```
