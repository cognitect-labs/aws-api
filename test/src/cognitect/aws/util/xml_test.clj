(ns cognitect.aws.util.xml-test
  (:require [clojure.data.xml :as data.xml]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cognitect.aws.util :as util]
            [cognitect.aws.util.xml :as util.xml])
  (:import (java.io StringReader)))

; Sampled from real S3 responses
(def aws-responses
  [; s3
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Owner><ID>02d6176db174dc93cb1b899f7c6078f08654445fe8cf1b6ce98d8855f66bdbf4</ID><DisplayName>minio</DisplayName></Owner><Buckets><Bucket><Name>aws-api-test-bucket-1773871449613-132</Name><CreationDate>2026-03-18T22:04:09.758Z</CreationDate></Bucket><Bucket><Name>aws-api-test-bucket-1773871603324-938</Name><CreationDate>2026-03-18T22:06:43.521Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>some-bucket</Name><Prefix></Prefix><KeyCount>1</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><Contents><Key>some-key/</Key><LastModified>2026-01-13T17:47:19.000Z</LastModified><ETag>&quot;d41d8cd98f00b204e9800998ecf8427e&quot;</ETag><ChecksumAlgorithm>CRC64NVME</ChecksumAlgorithm><ChecksumType>FULL_OBJECT</ChecksumType><Size>0</Size><StorageClass>STANDARD</StorageClass></Contents></ListBucketResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Deleted><Key>hello.txt</Key></Deleted><Deleted><Key>hai.txt</Key></Deleted><Deleted><Key>oi.txt</Key></Deleted></DeleteResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"
   ; route53
   "<?xml version=\"1.0\"?>
 <ListGeoLocationsResponse xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><GeoLocationDetailsList><GeoLocationDetails><CountryCode>*</CountryCode><CountryName>Default</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AD</CountryCode><CountryName>Andorra</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AE</CountryCode><CountryName>United Arab Emirates</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AF</CountryCode><CountryName>Afghanistan</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AG</CountryCode><CountryName>Antigua and Barbuda</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AI</CountryCode><CountryName>Anguilla</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AL</CountryCode><CountryName>Albania</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AM</CountryCode><CountryName>Armenia</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AO</CountryCode><CountryName>Angola</CountryName></GeoLocationDetails><GeoLocationDetails><CountryCode>AQ</CountryCode><CountryName>Antarctica</CountryName></GeoLocationDetails></GeoLocationDetailsList><IsTruncated>true</IsTruncated><NextCountryCode>AR</NextCountryCode><MaxItems>10</MaxItems></ListGeoLocationsResponse>"
   "<?xml version=\"1.0\"?>
 <ListCidrCollectionsResponse xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><CidrCollections/></ListCidrCollectionsResponse>"
   "<?xml version=\"1.0\"?>
 <ListHostedZonesResponse xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><HostedZones><HostedZone><Id>/hostedzone/REDACTED</Id><Name>example.com.</Name><CallerReference>RISWorkflow-RD:00000000-0000-0000-0000-000000000000</CallerReference><Config><Comment>HostedZone created by Route53 Registrar</Comment><PrivateZone>false</PrivateZone></Config><ResourceRecordSetCount>116</ResourceRecordSetCount></HostedZone><HostedZone><Id>/hostedzone/ALSOREDACTED</Id><Name>sub.example.com.</Name><CallerReference>00000000-0000-0000-0000-000000000000</CallerReference><Config><Comment>Subdomain</Comment><PrivateZone>false</PrivateZone></Config><ResourceRecordSetCount>3</ResourceRecordSetCount></HostedZone><HostedZone><Id>/hostedzone/REDACTEDASWELL</Id><Name>other.example.com.</Name><CallerReference>00000000-0000-0000-0000-000000000000</CallerReference><Config><Comment></Comment><PrivateZone>false</PrivateZone></Config><ResourceRecordSetCount>2</ResourceRecordSetCount></HostedZone></HostedZones><IsTruncated>true</IsTruncated><NextMarker>SOMEMARKER</NextMarker><MaxItems>3</MaxItems></ListHostedZonesResponse>"
   ; cloudfront
   "<?xml version=\"1.0\"?>
 <Distribution xmlns=\"http://cloudfront.amazonaws.com/doc/2020-05-31/\"><Id>REDACTED</Id><ARN>arn:aws:cloudfront::123456789012:distribution/REDACTED</ARN><Status>Deployed</Status><LastModifiedTime>2020-07-13T00:32:05.367Z</LastModifiedTime><InProgressInvalidationBatches>0</InProgressInvalidationBatches><DomainName>redacted.cloudfront.net</DomainName><ActiveTrustedSigners><Enabled>false</Enabled><Quantity>0</Quantity></ActiveTrustedSigners><ActiveTrustedKeyGroups><Enabled>false</Enabled><Quantity>0</Quantity></ActiveTrustedKeyGroups><DistributionConfig><CallerReference>1234567890123</CallerReference><Aliases><Quantity>0</Quantity></Aliases><DefaultRootObject></DefaultRootObject><Origins><Quantity>1</Quantity><Items><Origin><Id>s3-test</Id><DomainName>test.s3.amazonaws.com</DomainName><OriginPath></OriginPath><CustomHeaders><Quantity>0</Quantity></CustomHeaders><S3OriginConfig><OriginAccessIdentity>origin-access-identity/cloudfront/REDACTED</OriginAccessIdentity><OriginReadTimeout>30</OriginReadTimeout></S3OriginConfig><ConnectionAttempts>3</ConnectionAttempts><ConnectionTimeout>10</ConnectionTimeout><OriginShield><Enabled>false</Enabled></OriginShield><OriginAccessControlId></OriginAccessControlId></Origin></Items></Origins><OriginGroups><Quantity>0</Quantity></OriginGroups><DefaultCacheBehavior><TargetOriginId>s3-test</TargetOriginId><TrustedSigners><Enabled>false</Enabled><Quantity>0</Quantity></TrustedSigners><TrustedKeyGroups><Enabled>false</Enabled><Quantity>0</Quantity></TrustedKeyGroups><ViewerProtocolPolicy>redirect-to-https</ViewerProtocolPolicy><AllowedMethods><Quantity>2</Quantity><Items><Method>HEAD</Method><Method>GET</Method></Items><CachedMethods><Quantity>2</Quantity><Items><Method>HEAD</Method><Method>GET</Method></Items></CachedMethods></AllowedMethods><SmoothStreaming>false</SmoothStreaming><Compress>true</Compress><LambdaFunctionAssociations><Quantity>2</Quantity><Items><LambdaFunctionAssociation><LambdaFunctionARN>arn:aws:lambda:us-east-1:123456789012:function:cloudfront-url-rewrite:16</LambdaFunctionARN><EventType>origin-request</EventType><IncludeBody>false</IncludeBody></LambdaFunctionAssociation><LambdaFunctionAssociation><LambdaFunctionARN>arn:aws:lambda:us-east-1:123456789012:function:lambda-test:14</LambdaFunctionARN><EventType>viewer-request</EventType><IncludeBody>false</IncludeBody></LambdaFunctionAssociation></Items></LambdaFunctionAssociations><FunctionAssociations><Quantity>0</Quantity></FunctionAssociations><FieldLevelEncryptionId></FieldLevelEncryptionId><GrpcConfig><Enabled>false</Enabled></GrpcConfig><ForwardedValues><QueryString>false</QueryString><Cookies><Forward>none</Forward></Cookies><Headers><Quantity>0</Quantity></Headers><QueryStringCacheKeys><Quantity>0</Quantity></QueryStringCacheKeys></ForwardedValues><MinTTL>0</MinTTL><DefaultTTL>0</DefaultTTL><MaxTTL>0</MaxTTL></DefaultCacheBehavior><CacheBehaviors><Quantity>0</Quantity></CacheBehaviors><CustomErrorResponses><Quantity>0</Quantity></CustomErrorResponses><Comment></Comment><Logging><Enabled>true</Enabled><IncludeCookies>true</IncludeCookies><Bucket>access-logs.s3.amazonaws.com</Bucket><Prefix>example.com</Prefix></Logging><PriceClass>PriceClass_All</PriceClass><Enabled>true</Enabled><ViewerCertificate><CloudFrontDefaultCertificate>false</CloudFrontDefaultCertificate><ACMCertificateArn>arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000</ACMCertificateArn><SSLSupportMethod>sni-only</SSLSupportMethod><MinimumProtocolVersion>TLSv1.1_2016</MinimumProtocolVersion><Certificate>arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000</Certificate><CertificateSource>acm</CertificateSource></ViewerCertificate><Restrictions><GeoRestriction><RestrictionType>none</RestrictionType><Quantity>0</Quantity></GeoRestriction></Restrictions><WebACLId>arn:aws:wafv2:us-east-1:123456789012:global/webacl/generic-internal-access/00000000-0000-0000-0000-000000000000</WebACLId><HttpVersion>http2</HttpVersion><IsIPV6Enabled>false</IsIPV6Enabled><ContinuousDeploymentPolicyId></ContinuousDeploymentPolicyId><Staging>false</Staging></DistributionConfig></Distribution>"

   ; Errors
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message><Key>not-there</Key><RequestId>T0HLMOBVD5RIWGOM</RequestId><HostId>h+n2lhSA0AwatGPHV8qQlz4yDbaPKVJdBkwM+7fsV84AeKVlupffFt2cdKi1D31hVHPZkm2jsLg=</HostId></Error>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>AccessDenied</Code><Message>Access Denied</Message><AccountId>123456789012</AccountId><RequestId>48W6LOGUQCQ82YRL</RequestId><HostId>Ly20ZLlQMhBoIL75L9QsPAda2b2YDWB0/uTcHGLrYMKbql4ukyfazudx/TsAEo1LHtgDsTunlYo=</HostId></Error>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>NoSuchTagSet</Code><Message>The TagSet does not exist</Message><BucketName>some-bucket</BucketName><RequestId>WFVYD+KWSDCT0EDC</RequestId><HostId>HgJ5JGWgVvl5lGnuuq+/tog3BLlz5NOaeeQOWvGznfF9OEkiCPKzgBEVo2NI5kV+WOYQUniw4+M=</HostId></Error>"
   "<Error><Code>NoSuchLifecycleConfiguration</Code><Message>The lifecycle configuration does not exist</Message><BucketName>some-bucket</BucketName><RequestId>EBFD31HEEYTS09UU</RequestId><HostId>8GwIlsyERtJBc7zu10UjUKmBwNycRk6a/eCdphgRv8qobEbUYKDZcpPCbBZFDKmN2VlRGgl6G58=</HostId></Error>"
   ])

