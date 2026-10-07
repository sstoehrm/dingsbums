(ns dingsbums.cli
  "The command line: `dingsbums [port]` (installed with bbin) or
  `bb server [port]` (in a checkout)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dingsbums.server :as server]))

(def usage
  "usage: dingsbums [port]   serve the whiteboard (default port 8080, 0: any free port)
       dingsbums update    install the latest release
       dingsbums --version print the installed version
       dingsbums --help    print this")

(def latest-jar-url
  "https://github.com/sstoehrm/dingsbums/releases/latest/download/dingsbums.jar")

(defn version
  "The release's version (VERSION in the jar), \"dev\" in a checkout."
  []
  (or (some-> (io/resource "VERSION") slurp str/trim not-empty) "dev"))

(defn parse-args
  "{:port n}, {:version true}, {:help true}, {:update true} or {:error msg}."
  [args]
  (let [[a & more] args]
    (cond
      (seq more) {:error "too many arguments"}
      (nil? a) {:port 8080}
      (#{"-v" "--version"} a) {:version true}
      (#{"-h" "--help"} a) {:help true}
      (= "update" a) {:update true}
      :else (let [n (when (re-matches #"\d{1,5}" a) (parse-long a))]
              (if (and n (<= n 65535))
                {:port n}
                {:error (str "not a port: " a)})))))

(defn- update!
  "Reached from bbin's shim or a checkout: install.sh's launcher handles
  `update` itself."
  []
  (cond
    (= "dev" (version)) (println "dingsbums: this is a checkout; update it with git pull")
    (fs/which "bbin") (System/exit (:exit (p/shell {:continue true} "bbin" "install" latest-jar-url)))
    :else (println (str "dingsbums: update with: bbin install " latest-jar-url))))

(defn -main [& args]
  (let [{:keys [port version help update error]} (parse-args args)]
    (cond
      version (println (str "dingsbums " (dingsbums.cli/version)))
      help (println usage)
      update (update!)
      error (binding [*out* *err*]
              (println (str "dingsbums: " error))
              (println usage)
              (System/exit 1))
      :else (server/serve! port))))
