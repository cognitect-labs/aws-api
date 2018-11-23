# aws-api

aws-api is a Clojure library which provides programmatic access to AWS
services from your Clojure program.

## Rationale

AWS APIs are data oriented. Not only in the "send data, get data back"
sense, but all of the operations and data structures for every service
are, themselves, described in data which can be used to generate
mechanical transformations from application data to wire data and
back. This is exactly what we want from a Clojure API.

Using the AWS Java SDK directly via interop requires knowledge of
OO hierarchies of what are basically data classes, and while the
existing Clojure wrappers hide much of this from you, they don't
hide it from your process.

aws-api is an idiomatic, data-oriented Clojure library for
invoking AWS APIs.  While the library offers some helper and
documentation functions you'll use at development time, the only
functions you ever need at runtime are `client`, which creates a
client for a given service and `invoke`, which invokes an operation on
the service. `invoke` takes a map and returns a map, and works the
same way for every operation on every service.

## Approach

AWS APIs are described in data (json) which specifies operations, inputs, and
outputs. aws-api uses the same data descriptions to expose a
data-oriented interface, using service descriptions, documentation,
and specs which are generated from the source descriptions.

Each AWS SDK has its own copy of the data
descriptions in their github repos. We use
[aws-sdk-js](https://github.com/aws/aws-sdk-js/) as
the source for these, and release individual artifacts for each api.
The [api descriptors](https://github.com/aws/aws-sdk-js/tree/master/apis)
include the AWS `api-version` in their filenames (and in their data). For
example you'll see both of the following files listed:

    dynamodb-2011-12-05.normal.json
    dynamodb-2012-08-10.normal.json

Whenever we release com.cognitect.aws/dynamodb, we look for the
descriptor with the most recent API version. If aws-sdk-js-v2.351.0
contains an update to dynamodb-2012-08-10.normal.json, or a new
dynamodb descriptor with a more recent api-version, we'll make a
release whose version number includes the 2.351.0 from the version
of aws-sdk-js.

We also include the revision of our generator in the version. For example,
`com.cognitect.aws/dynamo-db-653.2.351.0` indicates revision `653` of the
generator, and tag `v2.351.0` of aws-sdk-js.

[Latest releases](doc/latest-releases.md)

## Usage

### prerequisites

* Clojure >= 1.9.0
* Clojure CLI tools >= 1.9.0.397
    * [https://clojure.org/guides/getting_started](https://clojure.org/guides/getting_started)

### deps

To use aws-api in your application, you depend on
`com.cognitect.aws/api`, `com.cognitect.aws/endpoints` and the service
of your choice, e.g. `com.cognitect.aws/s3`.

To use, for example, the s3 api, add the following to deps.edn

``` clojure
{:deps {com.cognitect.aws/api       {:mvn/version "0.8.89"}
        com.cognitect.aws/endpoints {:mvn/version "1.1.11.451"}
        com.cognitect.aws/s3        {:mvn/version "653.2.351.0"}}}
```

### explore!

Fire up a repl using that deps.edn, and then you can do things like this:

``` clojure
(require '[cognitect.aws.client.api :as aws])
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

See the [examples](examples) directory for more examples.

## Credentials lookup

The aws-api client looks up credentials the same way the [java
SDK](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html)
does.

## Region lookup

The aws-api client looks up the region the same with the [java
SDK](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-region-selection.html)
does, with an additional check for a System property named
"aws.region" after it checks for the AWS_REGION environment variable
and before it checks your aws configuration.

## Contributing

This library is open source, developed at Cognitect. As we incorporate
this it into products and client projects, we do not accept pull
requests or patches. We do, however, want to know how we can make it
better, so please file issues at
https://github.com/cognitect-labs/aws-api/issues.

## Copyright and License

Copyright © 2015 Cognitect

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
