(ns cognitect.aws.http.default
  (:require [cognitect.aws.dynaload :as dynaload]))

(def ^:private native-client-available
  (delay (try (Class/forName "java.net.http.HttpClient") true
              (catch Exception _ false))))

(def ^:private cognitect-client-available
  (delay (try (require 'cognitect.http-client) true
              (catch Exception _ false))))

(defn ^:private load-and-create
  [sym]
  (let [f @(dynaload/load-var sym)]
    (f)))

(defn create
  "Returns a new cognitect.aws.http/HttpClient.

  - If running JVM is JDK 11+, returns a client based on java.net.http.HttpClient.
  - If cognitect.http-client ns is available, returns a client based on it.

  If none of these requirements are met, throws."
  []
  (cond
    @native-client-available
    (load-and-create 'cognitect.aws.http.java/create)

    @cognitect-client-available
    (load-and-create 'cognitect.aws.http.cognitect/create)

    :else
    (throw (ex-info "aws-api requires JDK 11+, or you must have an explicit dependency on com.cognitect/http-client in your project"
                    {:details "See https://github.com/cognitect-labs/aws-api/blob/main/UPGRADE.md if you are upgrading from a previous aws-api version"}))))