; Sampled from data.xml tests
(def data-xml-inputs
  ["<foo><bar/></foo>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<foo>\n    <bar/>\n  </foo>\n  "

   "<a>\n<b with-attr=\"s p a c e\">123</b>\n<c>1 2 3</c>\n\n</a>"

   "<cdata><is><here><![CDATA[<dont><parse><me>]]></here></is></cdata>"

   "<comment><is><here><!-- or could be -->there</here></is></comment>"

   "<?xml version=\"1.0\" encoding=\"utf-8\"?>
                   <?xml-stylesheet type='text/xsl' href='someFile.xsl'?>
                   <ATag>With Stuff</ATag>"

   "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"
                \"foo://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">
                <html><h1>Heading Stuff</h1></html>"

   "<a><![CDATA[\nfoo bar\n]]><![CDATA[\nbaz\n]]></a>"

   "<a><b/>\n<b/></a>"

   "<?xml version=\"1.0\"?>
 <!DOCTYPE methodCall [
   <!ELEMENT methodCall (methodName, params)>
   <!ELEMENT params (param+)>
   <!ELEMENT param (value)>
   <!ELEMENT value (string)>
   <!ELEMENT methodName (#PCDATA)>
   <!ELEMENT string (#PCDATA)>
 ]>
 <methodCall>
   <methodName>lookupSymbol</methodName>
   <params>
     <param>
       <value>
         <string>
           Clojure XML &lt;3
         </string>
       </value>
     </param>
   </params>
 </methodCall>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><D:limit xmlns:D=\"DAV:\"><D:nresults>100</D:nresults></D:limit>"

   "<limit xmlns:D=\"DAV:\"><nresults>100</nresults></limit>"

   "<D:limit xmlns:D=\"DAV:\"><D:nresults>100</D:nresults></D:limit>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><top-level xmlns:a=\"DAV:\"/>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><local-root xmlns=\"DAV:\"><top-level xmlns=\"\" xmlns:a=\"DAV:\"/></local-root>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><D:limit xmlns:D=\"DAV:\"><a:other xmlns:a=\"uri-v:\"/></D:limit>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a:limit xmlns:a=\"DAV:\"><b:other xmlns:b=\"uri-v:\"/></a:limit>"

   "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a:limit xmlns:a=\"DAV:\" xmlns:b=\"uri-v:\" b:moo=\"gee\"><a:nresults>100</a:nresults></a:limit>"

   (str "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
        "<!DOCTYPE foo ["
        "  <!ELEMENT foo ANY >"
        "  <!ENTITY xxe SYSTEM \"" (io/resource ".aws/config") "\" >]>"
        "<foo>&xxe;</foo>")

   ; NOTE: these cases do not work because util/xml->map expects all nodes under :content to be other tags (not strings).
   ;       They fail with the same exception with both parser impls.
   #_"<html><body bg=\"red\">This is <b>bold</b> test</body></html>"
   #_(str "<a h='1' i=\"2\" j='3'>"
          "  t1<b k=\"4\">t2</b>"
          "  t3<c>t4</c>"
          "  t5<d>t6</d>"
          "  t7<e l='5' m='6'>"
          "    t8<f>t10</f>t11</e>"
          "  t12<g>t13</g>t14"
          "</a>")
   ])

(deftest parse-test
  (doseq [input (concat aws-responses data-xml-inputs)]
    (let [data-xml-val (data.xml/parse-str input :namespace-aware false :skip-whitespace true)
          aws-api-val (util.xml/parse (StringReader. input))]
      (is (= (util/xml->map data-xml-val) (util/xml->map aws-api-val))
          (str "Different parsed value for: " (pr-str input))))))
