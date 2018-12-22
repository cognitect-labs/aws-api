# aws-api 0.8

## DEV

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
