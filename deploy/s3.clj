(ns s3
  (:require [cognitect.aws.client.api :as aws]
            [taoensso.timbre :as log]))

(defn get-client [args]
  (aws/client (merge {:api :s3} (select-keys args [:region]))))

(defn ?anom [x]
  (:cognitect.anomalies/category x))

(defn throw-on-anom [x]
  (if (?anom x)
    (throw (ex-info "failed." x))
    x))

(defn create-public-read-bucket [{:keys [bucket] :as args}]
  (throw-on-anom
    (aws/invoke (get-client args)
      {:op :CreateBucket :request
       {:Bucket bucket
        :ACL    "public-read"}})))

(defn create-private-bucket [{:keys [bucket] :as args}]
  (let [client (get-client args)]
    (throw-on-anom
      (aws/invoke client
        {:op :CreateBucket :request
         {:Bucket bucket
          :ACL    "private"}}))
    (throw-on-anom
      (aws/invoke client
        {:op :PutPublicAccessBlock
         :request
         {:Bucket bucket
          :PublicAccessBlockConfiguration
          {:BlockPublicAcls       true
           :IgnorePublicAcls      true
           :BlockPublicPolicy     true
           :RestrictPublicBuckets true}}}))))

(defn s3-url [bucket key]
  (str "https://" bucket ".s3.amazonaws.com/" key))

(defn exists?
  "Returns true if an object exists at `key` on s3 in the build bucket"
  [{:keys [bucket key region] :as args}]
  (let [req  {:Bucket bucket :Key key}
        resp (aws/invoke (get-client args) {:op :HeadObject :request req})]
    (if-let [anom (?anom resp)]
      (if (= anom :cognitect.anomalies/not-found)
        false
        (throw (ex-info "Error in s3 HeadObject request" {:req  req
                                                          :resp resp})))
      true)))

(defn upload-new [{:keys [bucket region key body extra-request-args] :as args}]
  {:pre [(or (bytes? body) (instance? java.io.InputStream body))]}
  (let [s3-client (get-client args)
        url       (s3-url bucket key)]
    (if (exists? args)
      url
      (let [_      (log/info "uploading to" url)
            result (aws/invoke s3-client
                     {:op      :PutObject
                      :request (merge
                                 {:Bucket bucket
                                  :Key    key
                                  :Body   body}
                                 extra-request-args)})]
        
        (if (?anom result)
          result
          url)))))
