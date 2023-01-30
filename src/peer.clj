(ns peer
  (:require [convex.client :as client]
            [convex.pfx :as pfx]
            [convex.server :as server]
            [convex.cell :as cell :refer [*] :rename {* cell}]
            [convex.key-pair :as kp]
            [convex.db :as db]
            [convex.cvm :as cvm]
            [convex.read :as read]

            [objection.core :as obj]
            [promesa.core :as p]

            [clojure.math :as math]
            [taoensso.timbre :as log]
            [clojure.java.io :as io])
  (:import convex.core.init.Init))

;;; TODO: reconsider db handling
(when-not (db/current) (db/current-set (db/open-tmp)))

(def copper-per-cvx 1000000000)
(defn ->copper [n] (* copper-per-cvx n))

(def default-stake 1000)

(extend-protocol obj/IAutoStoppable

  org.eclipse.jetty.server.Server
  (-stop! [this]
    (.stop this))

  convex.peer.Server
  (-stop! [this]
    (server/stop this))

  convex.api.Convex
  (-stop! [this]
    (client/close this))

  etch.EtchStore
  (-stop! [this]
    (.close this)))

(defprotocol IFriend
  :extend-via-metadata true
  (make-account [this account-key])
  (fund [this address copper-amount])
  (get-client [this]))


;;; maybe extend metadata to cell data types and add raw result to meta?
(defn value-or-throw [result]
  (if-some [code (client/result->error-code result)]
    (throw (ex-info "Error."
             {:error-code code
              :trace      (client/result->trace result)
              :value      (client/result->value result)}))
    (client/result->value result)))

(defn invoke-async [cell ctx & {:as overrides}]
  (let [{:keys [client key-pair address sequence-id timeout]
         :or   {timeout 10000}
         :as   ctx}
        (merge ctx overrides)
        client (or client (get-client ctx))]
    (-> (p/let [sequence-id (or sequence-id (client/sequence-id client address))]
          (-> (client/transact client key-pair
                (cell/invoke address sequence-id cell))))
        (p/timeout timeout)
        (p/then value-or-throw))))

(defn invoke [cell ctx & {:as overrides}]
  @(invoke-async cell ctx overrides))

(defn query-async [cell ctx & {:as overrides}]
  (let [{:keys [client address timeout] :or {timeout 10000}}
        (merge ctx overrides)]
    (-> (client/query (or client (get-client ctx)) address cell)
        (p/timeout timeout)
        (p/then value-or-throw))))

(defn query [cell ctx & {:as overrides}]
  @(query-async cell ctx overrides))


(defrecord LocalFriend [client]
  IFriend
  (make-account [this account-key]
    (invoke (cell (create-account ~account-key)) this))
  (fund [this address amount]
    (invoke (cell (transfer ~address ~amount)) this))
  (get-client [this] client))


(defn local-friend [peer]
  (map->LocalFriend peer))

(def bad-password "this is a bad password")

(defn get-key [key-name]
  (let [keystore-file "private/keys/keystore.pfx"]
    (if (.exists (io/file keystore-file))
      (let [keystore (pfx/load keystore-file)]
        (try (pfx/key-pair-get keystore key-name bad-password)
             (catch Throwable t
               (let [key-pair (kp/ed25519)]
                 (-> (pfx/key-pair-set keystore key-name key-pair bad-password)
                     (pfx/save keystore-file))
                 key-pair))))
      (let [key-pair (kp/ed25519)]
        (.mkdirs (.getParentFile (io/file keystore-file)))
        (-> (pfx/create keystore-file)
            (pfx/key-pair-set key-name key-pair bad-password)
            (pfx/save keystore-file))
        key-pair))))


(defn genesis-account [id]
  {:key-pair (get-key id)
   :address  (Init/getGenesisPeerAddress 0)})

(defn new-account [friend id]
  (let [kp      (get-key id)
        address (make-account friend (kp/account-key kp))]
    {:key-pair kp
     :address  address}))

(defn db-path [id]
  (str "private/db/" id ".etch"))

