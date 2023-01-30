(ns rest
  (:require [clojure.data.json :as json]
            [convex.cell :as cell]
            [convex.clj :as clj]
            [convex.client :as client]
            [convex.key-pair :as kp]
            [java-http-clj.core :as http]
            [peer :as peer]
            [taoensso.timbre :as log]
            [ring.adapter.jetty :as jetty]
            [objection.core :as obj])
  (:import [convex.core.data AccountKey]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle [peer req]
  (try
    (let [json (-> req :body .readAllBytes String. json/read-str)]
      (case (:uri req)
        "/api/v1/createAccount"
        (let [pk          (-> json (get "accountKey") AccountKey/parse)
              new-address (.toLong (peer/invoke (cell/* (create-account ~pk)) peer))]
          {:status 200 :body (json/write-str {:address new-address})})
        "/api/v1/faucet"
        (let [{:strs [address amount]} json
              address                  (cell/address address)
              amount                   (cell/long amount)
              resp                     (clj/long (peer/invoke (cell/* (transfer ~address ~amount)) peer))]
          {:status 200 :body (json/write-str {:amount resp})})

        {:status 404}))
    (catch Throwable t
      (log/error t "Error in handle")
      {:status 404})))

(defn start-server [peer port]
  (obj/construct {:alias :rest-server}
    (jetty/run-jetty (fn [req] (handle peer req))
      {:port  port
       :join? false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-account [base-url account-key]
  (-> (http/post (str base-url "/createAccount")
        {:body    (json/write-str {"accountKey" (str account-key)})
         :timeout 4000})
      :body
      json/read-str
      (get "address")
      cell/address))

;;; TODO: add max amount?
(defn request-coin [base-url address amount]
  (-> (http/post (str base-url "/faucet")
        {:body    (json/write-str {"address" (.toLong address)
                                   "amount"  amount})
         :timeout 4000})
      :body
      json/read-str
      (get "amount")
      cell/long))

(defrecord RemoteFriend [host port rest-url]
  peer/IFriend
  (make-account [this account-key]
    (create-account rest-url account-key))
  (fund [this address amount]
    (request-coin rest-url address amount))
  (get-client [this]
    (client/connect {:convex.server/host host :convex.server/port port})))

(defn remote-friend [host peer-port rest-port]
  (map->RemoteFriend {:host host
                      :port peer-port
                      :rest-url (format "http://%s:%s/api/v1" host rest-port)}))
