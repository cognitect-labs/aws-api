(ns ^:skip-wiki cognitect.aws.dynaload)

(defonce ^:private dynalock (Object.))

(defn load-ns [ns]
  (locking dynalock
    (require (symbol ns))))

(defn load-var
  [s]
  (let [ns (namespace s)]
    (assert ns)
    (load-ns ns)
    (or (resolve s)
        (throw (RuntimeException. (str "Var " s " is not on the classpath"))))))
