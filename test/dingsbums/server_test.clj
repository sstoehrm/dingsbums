(ns dingsbums.server-test
  (:require [babashka.http-client :as hc]
            [babashka.http-client.websocket :as ws]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dingsbums.log :as log]
            [dingsbums.ops :as ops]
            [dingsbums.server :as server]
            [dingsbums.sessions :as s]
            [dingsbums.tar :as tar]
            [org.httpkit.server :as http])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:dynamic *base* nil)

(use-fixtures :each
  (fn [t]
    (reset! s/sessions {})
    (let [srv (server/start! 0)]
      (try (binding [*base* (str "127.0.0.1:" (http/server-port srv))] (t))
           (finally (http/server-stop! srv))))))

(defn- req [method path & [opts]]
  (hc/request (merge {:method method :uri (str "http://" *base* path) :throw false} opts)))

(defn- create! [] (:id (json/parse-string (:body (req :post "/api/sessions")) true)))

(defn- connect
  "A client of session sid; the log's messages are dropped unless log? is given."
  [sid & [log?]]
  (let [q (LinkedBlockingQueue.)
        c (ws/websocket {:uri (str "ws://" *base* "/ws?session=" sid)
                         :on-message (fn [_ data _]
                                       (let [s (str data)] (when (or log? (not (str/starts-with? s "[:log/"))) (.put q s))))
                         :on-close (fn [_ _ _] (.put q "closed"))})]
    {:conn c :q q}))

(defn- recv [{:keys [^LinkedBlockingQueue q]}]
  (let [m (.poll q 2 TimeUnit/SECONDS)]
    (if (= m "closed") :closed (some-> m edn/read-string))))

(defn- quiet? [{:keys [^LinkedBlockingQueue q]}] (nil? (.poll q 300 TimeUnit/MILLISECONDS)))

(defn- send! [{:keys [conn]} ev] (ws/send! conn (pr-str ev)))

(defn- hello!
  "Says hello; the snapshot it gets back. Reads up to the roster after it,
  skipping what it heard of others before."
  [c]
  (send! c [:session/hello])
  (loop [snap nil]
    (let [m (recv c)]
      (case (first m)
        :session/snapshot (recur m)
        (:presence/update :log/all :log/add) (recur snap)
        snap))))

(defn- hello-both!
  "c1 then c2 say hello; what c1 hears of c2 joining."
  [c1 c2]
  (hello! c1) (hello! c2) (recv c1))

(def a {:id "a" :kind :sticky :x 0 :y 0 :w 10 :h 10})

(deftest create-check-and-unknown
  (let [sid (create!)]
    (is (contains? @s/sessions sid))
    (is (= 204 (:status (req :get (str "/api/sessions/" sid)))))
    (is (= 404 (:status (req :get "/api/sessions/nope"))))
    (is (= 405 (:status (req :get "/api/sessions"))) "no listing of session ids")))

