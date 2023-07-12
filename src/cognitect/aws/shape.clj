;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns ^:skip-wiki cognitect.aws.shape
  "Impl, don't call directly.

   Functions to leverage the shapes defined in the AWS API descriptions.

   Terminology:

   shape        A map parsed from a JSON Object in a service descriptor that specifies
                the shape of the input or output of an AWS operation. AWS defines 8 primitive
                shapes (string, timestamp, boolean, blob, integer, long, double, and float) and
                3 composite shapes (structure, list, and map). Composites contain shape-refs
                for their members.

                  * map shape contains :key and :value shapes
                  * structure shape contains :members shapes
                  * list shape contains :member shape

   shape-ref    A map with a :shape key (String value) that names a shape. Structural shapes
                like list, map, and structure, include shape-refs to refer to the other shapes
                that represent the member shapes."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.data.json :as json]
            [cognitect.aws.util :as util]))

(set! *warn-on-reflection* true)

;; ----------------------------------------------------------------------------------------
;; Helpers to navigate shapes
;; ----------------------------------------------------------------------------------------

(defn resolve
  "Given a `service` and a map with a `:shape` key (`shape-ref`), looks up the referenced shape
   in the :shapes of the service, adding any additional keys that appear on the shape-ref.

   Returns nil if the shape cannot be found in the service."
  [service shape-ref]
  (when-let [shape (get (:shapes service) (keyword (:shape shape-ref)))]
    (merge shape (dissoc shape-ref :shape))))

(def map-key-shape-ref
  "Given a map shape, returns the shape-ref for its keys."
  :key)

(def map-value-shape-ref
  "Given a map shape, returns the shape-ref for its values."
  :value)

(defn structure-member-shape-ref
  "Given a structure shape and a key, `k`, returns the shape-ref for the `k` shape."
  [shape k] (get-in shape [:members k]))

(def list-member-shape-ref
  "Given a list shape, returns the shape-ref for its members."
  :member)

(defn format-date
  ([shape data]
   (format-date shape data util/format-timestamp))
  ([shape data default-format-fn]
   (condp = (:timestampFormat shape)
     "rfc822"        (util/format-date util/rfc822-date-format data)
     "iso8601"       (util/format-date util/iso8601-date-format data)
     "unixTimestamp" (util/format-timestamp data)
     (default-format-fn data))))

