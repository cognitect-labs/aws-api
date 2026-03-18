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
  [attr-ns attr-local-name]
  (keyword (when-not (str/blank? attr-ns)
             ; For compatibility with clojure.data.xml
             (URLEncoder/encode (str "xmlns." attr-ns) "UTF-8"))
           attr-local-name))

(defn- read-element
  [^XMLStreamReader rdr]
  (let [name (.getLocalName rdr)
        nattrs (.getAttributeCount rdr)]
    {:tag   (keyword name)
     :attrs (loop [i 0 attrs {}]
              (if (< i nattrs)
                (recur (inc i)
                       (assoc attrs
                         (attr-key (.getAttributeNamespace rdr i) (.getAttributeLocalName rdr i))
                         (.getAttributeValue rdr i)))
                attrs))}))

(defn- xml->map
  [^XMLStreamReader rdr]
  (loop [stack nil content []]
    (if (.hasNext rdr)
      (let [tok (.next rdr)
            tok (consts tok tok)]
        (case tok
          :el/start (recur (conj stack content (read-element rdr)) [])
          :el/end (let [[el pcontent & stack] stack]
                    (recur stack
                           (conj pcontent (cond-> el
                                                  (pos? (count content))
                                                  (assoc :content content)))))

          :chars (recur stack
                        (if (.isWhiteSpace rdr)
                          content
                          (conj content (.getText rdr))))
          :doc/end
          (nth content 0)

          (recur stack content)))
      (throw (IllegalStateException. "unreachable")))))

(defn parse
  [^java.io.Reader rdr]
  (let [^XMLInputFactory factory @factory]
    (xml->map (.createXMLStreamReader factory rdr))))
