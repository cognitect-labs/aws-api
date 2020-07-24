(ns cognitect.aws.flow.demand
  (:require [cognitect.aws.service :as service]
            [cognitect.aws.dynaload :as dynaload]))


(defn load-service [{:keys [api-key output]}
                    context
                    flow]
  (let [service (service/service-description (name (get context api-key)))]
    (dynaload/load-ns (symbol (str "cognitect.aws.protocols." (get-in service [:metadata :protocol]))))
    (assoc flow output service)))

(comment
  (load-service {:api-key :api
                 :output  :service}
                {:api :s3}
                {})
  )

(def steps
  {load-service {:api-key :api
                 :output  :service}})

(defn find-step [steps goal]
  (->> steps
       (filter (fn [[k v]] (= goal (:output v))))
       first
       ((fn [[k v]] {:step k :args v}))))

(comment
  (find-step steps :service)
  )

(defn run [steps goal context flow]
  (let [{:keys [step args]} (find-step steps goal)]
    (step args context flow)))

(comment
  (run steps :service {:api :s3} {}))