(defn- parse-date* [d & formats]
  (->> formats
       (map #(try (util/parse-date % d) (catch java.text.ParseException _ nil)))
       (filter identity)
       first))

(defn parse-date
  [{:keys [timestampFormat]} data]
  (when data
    (cond (= "rfc822" timestampFormat)
          (parse-date* data util/rfc822-date-format)
          (= "iso8601" timestampFormat)
          (parse-date* data
                       util/iso8601-date-format
                       util/iso8601-msecs-date-format)
          (int? data)
          (java.util.Date. (* 1000 ^int data))
          (double? data)
          (java.util.Date. (* 1000 (long data)))
          (re-matches #"^\d+$" data)
          (java.util.Date. (* 1000 (long (read-string data))))
          :else
          (parse-date* data
                       util/iso8601-date-format
                       util/iso8601-msecs-date-format
                       util/rfc822-date-format))))

;; ----------------------------------------------------------------------------------------
;; JSON helpers/handlers
;; ----------------------------------------------------------------------------------------

(defn handle-map [service shape data f]
  (when data
    (let [key-shape   (resolve service (map-key-shape-ref shape))
          value-shape (resolve service (map-value-shape-ref shape))]
      (reduce-kv (fn [m k v] (assoc m (f service key-shape k) (f service value-shape v)))
                 {}
                 data))))

(defn handle-list [service shape data f]
  (when data
    (mapv (partial f service (resolve service (list-member-shape-ref shape)))
          ;; sometimes the spec says list, but AWS sends a scalar
          (if (sequential? data) data [data]))))

;; ----------------------------------------------------------------------------------------
;; JSON Parser
;; ----------------------------------------------------------------------------------------

(defmulti json-parse*
  (fn [_service shape _data] (:type shape)))

(defmethod json-parse* :default [_service _shape data] data)

(defmethod json-parse* "structure"
  [service shape data]
  (when data
    (if (:document shape)
      data
      (reduce (fn [m k]
                (let [member-shape (resolve service (structure-member-shape-ref shape k))
                      location-name (or (keyword (:locationName member-shape)) k)]
                  (if (contains? data location-name)
                    (assoc m k (json-parse* service member-shape (get data location-name)))
                    m)))
              {}
              (-> shape :members keys)))))

(defmethod json-parse* "map"
  [service shape data]
  (handle-map service shape data json-parse*))

(defmethod json-parse* "list"
  [service shape data]
  (handle-list service shape data json-parse*))

(defmethod json-parse* "blob" [_service _shape data] (util/base64-decode data))
(defmethod json-parse* "timestamp" [_service shape data] (parse-date shape data))

;; ----------------------------------------------------------------------------------------
;; JSON Serializer
;; ----------------------------------------------------------------------------------------

(defmulti json-serialize* (fn [_service shape _data] (:type shape)))

(defn json-serialize
  "Serialize the shape's data into a JSON string."
  [service shape data]
  (json/write-str (json-serialize* service shape data)))

(defn json-parse
  "Parse the JSON string to return an instance of the shape."
  [service shape s]
  (json-parse* service shape (json/read-str s :key-fn keyword)))

(defmethod json-serialize* :default
  [_service _shape data] data)

(defmethod json-serialize* "structure"
  [service shape data]
  (when data
    (reduce-kv (fn [m k v]
                 (if-let [member-shape (resolve service (structure-member-shape-ref shape k))]
                   (assoc m
                          (or (keyword (:locationName member-shape))
                              k)
                          (json-serialize* service member-shape v))
                   m))
               {}
               data)))

(defmethod json-serialize* "map"
  [service shape data]
  (handle-map service shape data json-serialize*))

(defmethod json-serialize* "list"
  [service shape data]
  (handle-list service shape data json-serialize*))

(defmethod json-serialize* "blob"
  [_service _shape data]
  (util/base64-encode data))

(defmethod json-serialize* "timestamp"
  [_service shape data]
  (format-date shape data (comp read-string util/format-timestamp)))

;; ----------------------------------------------------------------------------------------
;; XML Parser
;; ----------------------------------------------------------------------------------------

(defmulti xml-parse*
  (fn [_service shape _nodes] (:type shape)))

;; TODO: ResponseMetadata in root
(defn xml-parse
  "Parse the XML string and return data representing of the shape."
  [service shape s]
  (let [root (util/xml-read s)]
    (if (:resultWrapper shape)
      (xml-parse* service shape (:content root))
      (xml-parse* service shape [root]))))

(defmethod xml-parse* "structure"
  [service shape nodes]
  (let [data          (first nodes)
        tag->children (group-by :tag (:content data))]
    (reduce-kv (fn [parsed member-key _]
                 (let [member-shape (resolve service (structure-member-shape-ref shape member-key))]
                   (if (contains? member-shape :location)
                     ;; Skip non-payload attributes
                     parsed
                     (let [member-name (keyword (or (when (:flattened member-shape)
                                                      (get (resolve service (list-member-shape-ref member-shape)) :locationName))
                                                    (get member-shape :locationName (name member-key))))]
                       (cond
                         ;; The member's value is in the attributes of the current XML element.
                         (:xmlAttribute member-shape)
                         (assoc parsed member-key (get (:attrs data) member-name))

                         ;; The member's value is a child node(s).
                         (contains? tag->children member-name)
                         (assoc parsed member-key (xml-parse* service member-shape (tag->children member-name)))

                         ;; Content is a single text node
                         (and (= 1 (count (:members shape)))
                              (= "string" (:type member-shape)))
                         (assoc parsed member-key (xml-parse* service member-shape nodes))

                         :else
                         parsed)))))
               {}
               (:members shape))))

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
  [service shape nodes]
  (let [key-shape (resolve service (map-key-shape-ref shape))
        key-name (get key-shape :locationName "key")
        value-shape (resolve service (map-value-shape-ref shape))
        value-name (get value-shape :locationName "value")
        entries (if (:flattened shape)
                  nodes
                  (:content (first nodes)))]
    (reduce (fn [parsed entry]
              (let [tag->children (group-by :tag (:content entry))]
                (assoc parsed
                       (xml-parse* service key-shape (tag->children (keyword key-name)))
                       (xml-parse* service value-shape (tag->children (keyword value-name))))))
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
  [service shape nodes]
  (let [member-shape (resolve service (list-member-shape-ref shape))
        members (if (:flattened shape)
                  nodes
                  (:content (first nodes)))]
    (mapv #(xml-parse* service member-shape [%])
          members)))

(defn ^:private content
  [nodes]
  (-> nodes first :content first))

(defmethod xml-parse* "string"    [_service _shape nodes] (or (content nodes) ""))
(defmethod xml-parse* "character" [_service _shape nodes] (or (content nodes) ""))
(defmethod xml-parse* "boolean"   [_service _shape nodes] (= "true" (content nodes)))
(defmethod xml-parse* "double"    [_service _shape nodes] (Double/parseDouble ^String (content nodes)))
(defmethod xml-parse* "float"     [_service _shape nodes] (Double/parseDouble ^String (content nodes)))
(defmethod xml-parse* "long"      [_service _shape nodes] (Long/parseLong ^String (content nodes)))
(defmethod xml-parse* "integer"   [_service _shape nodes] (Long/parseLong ^String (content nodes)))
(defmethod xml-parse* "blob"      [_service _shape nodes] (util/base64-decode (content nodes)))
(defmethod xml-parse* "timestamp" [_service shape nodes] (parse-date shape (content nodes)))

;; ----------------------------------------------------------------------------------------
;; XML Serializer
;; ----------------------------------------------------------------------------------------

(defmulti xml-serialize*
  (fn [_service shape _args _el-name] (:type shape)))

(defn xml-serialize
  "Serialize the shape's data into a XML string.
  el-name is the name of the root element."
  [service shape data el-name]
  (with-out-str
    (util/xml-write (xml-serialize* service shape data el-name))))

(defmethod xml-serialize* :default
  [_service _shape args el-name]
  {:tag el-name
   :content [(str args)]})

(defmethod xml-serialize* "boolean"
  [_service _shape args el-name]
  {:tag el-name
   :content [(if args "true" "false")]})

(defmethod xml-serialize* "blob"
  [_service _shape args el-name]
  {:tag el-name
   :content [(util/base64-encode args)]})

(defmethod xml-serialize* "timestamp"
  [_service shape args el-name]
  {:tag el-name
   :content [(format-date shape args (partial util/format-date util/iso8601-date-format))]})

(defmethod xml-serialize* "structure"
  [service shape args el-name]
  (reduce-kv (fn [node k v]
               (if (and (not (nil? v)) (contains? (:members shape) k))
                 (let [member-shape (resolve service (structure-member-shape-ref shape k))
                       member-name (get member-shape :locationName (name k))]
                   (if (:xmlAttribute member-shape)
                     (assoc-in node [:attrs member-name] v)
                     (let [member (xml-serialize* service member-shape v member-name)]
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
  [service shape args el-name]
  (let [member-shape (resolve service (list-member-shape-ref shape))]
    (if (:flattened shape)
      (mapv #(xml-serialize* service member-shape % el-name) args)
      (let [member-name (get member-shape :locationName "member")]
        {:tag el-name
         :content (mapv #(xml-serialize* service member-shape % member-name) args)}))))

(defmethod xml-serialize* "map"
  [service shape args el-name]
  (let [key-shape (resolve service (map-key-shape-ref shape))
        key-name (get key-shape :locationName "key")
        value-shape (resolve service (map-value-shape-ref shape))
        value-name (get value-shape :locationName "value")]
    {:tag el-name
     :content (reduce-kv (fn [serialized k v]
                           (conj serialized {:tag "entry"
                                             :content [(xml-serialize* service key-shape (name k) key-name)
                                                       (xml-serialize* service value-shape v value-name)]}))
                         []
                         args)}))
