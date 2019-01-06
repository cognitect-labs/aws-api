;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.shape
  "Impl, don't call directly.

  Functions to leverage the shapes defined in the AWS API descriptions.

  Terminology:

  shape             A value parsed from the JSON API description that specifies the shape of the
                    input or output of an AWS operation.
  composite shape   A shape made of other shapes.
  instance          An instance of a shape.

  AWS defines 8 primitive shapes: string, timestamp, boolean, blob, integer, long, double, and float
  and 3 composite shapes: structure, list, and map.
  "
  (:refer-clojure :exclude [resolve])
  (:require [clojure.data.json :as json]
            [cognitect.aws.util :as util]))

;; ----------------------------------------------------------------------------------------
;; Helpers to navigate shapes
;; ----------------------------------------------------------------------------------------

(defn resolve-shape
  "Resolve the shape reference, `shape-ref`. Return the shape if found, otherwise nil.

  A shape reference is a map with the name of another shape under the :shape key.

  If the shape reference contains other keys, they will be added to the shape."
  [shapes shape-ref]
  (when-let [shape (get shapes (keyword (:shape shape-ref)) nil)]
    (merge shape (dissoc shape-ref :shape))))

(defn with-resolver
  "Resolve the shape reference and augment it with a resolver so you can call `resolve` on it."
  [{:keys [shapes] :as meta} shape-ref]
  (when-let [shape (resolve-shape shapes shape-ref)]
    (with-meta shape meta)))

(defn resolve
  "Resolve the shape reference."
  [shape shape-ref]
  (assert (:shapes (meta shape)))
  (with-resolver (meta shape) shape-ref))

(defn key-shape
  "Resolve and return the maps' key shape."
  [shape]
  (resolve shape (:key shape)))

(defn value-shape
  "Resolve and return the map's value shape."
  [shape]
  (resolve shape (:value shape)))

(defn member-shape
  "Resolve and return the member shape."
  [shape k]
  (resolve shape (get-in shape [:members k])))

(defn list-member-shape
  "Resolve and return the list member shape."
  [shape]
  (resolve shape (:member shape)))

(defmulti walk
  "Walk a nested instance structure in parallel with the shape structure
  and calls (f shape data) for each primitive shape.

  operation can be one of :serialize or :parse, and is necessary because
  locationName works differently when parsing than when serializing.
  "
  (fn [shape data operation f] (:type shape)))

(defmethod walk :default
  [shape data operation f]
  (f shape data))

(defmethod walk "structure"
  [shape data operation f]
  (when data
    (if (= :serialize operation)
      ;; when serializing, we iterate over the data and use locationName
      ;; as the key in the output
      (reduce-kv (fn [m k v]
                   (if-let [member-shape (member-shape shape k)]
                     (assoc m
                            (or (keyword (:locationName member-shape))
                                k)
                            (walk member-shape v operation f))
                     m))
                 {}
                 data)
      ;; when parsing, we iterate over the spec and use locationName
      ;; as the key to get data from the input
      (reduce (fn [m k]
                (let [member-shape (member-shape shape k)
                      location-name (or (keyword (:locationName member-shape)) k)]
                  (if (contains? data location-name)
                    (assoc m
                           k
                           (walk member-shape
                                 (get data location-name)
                                 operation
                                 f))
                    m)))
              {}
              (-> shape :members keys)))))

(defmethod walk "map"
  [shape data operation f]
  (when data
    (let [key-shape (key-shape shape)
          value-shape (value-shape shape)]
      (reduce-kv (fn [m k v]
                   (assoc m (walk key-shape k operation f) (walk value-shape v operation f)))
                 {}
                 data))))

(defmethod walk "list"
  [shape data operation f]
  (when data
    (let [member-shape (list-member-shape shape)]
      (mapv #(walk member-shape % operation f)
            data))))

;; ----------------------------------------------------------------------------------------
;; JSON Parser & Serializer
;; ----------------------------------------------------------------------------------------

(declare json-parse* json-serialize*)

