# aws-api

## 0.8.800 / 2026-02-03

* Do not attempt to use unsupported protocols [#291](https://github.com/cognitect-labs/aws-api/issues/291)
  * Some AWS services support multiple protocols, and aws-api would always pick the first one, even for cases where
    protocol support was not implemented. Now it picks the first _supported_ protocol instead.
* Update dependencies

## 0.8.774 / 2025-09-02

* Add http read timeout to IMDS requests [#267](https://github.com/cognitect-labs/aws-api/issues/267)
* Fix `rest-json` and `json` protocol request headers [#168](https://github.com/cognitect-labs/aws-api/issues/168) [#241](https://github.com/cognitect-labs/aws-api/issues/241)

## 0.8.762 / 2025-08-05

* Add Babashka support
* NOTE: the internal class `cognitect.aws.client.impl.Client` was removed

## 0.8.741 / 2025-04-17

* Fix invalid signature for nonstandard host port [#263](https://github.com/cognitect-labs/aws-api/issues/263)
* Upgrade dependencies

## 0.8.735 / 2025-02-26

* This is the official release of the previous `0.8.730-beta01` release, see those release notes

## 0.8.730-beta01 / 2025-01-30

* Ensure all resources are loaded with the expected class loader [#265](https://github.com/cognitect-labs/aws-api/issues/265)
* Don't unnecessarily deref shared delays when they are overridden [#262](https://github.com/cognitect-labs/aws-api/issues/262)
* Revert virtual thread executor in HttpClient
  * This executor is meant to handle asynchronous non-blocking tasks, so virtual threads are not a good fit

## 0.8.723 / 2024-12-23

* Upgrade dependencies
* Fix InvalidSignatureException with services that support HTTP/2 [#261](https://github.com/cognitect-labs/aws-api/issues/261)
* Performance improvements in highly parallel scenarios related to URI encoding and date/time formatting
* Use newVirtualThreadPerTaskExecutor if virtual threads are available

## 0.8.711 / 2024-12-03

* New `java.net.http`-based HTTP client, without external dependencies
  * BREAKING: when running on java 8, an explicit dependency on `com.cognitect/http-client` is now required
* Support IMDS v2 in default credentials and region provider chains [#243](https://github.com/cognitect-labs/aws-api/issues/243) [#165](https://github.com/cognitect-labs/aws-api/issues/165)

See [Upgrade Notes](https://github.com/cognitect-labs/aws-api/blob/master/UPGRADE.md) for more
information about upgrading to this version.


## 0.8.710-beta01 / 2024-10-16

* New `java.net.http`-based HTTP client, without external dependencies
  * BREAKING: when running on java 8, an explicit dependency on `com.cognitect/http-client` is now required
* Support IMDS v2 in default credentials and region provider chains [#243](https://github.com/cognitect-labs/aws-api/issues/243) [#165](https://github.com/cognitect-labs/aws-api/issues/165)

See [Upgrade Notes](https://github.com/cognitect-labs/aws-api/blob/master/UPGRADE.md) for more
information about upgrading to this version.

## 0.8.692 / 2024-01-31

* upgrade to cognitect http-client 1.0.127
  * upgrade to org.eclipse.jetty/jetty-client-9.4.53.v20231009 [#249](https://github.com/cognitect-labs/aws-api/issues/249)
* upgrade to org.clojure/core.async 1.6.681
* upgrade to org.clojure/data.json 2.5.0

## 0.8.686 / 2023-07-06
* Support list member values in request headers [#234](https://github.com/cognitect-labs/aws-api/issues/234)

## 0.8.681 / 2023-06-16
* add `:cognitect.aws.http/status` and, when AWS provides an error code, `:cognitect.aws.error/code`, to error response maps

## 0.8.673 / 2023-05-23
* bug fix: add type-hint to resolve performance warning [#239](https://github.com/cognitect-labs/aws-api/issues/239)

## ~~0.8.670 / 2023-05-22~~
* WARNING: introduced performance warning; fixed in 0.8.673.
* BREAKING CHANGE: Changes the behavior of GetObject such that a 304 results in an anomaly.
  * This was reported in [#240](https://github.com/cognitect-labs/aws-api/issues/240), and after further review we decided to keep the new behavior in order to align with AWS semantics.
    * https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
    * https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html
* upgrade cognitect http-client to 1.0.125 and set follow-redirects false
  * Fixes [#15](https://github.com/cognitect-labs/aws-api/issues/15)

## 0.8.666 / 2023-04-27
* 301 gets cognitect.anomalies/incorrect instead of cognitect.anomalies/fault [#237](https://github.com/cognitect-labs/aws-api/issues/237)

## 0.8.664 / 2023-04-26
* Safely return byte arrays from ByteBuffers, honoring the `position` and `remaining` attributes, copying the underlying byte array when necessary.
* Upgrade to com.cognitect/http-client "1.0.123", which includes status and headers in the anomaly returned when there is a 301
  * Fixes [#171](https://github.com/cognitect-labs/aws-api/issues/171)
  * Improves [#15](https://github.com/cognitect-labs/aws-api/issues/15)

## 0.8.656 / 2023-03-14
* make ThrottlingException retriable [#233](https://github.com/cognitect-labs/aws-api/issues/233)

## 0.8.652 / 2023-02-28
* ensure that we only read InputStream request bodies once [#231](https://github.com/cognitect-labs/aws-api/issues/231)
  * h/t @jetmind
* convey :document types in response payloads [#232](https://github.com/cognitect-labs/aws-api/issues/232)

## 0.8.641 / 2023-01-17
* add Authorization header to container credentials request when AWS_CONTAINER_AUTHORIZATION_TOKEN present [#225](https://github.com/cognitect-labs/aws-api/issues/225)

## 0.8.635 / 2022-12-28
* fix URI parsing bug in ec2-metadata-utils
  * h/t @stevebuik
* add function to instrument test double client after initialization

## 0.8.630 / 2022-12-05
* log the credentials provider that succeeded [#217](https://github.com/cognitect-labs/aws-api/issues/217)
* add keyword access to :api on the test double client [#224](https://github.com/cognitect-labs/aws-api/issues/224)

## 0.8.620 / 2022-12-01
* ensure that serialize-uri can handle longs [#145](https://github.com/cognitect-labs/aws-api/issues/145)
  * h/t @ghost
* add keyword access to :api on client

## 0.8.615 / 2022-11-28
* fix URI encoding for signing [#193](https://github.com/cognitect-labs/aws-api/issues/193)
  * h/t @kgann

## 0.8.612 / 2022-10-27
* upgrade to org.clojure/core.async "1.6.673"
* NOTE: this release requires clojure >= 1.10

## 0.8.603 / 2022-10-11

* parse XML error messages from AWS even when we ask for JSON [#174](https://github.com/cognitect-labs/aws-api/issues/174)

## 0.8.596 / 2022-09-16

* upgrade to org.clojure/data.xml 0.2.0-alpha8. Fixes parsing issue [#205](https://github.com/cognitect-labs/aws-api/issues/205)

## 0.8.589 / 2022-09-12

* add `api/invoke-async` and deprecate `api.async/invoke`
* add test double client with custom handler support [#186](https://github.com/cognitect-labs/aws-api/issues/186)

## 0.8.587 / 2022-08-30

* add keyword access to :service (with :metadata) and :http-client

## 0.8.584 / 2022-08-29

* add keyword access to :region, :endoint, and :credentials on an aws client

## 0.8.575 / 2022-07-25

* upgrade to cognitect/http-client-1.0.115 to upgrade jetty [#215](https://github.com/cognitect-labs/aws-api/issues/215)
* remove unused arg to http-client constructor [#203](https://github.com/cognitect-labs/aws-api/issues/203)
* log "Unable to fetch credentials" at debug level instead of info [#196](https://github.com/cognitect-labs/aws-api/issues/196)

## 0.8.568 / 2022-07-08

* fix bug to ensure :endpoint is included in datafied client

## 0.8.561 / 2022-05-27

* consider `cognitect.anomalies/interrupted` from the http-client retriable
* check `AWS_SHARED_CREDENTIALS_FILE` as well as `AWS_CREDENTIAL_PROFILES_FILE` [#188](https://github.com/cognitect-labs/aws-api/issues/188)
* send accept header for json APIs [#206](https://github.com/cognitect-labs/aws-api/issues/206)

## 0.8.536 / 2021-12-08

* upgrade to core.async-1.5.644
* upgrade to http-client-1.0.110

## 0.8.532 / 2021-12-07

* upgrade to core.async-1.5.640
* status 429 to :cognitect.anomalies/busy [#197](https://github.com/cognitect-labs/aws-api/issues/197)

## 0.8.515 / 2021-06-25

* add support for calculating TTL values based on supplied Java Instant objects or Longs representing milliseconds since the epoch time [#160](https://github.com/cognitect-labs/aws-api/issues/160)

## 0.8.505 / 2021-02-23

* upgrade to cognitect/http-client-0.1.106
  * upgrade to org.eclipse.jetty/jetty-client-9.4.36.v20210114 [#173](https://github.com/cognitect-labs/aws-api/issues/173)

## 0.8.484 / 2020-11-05

* make date parsing more tolerant [#155](https://github.com/cognitect-labs/aws-api/issues/155)

## 0.8.474 / 2020-08-15

* fix bug decoding GetBucketPolicy response [#148](https://github.com/cognitect-labs/aws-api/issues/148)

## 0.8.469 / 2020-07-10

* fix bug parsing iso8601 dates with fractional seconds [#144](https://github.com/cognitect-labs/aws-api/issues/144)
* fix memory leak when validating requests [#143](https://github.com/cognitect-labs/aws-api/issues/143)

## 0.8.456 / 2020-03-27

* upgrade to tools.logging-1.0.0, data.json-1.0.0, and core.async-1.0.567
* let core.async manage the threadpool for fetching credentials and region
  * fixes deadlock with concurrent credentials fetch [#137](https://github.com/cognitect-labs/aws-api/issues/137)

## 0.8.445 / 2020-02-25

* fix deadlock using composite credentials providers (e.g. assume role example) [#130](https://github.com/cognitect-labs/aws-api/issues/130)

## 0.8.437 / 2020-02-14

* fix issue with `invoke` hanging when no region or creds are found [#124](https://github.com/cognitect-labs/aws-api/issues/124)

## 0.8.430 / 2020-02-10

* upgrade to com.cognitect/http-client 0.1.104 [#115](https://github.com/cognitect-labs/aws-api/issues/115)
* all aws clients use shared http-client, credentials-provider, and region-provider by default
  * addresses [#109](https://github.com/cognitect-labs/aws-api/issues/109)
  * first call to invoke takes hit of fetching region and credentials
* `com.cognitect.aws.api/stop` will not stop the shared http-client, but stop any other instance

See [Upgrade Notes](https://github.com/cognitect-labs/aws-api/blob/master/UPGRADE.md) for more
information about upgrading to this version.

## 0.8.423 / 2020-01-17

* Remove dep on commons-codec [#113](https://github.com/cognitect-labs/aws-api/issues/113)
* Convey anomaly from http-client as/is [#114](https://github.com/cognitect-labs/aws-api/issues/114)

## 0.8.408 / 2019-11-25

* Reduce noise from reflection warnings in java 9+ [#106](https://github.com/cognitect-labs/aws-api/issues/106)
* Get signing region from endpoint config [#105](https://github.com/cognitect-labs/aws-api/issues/105)
* Add documentationUrl when available [#108](https://github.com/cognitect-labs/aws-api/issues/108)

## 0.8.391 / 2019-10-25

* Fix: S3 HeadObject fails with large files [#97](https://github.com/cognitect-labs/aws-api/issues/97)
  * This was fixed in cognitect/http-client 0.1.101
* Fix concurrency 4 limit introduced in 0.8.383

## ~~0.8.383 / 2019-10-24~~

* Make http calls to fetch credentials async / non-blocking.

## 0.8.378 / 2019-10-19

* Include service full names in latest-releases.edn [#32](https://github.com/cognitect-labs/aws-api/issues/32)
* Wrap dynamic require of protocol ns in locking form [#92](https://github.com/cognitect-labs/aws-api/issues/92)

## 0.8.352 / 2019-07-26

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
