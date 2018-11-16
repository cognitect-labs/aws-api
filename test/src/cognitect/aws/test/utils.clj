(ns cognitect.aws.test.utils)

(defn stub-getenv [env]
  (fn
    ([] env)
    ([k] (get env k))))

(defn stub-getProperty [props]
  (fn [k]
    (get props k)))