(deftest static-files
  (is (= 200 (:status (req :get "/"))))
  (is (re-find #"text/html" (get-in (req :get "/") [:headers "content-type"])))
  (is (= 404 (:status (req :get "/nope.js"))))
  (is (= 404 (:status (server/handler {:request-method :get :uri "/../bb.edn"}))) "no path traversal")
  (is (= 404 (:status (server/handler {:request-method :get :uri "/icons/../../bb.edn"}))) "no traversal from a subfolder")
  (is (= "image/svg+xml" (get-in (req :get "/icons/dingsbums.svg") [:headers "content-type"])))
  (is (= 404 (:status (req :get "/icons"))) "no folders")
  (is (= 404 (:status (req :get "/js/manifest.edn"))) "only known file types"))

(defn- outbound-address
  "The address this host sends from (connecting a UDP socket sends
  nothing), nil when it has none besides loopback."
  []
  (try (with-open [s (java.net.DatagramSocket.)]
         (.connect s (java.net.InetAddress/getByName "192.0.2.1") 9)
         (let [^java.net.InetAddress a (.getLocalAddress s)]
           (when-not (or (.isLoopbackAddress a) (.isAnyLocalAddress a)) a)))
       (catch java.io.IOException _ nil)))

(deftest listens-on-loopback-only
  (if-let [addr (outbound-address)]
    (let [port (parse-long (second (re-find #":(\d+)$" *base*)))]
      (is (thrown? java.net.ConnectException
                   (with-open [s (java.net.Socket.)]
                     (.connect s (java.net.InetSocketAddress. ^java.net.InetAddress addr (int port)) 2000)))
          (str "reachable on " addr)))
    (println "listens-on-loopback-only: skipped, this host has no non-loopback IPv4 address")))

(deftest hello-gets-snapshot-and-ops-reach-others-only
  (let [sid (create!) c1 (connect sid) c2 (connect sid)]
    (is (= [:session/snapshot {}] (hello! c1)))
    (hello! c2)
    (recv c1)
    (send! c1 [:op/upsert [a]])
    (is (= [:op/upsert [a]] (recv c2)))
    (is (quiet? c1) "no echo to the sender")
    (is (= {"a" a} (get-in @s/sessions [sid :objects])))
    (send! c2 [:op/delete ["a"]])
    (is (= [:op/delete ["a"]] (recv c1)))
    (is (= {} (get-in @s/sessions [sid :objects])))))

(deftest patches-are-applied-and-forwarded
  (let [sid (create!) c1 (connect sid) c2 (connect sid)]
    (hello-both! c1 c2)
    (send! c1 [:op/upsert [a]])
    (recv c2)
    (send! c1 [:op/patch [{:id "a" :x 40}]])
    (is (= [:op/patch [{:id "a" :x 40}]] (recv c2)))
    (is (= 40 (get-in @s/sessions [sid :objects "a" :x])))
    (send! c2 [:op/delete ["a"]])
    (recv c1)
    (send! c1 [:op/patch [{:id "a" :x 50}]])
    (recv c2)
    (is (= {} (get-in @s/sessions [sid :objects])) "a late patch does not bring a deleted object back")))

(deftest ops-sent-before-hello-are-in-the-snapshot
  (let [sid (create!) c (connect sid)]
    (send! c [:op/upsert [a]])
    (is (= [:session/snapshot {"a" a}] (hello! c)))))

(defn- blocked?
  "Thread t is blocked on a lock within 2 seconds."
  [^Thread t]
  (loop [n 0]
    (cond (= Thread$State/BLOCKED (.getState t)) true
          (< n 200) (do (Thread/sleep 10) (recur (inc n)))
          :else false)))

(deftest an-op-during-hello-reaches-the-newcomer-once
  (let [sid (create!) c1 (connect sid) c2 (connect sid :log)
        snapshot-due (promise) go-on (promise) op-thread (promise)
        real-send! http/send! real-op? ops/op?]
    (hello! c1)
    (with-redefs [http/send! (fn [ch msg]
                               (when (str/starts-with? msg "[:session/snapshot")
                                 (deliver snapshot-due true) @go-on)
                               (real-send! ch msg))
                  ops/op? (fn [ev] (deliver op-thread (Thread/currentThread)) (real-op? ev))]
      (send! c2 [:session/hello])
      (is (deref snapshot-due 2000 false))
      ;; c1's change while c2's snapshot is on its way
      (send! c1 [:op/upsert [a]])
      (is (blocked? (deref op-thread 2000 (Thread/currentThread))) "the op waits for the hello")
      (is (nil? (get-in @s/sessions [sid :objects "a"])) "not applied before the snapshot is sent")
      (deliver go-on true)
      (let [msgs (vec (repeatedly 5 #(recv c2)))
            [objects lg] (reduce (fn [[objects lg] [ev x]]
                                   (case ev
                                     :session/snapshot [x lg]
                                     :log/all [objects x]
                                     :log/add [objects (log/add lg x)]
                                     :op/upsert [(ops/upsert objects x) lg]
                                     [objects lg]))
                                 [{} {}] msgs)]
        (is (= [:session/snapshot :log/all :presence/roster :op/upsert :log/add] (mapv first msgs))
            "nothing before the hello is answered, the change once after it")
        (is (quiet? c2))
        (is (= {"a" a} objects) "the newcomer's board has the change")
        (is (= [:created] (mapv :what (get lg "a"))) "and its history, once")))))

(deftest unknown-session-is-told-and-closed
  (let [c (connect "nope")]
    (is (= [:session/missing] (recv c)))
    (is (= :closed (recv c)))))

(deftest malformed-frames-are-ignored
  (let [sid (create!) c1 (connect sid) c2 (connect sid)]
    (hello! c1) (hello! c2)
    (ws/send! (:conn c1) "{{{")
    (send! c1 [:op/upsert [{:id 1}]])
    (send! c1 [:evil/event 1])
    (send! c1 [:op/upsert [a]])
    (is (= [:op/upsert [a]] (recv c2)) "only the valid op is forwarded, the channel survives")))

(deftest presence-roster-updates-and-leaving
  (let [sid (create!) c1 (connect sid)]
    (send! c1 [:session/hello])
    (recv c1)
    (let [[ev you roster] (recv c1)]
      (is (= [:presence/roster [{:id you :color 0}]] [ev roster]) "everyone in the session, me included")
      (send! c1 [:presence/update {:name "Ada" :cursor [10 20]}])
      (hello! c1) ; once it is answered, the update before it is in
      (let [c2 (connect sid)
            _ (send! c2 [:session/hello])
            _ (recv c2)
            [_ you2 roster2] (recv c2)]
        (is (= #{{:id you :color 0 :name "Ada" :cursor [10 20]} {:id you2 :color 1}} (set roster2))
            "a newcomer sees who is there and where")
        (is (= [:presence/update {:id you2 :color 1}] (recv c1)) "the others hear of a newcomer")
        (send! c2 [:presence/update {:selection ["a"]}])
        (is (= [:presence/update {:id you2 :selection ["a"]}] (recv c1)) "only what changed")
        (is (quiet? c2) "no echo to the sender")
        (send! c2 [:presence/update {:color 5}])
        (send! c2 [:presence/update {:id you}])
        (send! c2 [:presence/update {:name ""}])
        (is (quiet? c1) "invalid presence is dropped")
        (ws/close! (:conn c2))
        (is (= [:presence/left you2] (recv c1)))))))

(deftest changes-are-logged-with-name-and-time
  (let [sid (create!) c1 (connect sid :log) c2 (connect sid :log)]
    (send! c1 [:presence/update {:name "Ada"}])
    (hello-both! c1 c2)
    (send! c1 [:op/upsert [a]])
    (is (= [:op/upsert [a]] (recv c2)))
    (let [[ev [[id {:keys [at by what]}]]] (recv c2)]
      (is (= [:log/add "a" "Ada" :created] [ev id by what]) "everyone gets the entry")
      (is (< (- (System/currentTimeMillis) 5000) at (inc (System/currentTimeMillis))) "the server's time"))
    (is (= :log/add (first (recv c1))) "the sender too: the server's name and time")
    (send! c2 [:op/patch [{:id "a" :x 5}]])
    (recv c1)
    (is (= [:log/add [["a" "Guest" :moved]]] (let [[ev es] (recv c1)] [ev (mapv (fn [[id e]] [id (:by e) (:what e)]) es)]))
        "no name yet: a guest")
    (send! c2 [:op/patch [{:id "a" :x 6}]])
    (recv c1)
    (is (quiet? c1) "a repeat within a minute is not logged again")
    (let [c3 (connect sid :log)]
      (send! c3 [:session/hello])
      (recv c3)
      (is (= [:log/all {"a" [["Ada" :created] ["Guest" :moved]]}]
             (let [[ev lg] (recv c3)] [ev (update-vals lg #(mapv (juxt :by :what) %))]))
          "a newcomer gets the log after the snapshot")
      (send! c1 [:op/delete ["a"]])
      (recv c2) (recv c2)
      (let [c4 (connect sid :log)]
        (send! c4 [:session/hello])
        (recv c4)
        (is (= [:created :moved :deleted] (mapv :what (get (second (recv c4)) "a")))
            "deleted objects' logs too: an undo brings the object back with its history")))
    (let [res (req :get (str "/api/sessions/" sid "/export") {:as :bytes})]
      (is (= {} (:log (tar/import-session (:body res)))) "exported only for objects on the board"))))

(deftest clients-and-idle-clock
  (let [sid (create!) c (connect sid)]
    (hello! c)
    (is (= 1 (count (get-in @s/sessions [sid :clients]))))
    (is (nil? (get-in @s/sessions [sid :empty-since])))
    (ws/close! (:conn c))
    (Thread/sleep 300)
    (is (empty? (get-in @s/sessions [sid :clients])))
    (is (number? (get-in @s/sessions [sid :empty-since])))))

(deftest export-and-import
  (let [sid (create!) c (connect sid)]
    (hello! c)
    (swap! s/sessions s/apply-op sid [:op/upsert [a]] "Ada" 0)
    (let [res (req :get (str "/api/sessions/" sid "/export") {:as :bytes})]
      (is (= 200 (:status res)))
      (is (= "application/x-tar" (get-in res [:headers "content-type"])))
      (is (= {"a" a} (:objects (tar/import-session (:body res)))))
      (swap! s/sessions s/replace-objects sid {})
      (is (= 204 (:status (req :post (str "/api/sessions/" sid "/import") {:body (:body res)}))))
      (is (= {"a" a} (get-in @s/sessions [sid :objects])))
      (is (= [:session/snapshot {"a" a}] (recv c)) "every client gets the imported board"))))

(deftest import-rejects-garbage
  (let [sid (create!)]
    (swap! s/sessions s/apply-op sid [:op/upsert [a]] "Ada" 0)
    (is (= 400 (:status (req :post (str "/api/sessions/" sid "/import") {:body "not a tar"}))))
    (is (= {"a" a} (get-in @s/sessions [sid :objects])) "session untouched")
    (is (= 404 (:status (req :post "/api/sessions/nope/import" {:body "x"}))))))