(defn json-parse
  "Parse the JSON string to return an instance of the shape."
  [shape s]
  (if shape
    (walk shape (json/read-str s :key-fn keyword) :parse json-parse*)
    {}))


(defn json-serialize
  "Serialize the shape's instance into a JSON string."
  [shape instance]
  (json/write-str
   (walk shape instance :serialize json-serialize*)))

(defmulti json-parse*
  (fn [shape data] (:type shape)))

(defmethod json-parse* :default
  [_ data]
  data)

(defmethod json-parse* "blob"
  [_ data]
  (util/base64-decode data))

(defmulti json-serialize*
  (fn [shape instance] (:type shape)))

(defmethod json-serialize* :default
  [shape data]
  data)

(defmethod json-serialize* "blob"
  [_ data]
  (util/base64-encode data))

(defmethod json-serialize* "timestamp"
  [_ data]
  (long (/ (.getTime data) 1000)))

;; ----------------------------------------------------------------------------------------
;; XML Parser & Serializer
;; ----------------------------------------------------------------------------------------

(declare xml-parse* xml-serialize*)

;; TODO: ResponseMetadata in root
(defn xml-parse
  "Parse the XML string and return an instance of the shape."
  [shape s]
  (let [root (util/xml-read s)]
    (if (:resultWrapper shape)
      (xml-parse* shape (:content root))
      (xml-parse* shape [root]))))

(defn xml-serialize
  "Serialize the shape's instance into a XML string.
  el-name is the name of the root element."
  [shape instance el-name]
  (with-out-str
    (util/xml-write (xml-serialize* shape instance el-name))))

(defmulti xml-serialize*
  (fn [shape args el-name] (:type shape)))

(defmethod xml-serialize* :default
  [shape args el-name]
  {:tag el-name
   :content [(str args)]})

(defmethod xml-serialize* "boolean"
  [_ args el-name]
  {:tag el-name
   :content [(if args "true" "false")]})

(defmethod xml-serialize* "blob"
  [_ args el-name]
  {:tag el-name
   :content [(util/base64-encode args)]})

(defmethod xml-serialize* "timestamp"
  [_ args el-name]
  {:tag el-name
   :content [(util/format-timestamp util/iso8601-date-format args)]})

(defmethod xml-serialize* "structure"
  [shape args el-name]
  (reduce-kv (fn [node k v]
               (if (and (not (nil? v)) (contains? (:members shape) k))
                 (let [member-shape (member-shape shape k)
                       member-name (get member-shape :locationName (name k))]
                   (if (:xmlAttribute member-shape)
                     (assoc-in node [:attrs member-name] v)
                     (let [member (xml-serialize* member-shape v member-name)]
                       (update node :content
                               (if (vector? member) concat conj) ; to support flattened list
                               member))))
                 node))
             {:tag el-name
              :attrs (if-let [{:keys [prefix uri]} (:xmlNamespace shape)]
                       {(str "xmlns" (when prefix (str ":" prefix))) uri}
                       {})
              :content []}
             args))

