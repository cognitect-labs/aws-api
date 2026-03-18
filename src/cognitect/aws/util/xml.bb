(ns cognitect.aws.util.xml
  "For internal use. Do not call directly.

  Babashka compatibility layer. Required because javax.xml.stream classes
  fail to be resolved."
  (:require [clojure.data.xml :as xml]))

(defn parse
  [^java.io.Reader rdr]
  (xml/parse rdr :namespace-aware false :skip-whitespace true))
