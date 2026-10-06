(ns dingsbums.server
  "HTTP + WebSocket server: static files from public/, the session API and the
  op hub. Run with `bb server [port]`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dingsbums.ops :as ops]
            [dingsbums.sessions :as s]
            [dingsbums.tar :as tar]
            [org.httpkit.server :as http]))

(def max-frame (* 20 1024 1024))
(def max-body (* 100 1024 1024))

(defn- now [] (System/currentTimeMillis))

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "css" "text/css; charset=utf-8" "map" "application/json" "json" "application/json"
   "svg" "image/svg+xml" "png" "image/png" "ico" "image/x-icon"})

(defn- static [uri]
  (let [root (.getCanonicalFile (io/file "public"))
        f (.getCanonicalFile (io/file root (str/replace-first (if (= uri "/") "/index.html" uri) #"^/+" "")))]
    (if (and (str/starts-with? (.getPath f) (str (.getPath root) java.io.File/separator)) (.isFile f))
      {:status 200
       :headers {"Content-Type" (get content-types (last (str/split (.getName f) #"\.")) "application/octet-stream")}
       :body f}
      {:status 404 :body "not found"})))

(defn- send-to!
  "Sends msg to every client of sid except `except`."
  [sid except msg]
  (doseq [ch (get-in @s/sessions [sid :clients]) :when (not= ch except)]
    (http/send! ch msg)))

(defn- read-event [msg]
  (when (string? msg) (try (edn/read-string msg) (catch Exception _ nil))))

(defn- receive! [sid ch msg]
  (let [ev (read-event msg)]
    (cond
      (= ev [:session/hello])
      (http/send! ch (pr-str (if-let [sess (get @s/sessions sid)]
                               [:session/snapshot (:objects sess)]
                               [:session/missing])))

      (ops/op? ev)
      (let [[op arg] ev]
        (swap! s/sessions (if (= op :op/upsert) s/upsert s/delete) sid arg)
        (send-to! sid ch (pr-str ev))))))

(defn- ws [req sid]
  (http/as-channel req
    {:on-open (fn [ch]
                (when-not (contains? (swap! s/sessions s/join sid ch) sid)
                  (http/send! ch (pr-str [:session/missing]))
                  (http/close ch)))
     :on-receive (fn [ch msg] (receive! sid ch msg))
     :on-close (fn [ch _] (swap! s/sessions s/leave sid ch (now)))}))

(defn- import! [sid ^java.io.InputStream body]
  (if-let [objects (try (tar/import-session (if body (.readAllBytes body) (byte-array 0)))
                        (catch Exception _ nil))]
    (do (swap! s/sessions s/replace-objects sid objects)
        (send-to! sid nil (pr-str [:session/snapshot objects]))
        {:status 204})
    {:status 400 :body "not a dingsbums export"}))

(defn- query-param [req k]
  (some (fn [kv] (let [[a b] (str/split kv #"=" 2)] (when (= a k) b)))
        (str/split (or (:query-string req) "") #"&")))

(defn handler [req]
  (let [{:keys [request-method uri]} req
        [_ sid action] (re-matches #"/api/sessions/([^/]+)(?:/(export|import))?" uri)]
    (cond
      (= uri "/ws")
      (let [sid (query-param req "session")]
        (if (and (:websocket? req) sid)
          (ws req sid)
          {:status 400 :body "expected a websocket with ?session=<id>"}))

      (= uri "/api/sessions")
      (if (= request-method :post)
        (let [sid (s/new-id)]
          (swap! s/sessions s/create sid (now))
          {:status 200 :headers {"Content-Type" "application/json"} :body (str "{\"id\":\"" sid "\"}")})
        {:status 405 :body "method not allowed"})

      sid
      (cond
        (not (contains? @s/sessions sid)) {:status 404 :body "no such session"}
        (and (nil? action) (= request-method :get)) {:status 204}
        (and (= action "export") (= request-method :get))
        {:status 200
         :headers {"Content-Type" "application/x-tar"
                   "Content-Disposition" (str "attachment; filename=\"dingsbums-" sid ".tar\"")}
         :body (java.io.ByteArrayInputStream. (tar/export-session (get-in @s/sessions [sid :objects])))}
        (and (= action "import") (= request-method :post)) (import! sid (:body req))
        :else {:status 405 :body "method not allowed"})

      (= request-method :get) (static uri)
      :else {:status 404 :body "not found"})))

(defn sweep!
  "Drops sessions idle for more than 15 minutes."
  []
  (swap! s/sessions s/expire (now)))

(defn start! [port]
  (http/run-server #'handler {:port port :max-ws max-frame :max-body max-body :legacy-return-value? false}))

(defn -main [& args]
  (let [srv (start! (parse-long (or (first args) "8080")))]
    (future (loop []
              (Thread/sleep 60000)
              (try (sweep!) (catch Exception e (println "sweep failed:" (ex-message e))))
              (recur)))
    (println (str "dingsbums: http://localhost:" (http/server-port srv)))
    @(promise)))