(defmethod xml-serialize* "list"
  [shape args el-name]
  (let [member-shape (list-member-shape shape)]
    (if (:flattened shape)
      (mapv #(xml-serialize* member-shape % el-name) args)
      (let [member-name (get member-shape :locationName"member")]
        {:tag el-name
         :content (mapv #(xml-serialize* member-shape % member-name) args)}))))

(defmethod xml-serialize* "map"
  [shape args el-name]
  (let [key-shape (key-shape shape)
        key-name (get key-shape :locationName "key")
        value-shape (value-shape shape)
        value-name (get value-shape :locationName "value")]
    {:tag el-name
     :content (reduce-kv (fn [serialized k v]
                           (conj serialized {:tag "entry"
                                             :content [(xml-serialize* key-shape (name k) key-name)
                                                       (xml-serialize* value-shape v value-name)]}))
                         []
                         args)}))

(defmulti xml-parse*
  (fn [shape nodes] (:type shape)))

(defn- text-node? [nodes]
  (and (= 1 (count nodes))
       (string? (first (:content (first nodes))))))

(defn- only [coll]
  (assert (= 1 (count coll))
          (str "Expected (= 1 (count coll)), got " coll))
  (first coll))

(defmethod xml-parse* "structure"
  [shape nodes]
  (if (text-node? nodes)
    (let [member-key (->> shape :members only key)]
      {member-key (xml-parse* (member-shape shape member-key) nodes)})
    (let [data          (first nodes)
          tag->children (group-by :tag (:content data))]
      (reduce-kv (fn [parsed member-key member-shape-ref]
                   (let [member-shape (member-shape shape member-key)]
                     (if (contains? member-shape :location)
                       ;; Skip non-payload attributes
                       parsed
                       (let [member-name (keyword (or (when (:flattened member-shape)
                                                        (get (list-member-shape member-shape) :locationName))
                                                      (get member-shape :locationName (name member-key))))]
                         (cond
                           ;; The member's value is in the attributes of the current XML element.
                           (:xmlAttribute member-shape)
                           (assoc parsed member-key (get (:attrs data) member-name))


                           ;; The member's value is a child node(s).
                           (contains? tag->children member-name)
                           (assoc parsed member-key (xml-parse* member-shape (tag->children member-name)))

                           :else
                           parsed)))))
                 {}
                 (:members shape)))))

;;   Normal map:
;;
;;    <Map>
;;      <entry>
;;        <key>foo</key>
;;        <value>bar</value>
;;      </entry>
;;      <entry>
;;        <key>bar</key>
;;        <value>baz</value>
;;      </entry>
;;    </Map>
;;
;;  Flattened map:
;;
;;    <Map>
;;      <key>foo</key>
;;      <value>bar</value>
;;    </Map>
;;    <Map>
;;      <key>bar</key>
;;      <value>baz</value>
;;    </Map>

(defmethod xml-parse* "map"
  [shape nodes]
  (let [key-shape (key-shape shape)
        key-name (get key-shape :locationName "key")
        value-shape (value-shape shape)
        value-name (get value-shape :locationName "value")
        entries (if (:flattened shape)
                  nodes
                  (:content (first nodes)))]
    (reduce (fn [parsed entry]
              (let [tag->children (group-by :tag (:content entry))]
                (assoc parsed
                  (keyword (xml-parse* key-shape (tag->children (keyword key-name))))
                  (xml-parse* value-shape (tag->children (keyword value-name))))))
            {}
            entries)))

;;  Normal list:
;;
;;    <List>
;;      <member>foo</member>
;;      <member>bar</member>
;;    </List>
;;
;;  Flattened list:
;;
;;    <ListMember>foo</ListMember>
;;    <ListMember>bar</ListMember>

(defmethod xml-parse* "list"
  [shape nodes]
  (let [member-shape (list-member-shape shape)
        member-name (get member-shape :locationName "member")
        members (if (:flattened shape)
                  nodes
                  (:content (first nodes)))]
    (mapv #(xml-parse* member-shape [%])
          members)))

(defn data
  [nodes]
  (-> nodes first :content first))

;; TODO (dchelimsky 2017-04-22) validate enum membership?
(defmethod xml-parse* "string"    [_ nodes] (or (data nodes) ""))
(defmethod xml-parse* "character" [_ nodes] (or (data nodes) ""))
(defmethod xml-parse* "boolean"   [_ nodes] (= (data nodes) "true"))
(defmethod xml-parse* "double"    [_ nodes] (Double. (data nodes)))
(defmethod xml-parse* "float"     [_ nodes] (Double. (data nodes)))
(defmethod xml-parse* "long"      [_ nodes] (Long. (data nodes)))
(defmethod xml-parse* "integer"   [_ nodes] (Long. (data nodes)))
(defmethod xml-parse* "blob"      [_ nodes] (util/base64-decode (data nodes)))
(defmethod xml-parse* "timestamp"
  [_ nodes]
  (let [ts (data nodes)]
    (try
      (util/parse-date util/iso8601-date-format ts)
      (catch java.text.ParseException pe
        ;; S3 has msecs in its dates, but the conformance tests don't
        (util/parse-date util/iso8601-msecs-date-format ts)))))
