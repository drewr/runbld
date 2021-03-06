(ns runbld.hosting.aws-ec2
  (:require [runbld.schema :refer :all]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]])
  (:require [clj-http.client :as http]
            [runbld.hosting :refer [HostingProvider] :as hosting]))

(s/defn region-name :- s/Str
  "Given us-east-1c, we want us-east-1."
  ([az :- s/Str]
   (when az
     (when-let [[_ reg _] (re-find #"(.+-.+-[0-9]+)([a-z]+)" az)]
       reg))))

(s/defn ec2-meta
  ([]
   (ec2-meta "/"))
  ([postfix]
   (try+
    (:body
     (http/get (str "http://169.254.169.254/latest/meta-data" postfix)
               {:socket-timeout 500 :conn-timeout 500}))
    (catch java.net.SocketTimeoutException _)
    (catch org.apache.http.conn.ConnectTimeoutException _)
    (catch java.net.ConnectException _)
    ;; Non-AWS Windows
    (catch java.net.SocketException _)
    (catch java.net.UnknownHostException _))))

(s/defn this-host? :- s/Bool
  "Is this host in AWS EC2?"
  ([]
   (boolean (ec2-meta))))

(defrecord AwsEc2Hosting [facts]
  HostingProvider
  (datacenter    [x]
    (ec2-meta "/placement/availability-zone"))

  (image-id      [x]
    (ec2-meta "/ami-id"))

  (instance-id   [x]
    (ec2-meta "/instance-id"))

  (instance-type [x]
    (ec2-meta "/instance-type"))

  (provider [x]
    "aws-ec2")

  (region        [x]
    (region-name (hosting/datacenter x))))

(s/defn make
  ([facts]
   (AwsEc2Hosting. facts)))
