(ns cfn
  (:require [cognitect.aws.client.api :as aws]
            [clojure.set :as set]
            [clojure.java.browse :as browse]
            [clojure.string :as str]))

(defn get-client [{:as args}]
  (aws/client (merge {:api :cloudformation} (select-keys args [:region]))))

(defn ->Tags
  [tag-map]
  (into []
    (map (fn [[k v]]
           {:Key   (if (keyword? k)
                     (subs (str k) 1)
                     k)
            :Value (str v)}))
    tag-map))

;; https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_Parameter.html
(defn ->Parameters
  [param-map]
  (into []
    (map (fn [[k v]]
           (let [base {:ParameterKey (name k)}]
             ;; this allows a map value if you want to specify
             ;;:UsePreviousValue/:ResolvedValue
             (if (map? v)
               (merge base v)
               (assoc base :ParameterValue (str v))))
           ))
    param-map))

(def ->Capability
  {:iam         "CAPABILITY_IAM"
   :named-iam   "CAPABILITY_NAMED_IAM"
   :auto-expand "CAPABILITY_AUTO_EXPAND"})

(def ->capability (set/map-invert ->Capability))

(defn ->Capabilities [capabilities]
  (map ->Capability capabilities))

(defn awsify-args [{:keys [tags parameters capabilities stack-name template-url]}]
  (cond-> {:StackName stack-name :TemplateURL template-url}
    tags         (assoc :Tags (->Tags tags))
    parameters   (assoc :Parameters (->Parameters parameters))
    capabilities (assoc :Capabilities (->Capabilities capabilities))))

;;; should stack be first arg?
(defn create-stack [{:keys [stack-name template-url] :as args}]
  {:pre [stack-name template-url]}
  (let [request (awsify-args args)
        resp    (aws/invoke (get-client args)
                  {:op      :CreateStack
                   :request request})]
    resp))


;;; templateurl probably belongs with stackname?
(defn update-stack [{:keys [stack-name template-url] :as args}]
  (aws/invoke (get-client args)
    {:op      :UpdateStack
     :request (awsify-args args)}))


(defn delete-stack [{:keys [stack-name] :as args}]
  (aws/invoke (get-client args)
    {:op      :DeleteStack
     :request {:StackName stack-name}}))

(defn browse-stack [{:keys [stack-name region]}]
  (let [region (or region
                   (cognitect.aws.region/fetch (cognitect.aws.client.shared/region-provider))
                   (assert false "Must specify region"))]
    (browse/browse-url
      (format
        "https://%s.console.aws.amazon.com/cloudformation/home?region=%s#/stacks/stackinfo/?stackId=%s"
        (name region)
        (name region)
        (name stack-name)))))

(defn quick-create
  "Opens a quick-create cloudformation url in a browser to launch a stack for
  the given template"
  [{:keys [stack-name template-url params region]}]
  (let [region (or region
                   (cognitect.aws.region/fetch (cognitect.aws.client.shared/region-provider)))
        url    (cond-> (format
                      (str "https://%s.console.aws.amazon.com/"
                           "cloudformation/home?region=%s#/stacks/create/review?stackName=%s&templateURL=%s")
                      (name region)
                      (name region)
                      (name stack-name)
                      template-url)
                 params (str "&"
                             (->> params
                                  (map (fn [[k v]]
                                         (str "param_" (name k) "=" (java.net.URLEncoder/encode v "UTF-8"))))
                                  (str/join "&"))))]
    (browse/browse-url url)))


(defn unawsify [{:keys [Tags Parameters Outputs Capabilities StackName] :as m}]
  (let [f (fn [k v xs] (into {} (map (juxt (comp keyword k) v)) xs))
        m (-> (dissoc m :Parameters :Outputs :Tags :StackName :Capabilities)
              (assoc :stack-name StackName))]
    (cond-> m
      Tags         (assoc :tags (f :Key :Value Tags))
      Parameters   (assoc :parameters (f :ParameterKey :ParameterValue Parameters))
      Outputs      (assoc :outputs (f :OutputKey :OutputValue Outputs))
      Capabilities (assoc :capabilities (mapv ->capability Capabilities)))))


;; todo: shouldn't throw if empty maybe just not found? or nil?
(defn describe-stack [{:keys [stack-name] :as args}]
  (-> (get-client args)
      (aws/invoke {:op      :DescribeStacks
                   :request {:StackName stack-name}})
      
      :Stacks
      (as-> x (do (assert (= 1 (count x))) (first x)))
      unawsify))

(defn describe-resources [{:keys [stack-name] :as args}]
  (-> (get-client args)
      (aws/invoke {:op      :DescribeStackResources
                   :request {:StackName stack-name}})
      :StackResources))


