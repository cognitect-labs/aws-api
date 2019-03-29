# Data Types

AWS service descriptions define a number of data types for use in `:request` maps passed to `cognitect.aws.client.api/invoke` and responses. aws-api supports them as follows:

| AWS type  | Clojure/java input type           | Clojure/java output type (if different) |
|-----------|-----------------------------------|-----------------------------------------|
| structure | map with keyword keys             |                                         |
| map       | map with arbitrary types for keys |                                         |
| list      | sequence                          |                                         |
| string    | string                            |                                         |
| character | character                         |                                         |
| boolean   | boolean                           |                                         |
| double    | double                            |                                         |
| float     | double                            |                                         |
| long      | long                              |                                         |
| integer   | long                              |                                         |
| blob      | byte[] or java.io.InputStream*    | java.io.InputStream                     |
| timestamp | java.util.Date                    |                                         |

* for the time being, if you submit a `java.io.InputStream` for a `blob`,
  it must fit in memory.

You can validate that your request maps conform to these types by enabling `clojure.spec`-backed request validation during development:

``` clojure
(cognitect.aws.client.api/validate-requests client true)
```
