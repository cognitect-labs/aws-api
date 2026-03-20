(ns cognitect.aws.util.xml
  "For internal use. Do not call directly.

  Parse XML strings into Clojure maps. Simplified version of clojure.data.xml/parse,
  expected to be backwards compatible.

  Authors: Ghadi Shayban, Marco Biscaro"
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)
           (javax.xml.stream XMLInputFactory
                             XMLStreamConstants
                             XMLStreamReader)))

(set! *warn-on-reflection* true)

(def ^:private factory
  (delay (doto (XMLInputFactory/newInstance)
           (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
           (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
           (.setProperty XMLInputFactory/IS_COALESCING true))))

(def ^:private consts
  {XMLStreamConstants/END_DOCUMENT  :doc/end
   XMLStreamConstants/START_ELEMENT :el/start
   XMLStreamConstants/END_ELEMENT   :el/end
   XMLStreamConstants/CHARACTERS    :chars})

(defn- attr-key
  [^XMLStreamReader rdr i]
  (let [attr-ns (.getAttributeNamespace rdr i)
        attr-local-name (.getAttributeLocalName rdr i)]
    (keyword (when-not (str/blank? attr-ns)
               ; For compatibility with clojure.data.xml
               (URLEncoder/encode (str "xmlns." attr-ns) "UTF-8"))
             attr-local-name)))

(defn- read-element
  [^XMLStreamReader rdr]
  (let [name (.getLocalName rdr)
        nattrs (.getAttributeCount rdr)]
    {:tag   (keyword name)
     :attrs (loop [i 0 attrs {}]
              (if (< i nattrs)
                (recur (inc i)
                       (assoc attrs
                         (attr-key rdr i)
                         (.getAttributeValue rdr i)))
                attrs))}))

(defn- xml->map
  [^XMLStreamReader rdr]
  ;; stack is interleaved: coll, element-info, coll, element-info...
  (loop [stack nil content []]
    (if (.hasNext rdr)
      (let [tok (.next rdr)
            tok (consts tok tok)]
        (case tok
          ;; when an element nests, push current coll and nested element info
          :el/start (recur (conj stack content (read-element rdr)) [])
          ;; pop element info and parent coll, add element to parent coll
          :el/end (let [[el pcontent & stack] stack]
                    (recur stack (conj pcontent (assoc el :content content))))
          :chars (recur stack (cond-> content (not (.isWhiteSpace rdr)) (conj (.getText rdr))))
          :doc/end (nth content 0)
          ;; skip other types (e.g. comment, ignorable space, dtd, cdata)
          (recur stack content)))
      (throw (IllegalStateException. "unreachable")))))

(defn parse
  [^java.io.Reader rdr]
  (let [^XMLInputFactory factory @factory]
    (xml->map (.createXMLStreamReader factory rdr))))
