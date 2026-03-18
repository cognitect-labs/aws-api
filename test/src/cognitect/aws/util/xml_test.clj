(ns cognitect.aws.util.xml-test
  (:require [clojure.data.xml :as data.xml]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cognitect.aws.util :as util]
            [cognitect.aws.util.xml :as util.xml])
  (:import (java.io StringReader)))

; Sampled from real S3 responses
(def aws-responses
  ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Owner><ID>02d6176db174dc93cb1b899f7c6078f08654445fe8cf1b6ce98d8855f66bdbf4</ID><DisplayName>minio</DisplayName></Owner><Buckets><Bucket><Name>aws-api-test-bucket-1773871449613-132</Name><CreationDate>2026-03-18T22:04:09.758Z</CreationDate></Bucket><Bucket><Name>aws-api-test-bucket-1773871603324-938</Name><CreationDate>2026-03-18T22:06:43.521Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>some-bucket</Name><Prefix></Prefix><KeyCount>1</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated><Contents><Key>some-key/</Key><LastModified>2026-01-13T17:47:19.000Z</LastModified><ETag>&quot;d41d8cd98f00b204e9800998ecf8427e&quot;</ETag><ChecksumAlgorithm>CRC64NVME</ChecksumAlgorithm><ChecksumType>FULL_OBJECT</ChecksumType><Size>0</Size><StorageClass>STANDARD</StorageClass></Contents></ListBucketResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Deleted><Key>hello.txt</Key></Deleted><Deleted><Key>hai.txt</Key></Deleted><Deleted><Key>oi.txt</Key></Deleted></DeleteResult>"
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"

   ; RequestId -> openssl rand -base64 12 | tr "[:lower:]" "[:upper:]"
   ; HostId -> openssl rand -base64 56
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
