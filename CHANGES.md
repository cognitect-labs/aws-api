# aws-api 0.8

## DEV

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
