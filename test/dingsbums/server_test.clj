(ns dingsbums.server-test
  (:require [babashka.http-client :as hc]
            [babashka.http-client.websocket :as ws]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
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
      (try (binding [*base* (str "localhost:" (http/server-port srv))] (t))
           (finally (http/server-stop! srv))))))

(defn- req [method path & [opts]]
  (hc/request (merge {:method method :uri (str "http://" *base* path) :throw false} opts)))

(defn- create! [] (:id (json/parse-string (:body (req :post "/api/sessions")) true)))

(defn- connect [sid]
  (let [q (LinkedBlockingQueue.)
        c (ws/websocket {:uri (str "ws://" *base* "/ws?session=" sid)
                         :on-message (fn [_ data _] (.put q (str data)))
                         :on-close (fn [_ _ _] (.put q "closed"))})]
    {:conn c :q q}))

(defn- recv [{:keys [^LinkedBlockingQueue q]}]
  (let [m (.poll q 2 TimeUnit/SECONDS)]
    (if (= m "closed") :closed (some-> m edn/read-string))))

(defn- quiet? [{:keys [^LinkedBlockingQueue q]}] (nil? (.poll q 300 TimeUnit/MILLISECONDS)))

(defn- send! [{:keys [conn]} ev] (ws/send! conn (pr-str ev)))

(defn- hello! [c] (send! c [:session/hello]) (recv c))

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

(deftest hello-gets-snapshot-and-ops-reach-others-only
  (let [sid (create!) c1 (connect sid) c2 (connect sid)]
    (is (= [:session/snapshot {}] (hello! c1)))
    (hello! c2)
    (send! c1 [:op/upsert [a]])
    (is (= [:op/upsert [a]] (recv c2)))
    (is (quiet? c1) "no echo to the sender")
    (is (= {"a" a} (get-in @s/sessions [sid :objects])))
    (send! c2 [:op/delete ["a"]])
    (is (= [:op/delete ["a"]] (recv c1)))
    (is (= {} (get-in @s/sessions [sid :objects])))))

(deftest patches-are-applied-and-forwarded
  (let [sid (create!) c1 (connect sid) c2 (connect sid)]
    (hello! c1) (hello! c2)
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
    (swap! s/sessions s/upsert sid [a])
    (let [res (req :get (str "/api/sessions/" sid "/export") {:as :bytes})]
      (is (= 200 (:status res)))
      (is (= "application/x-tar" (get-in res [:headers "content-type"])))
      (is (= {"a" a} (tar/import-session (:body res))))
      (swap! s/sessions s/replace-objects sid {})
      (is (= 204 (:status (req :post (str "/api/sessions/" sid "/import") {:body (:body res)}))))
      (is (= {"a" a} (get-in @s/sessions [sid :objects])))
      (is (= [:session/snapshot {"a" a}] (recv c)) "every client gets the imported board"))))

(deftest import-rejects-garbage
  (let [sid (create!)]
    (swap! s/sessions s/upsert sid [a])
    (is (= 400 (:status (req :post (str "/api/sessions/" sid "/import") {:body "not a tar"}))))
    (is (= {"a" a} (get-in @s/sessions [sid :objects])) "session untouched")
    (is (= 404 (:status (req :post "/api/sessions/nope/import" {:body "x"}))))))