(defn start-friend-server [{:keys [::server/port ::stake ::id]
                            :as   conf
                            :or   {stake default-stake
                                   id    (subs (str (random-uuid)) 0 8)}}]
  (let [{:keys [address key-pair] :as account} (genesis-account id)

        db     (obj/construct {} (db/current-set (db/open (db-path id))))
        server (obj/construct {:deps [db]}
                 (server/start
                   (server/create key-pair
                     (merge conf
                       {::server/controller address
                        ::server/db         db
                        ::server/bind       "0.0.0.0"
                        ::server/state      [:db db]}))))
        client (obj/construct {:deps [server]} (client/connect conf))
        this   (merge account
                 {:client client
                  :server server
                  :db     db
                  :conf   conf
                  ::id    id})]
    (invoke (cell (set-peer-stake ~(kp/account-key key-pair) ~(->copper stake)))
      this)
    this))

(defn friend-sync-conf [friend]
  (let [a (.getRemoteAddress (get-client friend))]
    {::server/host (.getHostString a)
     ::server/port (.getPort a)}))

(defn start-peer-server [friend {:keys [::server/port ::stake ::id]
                                 :as   conf
                                 :or   {stake default-stake
                                        id    (subs (str (random-uuid)) 0 8)}}]
  (let [{:keys [address key-pair] :as account} (new-account friend id)

        _      (fund friend address (->copper (* stake 2)))
        ;; MUST be before get-client call. Todo: reasess db handling
        db     (obj/construct {} (db/current-set (db/open (db-path id))))
        _      (invoke (cell (create-peer ~(kp/account-key key-pair) ~(->copper stake)))
                 account
                 {:client (get-client friend)})
        server (obj/construct {:deps  [db]
                               :alias [:server port]}
                 (server/start
                   (server/create key-pair
                     (merge conf
                       {::server/controller address
                        ::server/db         db
                        ::server/bind       "0.0.0.0"
                        ::server/state      [:sync (friend-sync-conf friend)]}))))
        client (obj/construct {:deps [server db]} (client/connect conf))]
    (merge account
      {:server server
       :client client
       :db     db})))


(defn peer-blocks [peer]
  (->> peer :server .getPeer .getPeerOrder .getBlocks))

;;; Local test/benchmark

(comment

  (do
    (obj/stop-all!)
    (let [peer-count  3
          friend-port 19000
          friend      (local-friend (start-friend-server {::server/port friend-port}))
          peers       (into [friend]
                       (map-indexed
                         (fn [ix x]
                           (start-peer-server friend {::server/port (+ ix 1 friend-port )}))
                         (range (dec peer-count))))
          _           (def FRIEND friend)
          _           (def PEERS peers)
          
          total-transactions   100
          per-peer (math/ceil (/ total-transactions peer-count))
          total    (* per-peer peer-count)
          
          futs
          (->> peers
               (map-indexed 
                 (fn [peer-ix peer]
                   (p/future
                     (log/info "Starting thread for peer" peer-ix)
                     (db/current-set (:db peer))
                     (let [s1 @(client/sequence-id (:client peer) (:address peer))]
                       (mapv
                         (fn [i]
                           (try 
                             (invoke-async (cell (def foo (+ (if (defined? foo) foo  0) 1)))
                               peer :sequence-id (+ s1 i))
                             (catch Throwable t
                               (log/error t))))
                         (range per-peer)))))))]
      (->> @(p/all futs) (mapv (fn [results] @(p/all results))))

      (assert
        (= (into #{} (map (comp kp/account-key :key-pair)) peers)
           (into #{} (map kp/signed->account-key) (peer-blocks friend)))
        "Not all peers have signed blocks")
      (transduce
        (map kp/signed->cell)
        (fn
          ([] {})
          ([acc block]
           (update acc (quot (.getTimeStamp block) 1000)
             (fn [sec-data]
               (-> sec-data
                   (update :transactions (fnil into [])
                     (map kp/signed->cell (.getTransactions block)))
                   (update :blocks (fnil conj []) block)))))
          ([sec->data]
           (let [blk-cts (->> sec->data vals (map (comp count :blocks)))
                 tx-cts  (->> sec->data vals (map (comp count :transactions)))
                 blk-ct  (apply + blk-cts)
                 tx-ct   (apply + tx-cts)
                 sec-ct  (count sec->data)]
             {:blk-ct  blk-ct
              :tx-ct   tx-ct
              :tps-max (apply max tx-cts)
              :bps-max (apply max blk-cts)
              :tps-avg (double (/ blk-ct sec-ct))
              :bps-avg (double (/ tx-ct sec-ct))})))
        (peer-blocks friend))))

  )
