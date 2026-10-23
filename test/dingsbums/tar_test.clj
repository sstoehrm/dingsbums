(ns dingsbums.tar-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [dingsbums.tar :as tar])
  (:import [java.util Base64]))

(defn- utf8 [^String s] (.getBytes s "UTF-8"))
(defn- text [^bytes b] (String. b "UTF-8"))

(deftest write-read-round-trip
  (let [big (byte-array (map unchecked-byte (range 700)))
        t (tar/write-tar [["a.txt" (utf8 "hello")] ["dir/big.bin" big] ["empty" (byte-array 0)]])
        files (tar/read-tar t)]
    (is (zero? (mod (alength t) 512)))
    (is (= "hello" (text (get files "a.txt"))))
    (is (= (seq big) (seq (get files "dir/big.bin"))))
    (is (zero? (alength (get files "empty"))))))

(deftest malformed-archives-throw
  (is (thrown? clojure.lang.ExceptionInfo (tar/read-tar (byte-array 0))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/read-tar (utf8 (apply str (repeat 600 "x"))))))
  (let [t (tar/write-tar [["a" (utf8 "x")]])]
    (aset-byte t 0 (byte 66))
    (is (thrown? clojure.lang.ExceptionInfo (tar/read-tar t)) "checksum mismatch")))

(def png-b64 (.encodeToString (Base64/getEncoder) (byte-array [1 2 3 4])))
(def objects {"img" {:id "img" :kind :image :x 0 :y 0 :w 10 :h 10 :src (str "data:image/png;base64," png-b64)}
              "t" {:id "t" :kind :text :x 0 :y 0 :w 10 :h 10 :text "hi"}})

(deftest export-splits-images-into-files
  (let [files (tar/read-tar (tar/export-session objects))
        data (edn/read-string (text (get files "session.edn")))]
    (is (= #{"session.edn" "images/img.png"} (set (keys files))))
    (is (= [1 2 3 4] (vec (get files "images/img.png"))))
    (is (= 1 (:version data)))
    (is (= "images/img.png" (get-in data [:objects "img" :src])))
    (is (= (objects "t") (get-in data [:objects "t"])))))

(def log {"t" [{:at 5 :by "Ada" :what :created} {:at 9 :by "Bo" :what :edited}]})

(deftest import-round-trips-export
  (is (= {:objects objects :log log} (tar/import-session (tar/export-session objects log))))
  (is (= {:objects objects :log {}} (tar/import-session (tar/export-session objects))))
  (is (= {"t" (log "t")} (:log (tar/import-session (tar/export-session objects (assoc log "gone" (log "t"))))))
      "only the log of objects on the board"))

(deftest import-drops-invalid-objects
  (let [t (tar/write-tar [["session.edn" (utf8 (pr-str {:version 1 :objects {"ok" {:id "ok" :kind :text}
                                                                              "bad" {:kind :text}
                                                                              "junk" 42}}))]])]
    (is (= {:objects {"ok" {:id "ok" :kind :text}} :log {}} (tar/import-session t)) "an export from before the log")))

(deftest import-rejects-non-exports
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["other.txt" (utf8 "x")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["session.edn" (utf8 "{:objects")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["session.edn" (utf8 "[1 2]")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (byte-array 3)))))
