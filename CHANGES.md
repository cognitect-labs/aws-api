# aws-api 0.8

### 0.8.352 / 2019-07-26

* Use custom dynaload for http-client [#88](https://github.com/cognitect-labs/aws-api/issues/88)
  * Restores compatibility with Clojure-1.9

## 0.8.345 / 2019-07-06

* Update pom.xml [#86](https://github.com/cognitect-labs/aws-api/issues/86)

## ~~0.8.342 / 2019-07-06~~

* skip whitespace when reading XML [#85](https://github.com/cognitect-labs/aws-api/issues/85)

## ~~0.8.335 / 2019-07-05~~

* use clojure.data.xml instead of clojure.xml
  * resolves Illegal Reflective Access warnings in java 11 [#19](https://github.com/cognitect-labs/aws-api/issues/19)
* upgrade cognitect http-client
  * upgrades jetty to 9.4 [#81](https://github.com/cognitect-labs/aws-api/issues/81)
  * resolves Illegal Reflective Access warnings in java 11 [#19](https://github.com/cognitect-labs/aws-api/issues/19)
* share http-client across aws-api clients [#80](https://github.com/cognitect-labs/aws-api/issues/80)
* add Content-Type header for rest-json requests [#84](https://github.com/cognitect-labs/aws-api/issues/84)

## 0.8.305 / 2019-05-10

* add x-amz-glacier-version header to glacier requests [#74](https://github.com/cognitect-labs/aws-api/issues/74)

## 0.8.301 / 2019-04-19

* fix bug generating default idempotencyToken [#72](https://github.com/cognitect-labs/aws-api/issues/72)
* fix uri-encoding bug when `*unchecked-math*` is true [#71](https://github.com/cognitect-labs/aws-api/issues/71)

## 0.8.292 / 2019-04-12

* improved support for apigatewaymanagementapi `:PostToConnection`
  * see [https://github.com/cognitect-labs/aws-api/#posttoconnection](https://github.com/cognitect-labs/aws-api/#posttoconnection)

## 0.8.289 / 2019-03-29

* fix signing bug introduced in 0.8.283

## 0.8.283 / 2019-03-29

* read input-stream once [#67](https://github.com/cognitect-labs/aws-api/issues/67)

## 0.8.280 / 2019-03-25

* support `:endpoint-override` map [#59](https://github.com/cognitect-labs/aws-api/issues/59), [#61](https://github.com/cognitect-labs/aws-api/issues/61), [#64](https://github.com/cognitect-labs/aws-api/issues/64)
  * DEPRECATED support for `:endoint-override` string
* only parse json response body when output-shape is specified [#66](https://github.com/cognitect-labs/aws-api/issues/66)

## 0.8.273 / 2019-03-01

* friendly error when op not supported [#62](https://github.com/cognitect-labs/aws-api/issues/62)

## 0.8.271 / 2019-02-25

* daemonize the credentials auto-refresh thread [#57](https://github.com/cognitect-labs/aws-api/issues/57)

## 0.8.266 / 2019-02-22

* add Content-MD5 header for some S3 ops [#40](https://github.com/cognitect-labs/aws-api/issues/40)
* require clojure.edn [#60](https://github.com/cognitect-labs/aws-api/issues/60)

## 0.8.253 / 2019-02-08

* fixed URI generation bug [#56](https://github.com/cognitect-labs/aws-api/issues/56)
* ensure keyword keys for errors in json protocols [#55](https://github.com/cognitect-labs/aws-api/issues/55)
* add stop fn to release resources [#54](https://github.com/cognitect-labs/aws-api/issues/54)

## 0.8.243 / 2019-02-01

* fix signing bug (double slashes in constructed uris) [#53](https://github.com/cognitect-labs/aws-api/issues/53)
* fix signing bug (query-string ordering) [#52](https://github.com/cognitect-labs/aws-api/issues/52)

## 0.8.223 / 2019-01-25

* support endpoint-override [#43](https://github.com/cognitect-labs/aws-api/issues/43)
* parse "map" shapes using their key spec instead of assuming keyword keys [#50](https://github.com/cognitect-labs/aws-api/issues/50)
* wrap non-sequential values when the spec calls for a list [#45](https://github.com/cognitect-labs/aws-api/issues/45)
* config parsing bug fixes [#46](https://github.com/cognitect-labs/aws-api/issues/46), [#48](https://github.com/cognitect-labs/aws-api/issues/48)
* support refreshing credentials [#47](https://github.com/cognitect-labs/aws-api/issues/47)

## 0.8.204 / 2019-01-18

* support nested values in config [#42](https://github.com/cognitect-labs/aws-api/issues/42)
* handle doubles when parsing timestamps [#36](https://github.com/cognitect-labs/aws-api/issues/36)

## 0.8.198 / 2019-01-11

* support HAL responses [#30](https://github.com/cognitect-labs/aws-api/issues/30)

* extend Datafiable via metadata (allows use with clojure-1.9) [#44](https://github.com/cognitect-labs/aws-api/issues/44)

* fix edge case bug parsing json with locationNames

* use the correct metadata endpoint when running in ECS [#33](https://github.com/cognitect-labs/aws-api/issues/33)

## 0.8.171 / 2018-12-28

* use the configured region as signing region for S3 requests
  * fixes regression introduced in fixing [#27](https://github.com/cognitect-labs/aws-api/issues/27)
  * we still need a better solution for [#15](https://github.com/cognitect-labs/aws-api/issues/15)

## 0.8.166 / 2018-12-22

* use us-east-1 as signing region when service has globalEndpoint [#27](https://github.com/cognitect-labs/aws-api/issues/27)

## 0.8.158 / 2018-12-13

* use signingName when available in service metadata. [#26](https://github.com/cognitect-labs/aws-api/issues/26)

## 0.8.155 / 2018-12-12

* Stringify query protocol body param vals before url encoding them. [#25](https://github.com/cognitect-labs/aws-api/issues/25)

## 0.8.149 / 2018-12-12

* URL encode query protocol body params. [#22](https://github.com/cognitect-labs/aws-api/issues/22)

## 0.8.146 / 2018-12-10

* Fix bug caused by attempting to parse a response body when no output shape specified. [#21](https://github.com/cognitect-labs/aws-api/issues/21)

## 0.8.142 / 2018-12-06

* Add http-request to response metadata.

## 0.8.140 / 2018-12-06

* Add `basic-credentials-provider` helper fn. Thanks to Christian Gonzalez for the suggestion. [#16](https://github.com/cognitect-labs/aws-api/issues/16)
* log/debug instead of log/error when individual credentials providers can't find credentials. [#17](https://github.com/cognitect-labs/aws-api/issues/17)

## 0.8.122 / 2018-11-30

* Initial release.
