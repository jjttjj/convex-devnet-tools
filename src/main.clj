(ns main
  (:require [clojure.tools.cli :as cli]
            [clojure.core.server :as repl-server]
            [objection.core :as obj]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [;;taoensso.timbre.appenders.community.rolling ;;<- for v6. Currently on v5
             taoensso.timbre.appenders.3rd-party.rolling :as roll]
            [rest :as rest]
            [peer :as peer]))

(def cli-options
  [["-H" "--friend-host HOST" (str "Host address for genesis peer connection. "
                                "When not specified, creates the friend peer.")]
   
   ["-P" "--friend-port PORT" "Port used for genesis peer connection"
    :default 18888
    :parse-fn parse-long]
   ["-R" "--friend-http-port PORT" "Port used for genesis peer rest api"
    :default 3000
    :parse-fn parse-long]
   ["-p" "--port PORT" "Port used for peer"
    :default 18888
    :parse-fn parse-long]
   ["-s" "--stake STAKE" "Stake amount for peer (in Gold)"
    :default peer/default-stake
    :parse-fn parse-long]])

(log/merge-config!
  {:min-level :info
   ;;:timestamp-opts (assoc log/default-timestamp-opts :timezone (java.util.TimeZone/getDefault))
   :appenders {:spit    (assoc (roll/rolling-appender {:path    "logs/convex.log"
                                                       :pattern :daily})
                          :min-level :debug
                          :ns-filter {:deny #{"*NIOServer"
                                              "convex.net.Connection"
                                              "ResourceLoader*"}})
               :println {:min-level :debug}}})

(defn start-peer [{:keys [friend-host friend-port friend-http-port port] :as opt}]
  (if friend-host
    (let [_      (log/info "Starting peer")
          friend (rest/remote-friend friend-host friend-port friend-http-port)
          peer (obj/construct {:alias [:peer port]}
                 (peer/start-peer-server friend {:convex.server/port port}))])
    (let [_        (log/info "Starting friend peer")
          friend-peer (obj/construct {:alias [:peer port] :data {:friend? true}}
                     (peer/start-friend-server {:convex.server/port port}))]
      (obj/construct {} (rest/start-server friend-peer friend-http-port)))))

(defn -main [& args]
  (let [{:keys [options]}                              (cli/parse-opts args cli-options)
        {:keys [friend-host friend-port friend-http-port port]} options]
    (start-peer options)

    (obj/construct {:stopfn (fn [_] (repl-server/stop-server "repl-server"))}
      (repl-server/start-server {:name    "repl-server"
                                 :address "localhost"
                                 :port    5555
                                 :accept  'clojure.core.server/repl}))
    
    ;; prevent immediate exit when called from cli
    @(promise)))


(comment
  (obj/stop-all!)

  ;; By default a friend peer is created
  (start-peer (:options (cli/parse-opts [] cli-options)))

  ;; If we specify a friend host we use that create our normal peer with its existing state
  (start-peer (:options (cli/parse-opts ["--friend-host" "localhost" "-p" "18889"] cli-options)))

  (obj/status) ;;see registered objects

  ;; We can transact against our peer and verify that it is propagated to the friend peer
  (let [friend  (obj/object [:peer 18888])
        peer (obj/object [:peer 18889])]
    (peer/invoke (convex.cell/* (def hello "world")) peer)
    (Thread/sleep 200)
    (peer/query (convex.cell/* (lookup ~(:address peer) hello)) friend))
  
  )
