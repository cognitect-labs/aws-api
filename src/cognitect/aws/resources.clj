(ns ^:skip-wiki cognitect.aws.resources
  "Impl, don't call directly."
  (:import (clojure.lang RT)))

(def loader
  "Clojure's base class loader, used to load all resources.

  This ensures the same class loader is used, no matter which thread invokes `resource`.
  This is needed to guard against the scenario where the application is running in an
  environment that uses a non-default class loader, and the `resource` function is invoked
  from a thread that does not inherit the custom class loader (e.g. ForkJoinPool/commonPool threads).

  See https://github.com/cognitect-labs/aws-api/issues/265 for details"
  (RT/baseLoader))

(defn resource
  "Returns the URL for a named resource, always using Clojure's base class loader."
  [n]
  (.getResource ^ClassLoader loader n))
