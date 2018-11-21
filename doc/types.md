# Types

AWS service descriptions define a number of data types for use in
`:request` maps passed to `cognitect.aws.client.api/invoke` and
responses. aws-api supports them as follows:

| AWS type  | Clojure/java input type           | Clojure/java output type (if different) |
|-----------|-----------------------------------|-----------------------------------------|
| structure | map with keyword keys             |                                         |
| map       | map with arbitrary types for keys |                                         |
| list      | sequence                          |                                         |
| string    | String                            |                                         |
| character | Character                         |                                         |
| boolean   | Boolean                           |                                         |
| double    | Double                            |                                         |
| float     | Double                            |                                         |
| long      | Long                              |                                         |
| integer   | Long                              |                                         |
| blob      | byte[] or java.io.InputStream     | java.io.InputStream                     |
| timestamp | Long                              |                                         |

You can validate that your request maps conform to these types by
enabling `clojure.spec`-backed request validation during development:

``` clojure
(cognitect.aws.client.api/validate-requests client true)
```
