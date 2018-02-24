(ns net.cgrand.boring
  (:require [clojure.java.io :as io]))

;; syntax: "(" tunnel ")" rest
;; examples: (ssh:user@server)localhost:5555
;; examples: (ssh:user@server)(oc:pod-name)5555

(defprotocol Connector
  (connect-through [connector host port]
    "Returns a pair of streams as {:in output-stream :out input-stream} -- yes they are inverted."))

(def socket-connector
  (reify
    java.io.Closeable
    (close [_] nil)
    Connector
    (connect-through [_ host port]
      (let [socket (java.net.Socket. ^String host (int port))]
        {:in (.getOutputStream socket)
         :out (.getInputStream socket)}))))

(defn- resolve-identity [options host user]
  (some #(let [creds (get-in options % {})]
           (when (some creds [:ssh/key :ssh/password]) creds))
    [[[host user]] [[host]] []]))

(defn ssh [connector tunnel-segment-spec options]
  (let [[_ user ssh-host ssh-port] (re-matches #"(.*?)@(.*?)(?::(\d+))?" tunnel-segment-spec)
        ssh-port (Integer/parseInt (or ssh-port "22"))
        {:keys [:ssh/key :ssh/password]} (resolve-identity options ssh-host user)]
    ; TODO use connector for nested tunnels
    (let [client (doto (org.apache.sshd.client.SshClient/setUpDefaultClient) .start)
          session (-> client (.connect user ssh-host ssh-port) (doto .await) .getSession)]
      (if key
        (.addPublicKeyIdentity session
          (org.apache.sshd.common.util.security.SecurityUtils/loadKeyPairIdentity
            "shhhh" (io/input-stream key)
            (reify org.apache.sshd.common.config.keys.FilePasswordProvider
              (getPassword [_ _] password))))
        (.addPasswordIdentity session password))
      (-> session .auth .verify)
      (reify
        java.io.Closeable
        (close [_] (.close session) (.close connector))
        Connector
        (connect-through [_ host port]
          (let [channel (.createDirectTcpipChannel session
                          (org.apache.sshd.common.util.net.SshdSocketAddress. "ssh-repl-client" 0) 
                          (org.apache.sshd.common.util.net.SshdSocketAddress. host port))]
            (-> channel .open .verify)
            {:in (-> channel .getInvertedIn #_(io/writer :encoding "UTF-8"))
             :out (-> channel .getInvertedOut #_(io/reader :encoding "UTF-8"))}))))))

(defn- require-resolve-sym [sym]
  (let [ns-sym (symbol (namespace sym))]
    (when-not (find-ns ns-sym) (require ns-sym))
    (resolve sym)))

(defn- parse-connection-string [connection-string]
  (if-some [[_ tunnel-segments exit] (re-matches #"(\((?:[^()]|\)\()*\))?([^()]*)" connection-string)]
    (let [tunnel-segments
          (into []
            (map #(if-some [[_ ns tag segment-spec] (re-matches #"\((?:(.*?)/)?(.*?):(.*)\)" %)]
                    [(require-resolve-sym (symbol (or ns "net.cgrand.boring") tag)) segment-spec]
                    (throw (ex-info (str "Can't parse tunnel segment. " (pr-str %)) {::segment %}))))
            (re-seq #"\(.*?\)" (or tunnel-segments "")))]
      [tunnel-segments exit])
    (throw (ex-info (str "Can't parse connection string. " (pr-str connection-string)) {::connection-string connection-string}))))

(defn connect
  "Returns a pair of streams as {:in output-stream :out input-stream} -- yes they are inverted."
  [connection-string options]
  (let [[tunnels exit] (parse-connection-string connection-string)
        [_ host port] (re-matches #"(?:(.*):)?(\d+)" exit)
        port (Integer/parseInt port)
        connector (reduce (fn [connector [f spec]]
                            (f connector spec options)) socket-connector #_TODO tunnels)] 
    (connect-through connector host port)))

; TODO api for repeatedly establishing connections while sharing tunnels