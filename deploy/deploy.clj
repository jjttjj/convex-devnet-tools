(ns deploy
  (:require [cognitect.aws.client.api :as aws]
            [taoensso.timbre :as log]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [hasch.core :as hasch]
            [clojure.data.json :as json]
            [s3 :as s3]
            [cfn :as cfn]))

(def clj-url "https://download.clojure.org/install/linux-install-1.11.1.1182.sh")

;;; Not actually using babashka yet, but good to have?
(def babashka-url "https://raw.githubusercontent.com/babashka/babashka/4c3fa4c40640eed3d7e0f628cfe66e569a05c790/install")

;;; Notes:
;;; I'm pushing source code to a bucket to use in :sources within
;;; AWS::CloudFormation::Init. It might be better to use a public github repo
;;; for this. Unlike other parts of the template, cloudformation doesn't use the
;;; deployer's IAM credentials to access private buckets here. Probably also
;;; better to separate the deployment / library projects at some point.

;; I think we might need to add the service in here to properly our services
;; with stack updates
;; https://github.com/awslabs/aws-cloudformation-templates/blob/master/aws/solutions/OperatingSystems/ubuntu18.04LTS_cfn-hup.cfn.yaml
(defn peer-template [{:keys [code-zip-url]}]
  {:Description "Generate a single peer. If FriendIp parameter is blank, create peer from genesis and use that peer as a faucet and client that can be used to initiate peers which do specify it as FriendIp."
   :Parameters
   {"FriendIp"
    {:Type        "String"
     :Description "Ip of the friend peer, used for account creation, initial sync. Leave empty to create friend peer."
     :Default     ""}
    "KeyName"
    {:Type                  "AWS::EC2::KeyPair::KeyName"
     :Description           "Name of an existing EC2 KeyPair to enable SSH access to the instance"
     :ConstraintDescription "Must be the name of an existing EC2 KeyPair."}
    "Stake"
    {:Type        "Number"
     :Default     1000
     :Description "Stake amount for peer (in Gold)"}}
   :Conditions  {"IsFriend" {"Fn::Equals" [{:Ref "FriendIp"} ""]}}
   :Resources
   {"Role"
    {:Type "AWS::IAM::Role"
     :Properties
     {;;:ManagedPolicyArns policies
      :AssumeRolePolicyDocument
      {"Version" "2012-10-17",
       "Statement"
       [{"Effect"    "Allow"
         "Principal" {"Service" "ec2.amazonaws.com"}
         "Action"    "sts:AssumeRole"}]}
      :Description "Role for Peer ec2 server"}}
    "InstanceProfile"
    {:Type "AWS::IAM::InstanceProfile"
     :Properties
     {:InstanceProfileName {:Ref "Role"}
      :Roles               [{:Ref "Role"}]}}
    "InstanceSecurityGroup"
    {:Type       "AWS::EC2::SecurityGroup",
     :Properties
     {:GroupDescription     "Enable SSH access via port 22",
      :SecurityGroupIngress
      [{:IpProtocol  "tcp",
        :FromPort    "22",
        :ToPort      "22",
        :CidrIp      "0.0.0.0/0"
        :Description "Enable SSH"}
       {:IpProtocol  "tcp",
        :FromPort    "18888",
        :ToPort      "18888",
        :CidrIp      "0.0.0.0/0"
        :Description "Convex peer port"}
       {"Fn::If" ["IsFriend"
                  {:IpProtocol  "tcp",
                   :FromPort    "3000",
                   :ToPort      "3000",
                   :CidrIp      "0.0.0.0/0"
                   :Description "Convex rest port"}
                  {:Ref "AWS::NoValue"}]}]}}

    "EC2Instance"
    {:Type "AWS::EC2::Instance"

     :CreationPolicy
     ;; Wait for a signal from cfn-signal cli command
     {:ResourceSignal {:Count 1 :Timeout "PT20M"}}
     :Properties
     {:InstanceType
      #_                  "m6gd.medium"
      #_                  "m6g.large"
      "m6id.large"
      ;; Latest ubunutu ami
      :ImageId            "{{resolve:ssm:/aws/service/canonical/ubuntu/server/focal/stable/current/amd64/hvm/ebs-gp2/ami-id}}"
      :KeyName            {:Ref "KeyName"}
      :IamInstanceProfile {:Ref "InstanceProfile"}
      :SecurityGroups     [{:Ref "InstanceSecurityGroup"}]
      :Tags               [{:Key "Name" :Value {"Fn::Join" ["-" [{:Ref "AWS::StackName"}
                                                                 "instance"]]}}]

      :UserData
      {"Fn::Base64"
       {"Fn::Sub"
        "#!/bin/bash -xe
# log output
exec > >(tee /var/log/user-data.log|logger -t user-data -s 2>/dev/console) 2>&1

sudo apt-get update && sudo apt-get -y install python3-pip && pip install https://s3.amazonaws.com/cloudformation-examples/aws-cfn-bootstrap-py3-latest.tar.gz

ln -s /root/aws-cfn-bootstrap-latest/init/ubuntu/cfn-hup /etc/init.d/cfn-hup

/usr/local/bin/cfn-init --verbose --resource EC2Instance --region ${AWS::Region} --stack ${AWS::StackName}"}}}
     :Metadata
     {"AWS::CloudFormation::Init"
      ;;The cfn-init helper script processes these configuration sections in the following order:
      ;; packages, groups, users, sources, files, commands, and then services.
      {"config"
       {:packages
        {"apt"
         {"zip"            []
          "unzip"          []
          "rlwrap"         []
          "openjdk-17-jdk" []}}

        :files

        {"/lib/systemd/system/peer.service"
         {:content
          {"Fn::Sub"
           ["[Unit]
Description=Peer Clojure Process

[Service]
WorkingDirectory=/home/ubuntu/convex-devnet
ExecStart=clojure -M:peer -m main ${Args}
Restart=always
RestartSec=1

[Install]
WantedBy=multi-user.target"
            {"Args"
             {"Fn::Join"
              [" "
               [{"Fn::If" ["IsFriend" "" {"Fn::Sub" "--friend-host ${FriendIp}"}]}
                "--stake"
                {:Ref "Stake"}]]}}]}}

         "/home/ubuntu/install/install-clojure"
         {:source clj-url
          :mode   "000744"
          :owner  "ubuntu"
          :group  "ubuntu"}
         "/home/ubuntu/install/babashka"
         {:source babashka-url
          :mode   "000744"
          :owner  "ubuntu"
          :group  "ubuntu"}}
        :sources
        {"/home/ubuntu/convex-devnet" code-zip-url}

        :commands
        {"01-install-clojure"
         {"cwd"     "/home/ubuntu"
          "command" "sudo /home/ubuntu/install/install-clojure"}
         "02-install-babashka"
         {"cwd"     "/home/ubuntu"
          "command" "sudo /home/ubuntu/install/babashka"}

         "03-chown"
         {"cwd"     "/home/ubuntu"
          "command" "chown -R ubuntu:ubuntu convex-devnet"}
         "04-chmod"
         {"cwd"     "/home/ubuntu"
          "command" "chmod -R +rw convex-devnet"}

         "99-signal-ready"
         {"command" {"Fn::Join"
                     [" "
                      ["/usr/local/bin/cfn-signal --exit-code $? --resource EC2Instance"
                       "--stack" {:Ref "AWS::StackName"}
                       "--region" {:Ref "AWS::Region"}]]}}}
        :services
        {:systemd
         {:peer {:enabled       true
                 :ensureRunning true}}}}}}}}

   :Outputs
   {"Ip"
    {:Value {"Fn::GetAtt" ["EC2Instance" "PublicIp"]}}}})

(defn devnet-template
  "Launch a devnet with one peer for each integer in `:stakes`"
  [{:keys [peer-template-url] :as deps} {:keys [stakes] :as args}]
  {:pre [(pos? (count stakes)) (every? int? stakes)]}
  {:Description "Launch a devnet, a number of peer servers all of which use the first one as a faucet/client."

   :Parameters {"KeyName"
                {:Type                  "AWS::EC2::KeyPair::KeyName"
                 :Description           "Name of an existing EC2 KeyPair to enable SSH access to the instance"
                 :ConstraintDescription "Must be the name of an existing EC2 KeyPair."}}

   :Resources
   (into {"Peer0"
          {:Type "AWS::CloudFormation::Stack"
           :Properties
           {:TemplateURL peer-template-url
            :Parameters  {:KeyName {:Ref "KeyName"}
                          :Stake   (first stakes)}}}}
     (map-indexed
       (fn [ix stake]
         [(str "Peer" (inc ix))
          {:Type "AWS::CloudFormation::Stack"
           :Properties
           {:TemplateURL peer-template-url
            :Parameters  {:KeyName  {:Ref "KeyName"}
                          :FriendIp {"Fn::GetAtt" ["Peer0" "Outputs.Ip"]}
                          :Stake    stake}}}])
       (rest stakes)))
   :Outputs
   (into {}
     (map (fn [ix]
            [(str "Peer" ix "Ip")
             {:Value {"Fn::GetAtt" [(str "Peer" ix) "Outputs.Ip"]}}]) (range (count stakes))))})



;;; I'm finding this necessary to get the cognitect api working for some reason
((requiring-resolve 'taoensso.timbre.tools.logging/use-timbre))

;;; initially, to create buckets:
#_(s3/create-public-read-bucket {:bucket "my-public-bucket"})
#_(s3/create-private-bucket {:bucket "my-private-bucket"})

(def region "us-east-1")
(def public-bucket "public-8187fd81-4cfd-4a62-8554-045d27afc824")
(def artifact-bucket "artifacts-d7cdd06b-015a-40c4-89eb-42d6ac8f3eb7")
(def target-dir (fs/create-dirs "target"))
(def source-zip-file (fs/file target-dir "convex-devnet.zip"))

(defn clean []
  (fs/delete-tree target-dir))

(System/setProperty "aws.region" region)


(defn build-code-zip []
  (let [dir (fs/create-dirs "target/convex-devnet")]
    (fs/copy-tree "src" (fs/path dir "src") {:replace-existing true})
    (fs/copy "deps.edn" dir {:replace-existing true})
    (fs/zip source-zip-file (->> (fs/glob "target/convex-devnet" "**")
                                 (remove fs/directory?)
                                 (map (partial fs/relativize "target/convex-devnet"))))))


(defn content-hash [x & [suffix]]
  (str (hasch/uuid x) suffix))

(defn push-code-zip []
  (assert (fs/exists? source-zip-file))
  (s3/upload-new
    {:bucket             public-bucket
     :region             region
     :key                (content-hash (fs/file source-zip-file) ".zip")
     :body               (io/input-stream source-zip-file)
     :extra-request-args {:ACL "public-read"}}))


(defn upload-template [{:keys [region bucket template-json] :as args}]
  (s3/upload-new
    (assoc args
      :key (content-hash template-json ".json")
      :body (.getBytes template-json))))

(defn push-all [{:keys [stakes]}]
  (let [_                    (build-code-zip)
        code-zip-url         (push-code-zip)
        peer-template-json   (json/write-str (peer-template {:code-zip-url code-zip-url}))
        peer-template-url    (upload-template
                               {:region        region
                                :bucket        artifact-bucket
                                :template-json peer-template-json})
        devnet-template-json (-> (devnet-template
                                   {:peer-template-url peer-template-url}
                                   {:stakes stakes})
                                 json/write-str)
        devnet-template-url
        (upload-template {:region        region
                          :bucket        artifact-bucket
                          :template-json devnet-template-json})]
    {:devnet-template-url devnet-template-url
     :peer-template-url   peer-template-url}))


(comment
  (clean)

  ;; Upload all necessary artifacts for a template that creates 4 peers each
  ;; with a stake of 1000.
  (def template-url1
    (:devnet-template-url (push-all {:stakes [1000 1000 1000 1000]})))

  (def stack1
    {:stack-name   "convex-devnet1"
     :parameters   {:KeyName "justin3"} ;;replace with the aws key you want to use for the instances
     :capabilities [:iam]
     :template-url template-url1})

  ;; Create the stack (ie launch the network)
  (cfn/create-stack stack1)

  ;; view stack progress in aws web ui
  (cfn/browse-stack stack1)

  ;; We could update the stack if we want to change something
  ;; (make sure to re-push artifacts)
  #_(cfn/update-stack stack1)

  ;; When satisfied, delete the stack (which will terminate all servers)
  #_(cfn/delete-stack stack1)

  ;;Alternatively, if you want to create the stack from the aws web console:
  #_(cfn/quick-create stack1)
  )

(comment
  ;; Once the stack is complete we can connect to it using the IP addresses given in the outputs


  (require '[convex.client :as client]
           '[convex.cell :as cell]
           '[convex.db :as db]
           '[rest :as rest])

  (let [{:keys [Peer0Ip Peer1Ip]} (:outputs (cfn/describe-stack stack1))]
    (def friend-ip Peer0Ip)
    (def peer-ip Peer1Ip))


  (def client1
    (client/connect {:convex.server/host peer-ip
                     :convex.server/port 18888}))

  ;; The friend is the first peer and can help us create and fund an account.
  (def friend (rest/remote-friend friend-ip 18888 3000))

  (def me (peer/new-account friend "local-account"))

  (peer/fund friend (:address me) 1e9)

  (peer/invoke (cell/* (def foo "bar")) friend me)

  @(client/query client1 (:address me) (cell/* (count (keys (:peers *state*)))))

  )
