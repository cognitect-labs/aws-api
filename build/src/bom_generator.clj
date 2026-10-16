(ns bom-generator
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.time Clock ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(defn- ->dependencies
  [latest-releases]
  (let [indent "      "
        sb (StringBuilder.)]
    (doseq [[artifact-coordinate info] latest-releases]
      (.append sb indent)
      (.append sb "<dependency>\n")
      (.append sb indent)
      (.append sb "  <groupId>")
      (.append sb (namespace artifact-coordinate))
      (.append sb "</groupId>\n")
      (.append sb indent)
      (.append sb "  <artifactId>")
      (.append sb (name artifact-coordinate))
      (.append sb "</artifactId>\n")
      (.append sb indent)
      (.append sb "  <version>")
      (.append sb (:mvn/version info))
      (.append sb "</version>\n")
      (.append sb indent)
      (.append sb "</dependency>\n"))
    (.toString sb)))

(defn xml-escape [^String s]
  (-> s
      (.replace "&" "&amp;")
      (.replace "<", "&lt;")
      (.replace ">", "&gt;")))

(defn gen-bom
  [bom-coordinate
   bom-version
   bom-name
   latest-releases]
  (let [bom-group-id (namespace bom-coordinate)
        bom-artifact-id (name bom-coordinate)]
    (str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
    xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion>
  <groupId>" bom-group-id "</groupId>
  <artifactId>" bom-artifact-id "</artifactId>
  <version>" bom-version "</version>
  <packaging>pom</packaging>
  <name>" (xml-escape bom-name) "</name>
  <dependencyManagement>
    <dependencies>
"
       (->dependencies latest-releases)
"    </dependencies>
  </dependencyManagement>
  <build>
    <plugins>
      <plugin>
        <groupId>org.sonatype.central</groupId>
        <artifactId>central-publishing-maven-plugin</artifactId>
        <version>0.12.0</version>
        <extensions>true</extensions>
        <configuration>
          <publishingServerId>central</publishingServerId>
          <autoPublish>true</autoPublish>
          <waitUntil>published</waitUntil>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-gpg-plugin</artifactId>
        <version>3.2.8</version>
        <executions>
          <execution>
            <id>sign-artifacts</id>
            <phase>verify</phase>
            <goals>
              <goal>sign</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
")))

(defn now-utc-str []
  (.format
   (DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
   (ZonedDateTime/now (Clock/systemUTC))))

(defn create-bom!
  [input-file bom-coordinate bom-name]
  (let [bom-version     (now-utc-str)
        latest-releases (edn/read-string (slurp input-file))
        dest-file       (io/file "target" (name bom-coordinate) "pom.xml")]
    (io/make-parents dest-file)
    (spit dest-file (gen-bom bom-coordinate bom-version bom-name latest-releases))
    dest-file))

(defn -main [& args]
  (when-not (= 3 (count args))
    (println "Usage: bom-generator <input-file> <bom-coordinate> <bom-name>")
    (System/exit 1))

  (let [[input-file bom-coordinate bom-name] args
        output-file (create-bom! input-file (symbol bom-coordinate) bom-name)]
    (println (str output-file))))

(comment
 (-main "latest-releases.edn" "com.cognitect.aws/api-bom" "aws-api Bill of Materials"))
