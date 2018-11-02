;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns cognitect.aws.service
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cognitect.aws.shape :as shape]))

(def base-ns "cognitect.aws")

(def base-resource-path "cognitect/aws")

(defn descriptor-resource-path [service-name]
  (str base-resource-path "/" service-name "/service.edn"))

(defn descriptor-resource [service-name]
  (io/resource (descriptor-resource-path service-name)))

(defn read-service-description
  "Return service description readerable source (anything supported by
  clojure.java.io/reader)."
  [readerable]
  (clojure.edn/read-string (slurp readerable)))

(defn service-description [service-name]
  (if-let [resource (descriptor-resource service-name)]
    (read-service-description resource)
    (throw (ex-info (str "Cannot find resource " (descriptor-resource-path service-name) ".") {}))))

(defn shape
  "Returns the shape referred by `shape-ref`."
  [service shape-ref]
  (shape/with-resolver (select-keys service [:shapes]) shape-ref))

(defn endpoint-prefix
  [service]
  (get-in service [:metadata :endpointPrefix]))

(defn service-name [service]
  (-> service :metadata :uid
      (str/replace #"-\d{4}-\d{2}-\d{2}" "")
      (str/replace #"\s" "-")
      (str/replace #"\." "-")))

(defn ns-prefix
  "Returns the namespace prefix to use when looking up resources."
  [service]
  (format "%s.%s" base-ns (service-name service)))

(defn spec-ns
  "The namespace for specs for service."
  [service]
  (symbol (format "%s.specs" (ns-prefix service))))

(defn load-specs [service]
  (require (spec-ns service)))

(defonce svc-docs (atom {}))

(defn docs
  "Returns the docs for this service"
  [service]
  (load-specs service)
  (let [k (service-name service)]
    (if-let [doc (get @svc-docs k)]
      doc
      (-> (swap! svc-docs
                 assoc
                 k
                 (clojure.edn/read-string
                  (slurp
                   (io/resource (format "%s/%s/docs.edn" base-resource-path (service-name service))))))
          (get k)))))

(defn request-spec-key
  "Returns the key to look up in the spec registry for the spec for
  the request body of op."
  [service op]
  (load-specs service)
  (when-let [shape-key (some->> service :operations op :input :shape)]
    (keyword (ns-prefix service) shape-key)))

(defn response-spec-key
  "Returns the key to look up in the spec registry for the spec for
  the response body of op."
  [service op]
  (load-specs service)
  (when-let [shape-key (some->> service :operations op :output :shape)]
    (keyword (ns-prefix service) shape-key)))
