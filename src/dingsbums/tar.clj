(ns dingsbums.tar
  "Minimal POSIX ustar (regular files only) and the session export format:
  session.edn ({:version 1 :objects {id obj}}, image :src replaced by a path)
  plus images/<id>.<ext>."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dingsbums.ops :as ops])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util Arrays Base64]))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- put-str! [^bytes buf off ^String s]
  (let [b (utf8 s)] (System/arraycopy b 0 buf (int off) (alength b))))

(defn- octal
  "n as zero-padded octal, width-1 digits (the field's last byte stays NUL)."
  [n width]
  (let [s (Long/toOctalString n)]
    (str (str/join (repeat (- width 1 (count s)) "0")) s)))

(defn- checksum
  "Header checksum: every byte summed unsigned, the checksum field counted as spaces."
  [^bytes h]
  (+ (* 8 32) (reduce + (map #(bit-and % 0xff)
                             (concat (Arrays/copyOfRange h 0 148) (Arrays/copyOfRange h 156 512))))))

(defn- header ^bytes [^String name size]
  (when (> (alength (utf8 name)) 99) (throw (ex-info "tar entry name too long" {:name name})))
  (let [h (byte-array 512)]
    (put-str! h 0 name)
    (put-str! h 100 "0000644")
    (put-str! h 108 "0000000")
    (put-str! h 116 "0000000")
    (put-str! h 124 (octal size 12))
    (put-str! h 136 (octal (quot (System/currentTimeMillis) 1000) 12))
    (aset-byte h 156 (byte 48))
    (put-str! h 257 "ustar")
    (put-str! h 263 "00")
    (put-str! h 148 (octal (checksum h) 7))
    (aset-byte h 155 (byte 32))
    h))

(defn write-tar
  "A ustar archive of entries [[name bytes] ...]."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (doseq [[name ^bytes data] entries]
      (.write out (header name (alength data)))
      (.write out data)
      (.write out (byte-array (mod (- 512 (mod (alength data) 512)) 512))))
    (.write out (byte-array 1024))
    (.toByteArray out)))

(defn- field [^bytes buf off len]
  (let [end (loop [i off] (if (and (< i (+ off len)) (not (zero? (aget buf i)))) (recur (inc i)) i))]
    (String. buf (int off) (int (- end off)) StandardCharsets/UTF_8)))

(defn- parse-octal [s]
  (try (Long/parseLong (str/trim s) 8)
       (catch NumberFormatException _ (throw (ex-info "bad tar header" {})))))

(defn read-tar
  "{name bytes} of the regular files in a ustar archive; ex-info when malformed."
  [^bytes buf]
  (loop [off 0 acc {}]
    (when (> (+ off 512) (alength buf)) (throw (ex-info "truncated tar" {})))
    (let [h (Arrays/copyOfRange buf (int off) (int (+ off 512)))]
      (if (every? zero? h)
        acc
        (let [_ (when (not= (parse-octal (field h 148 8)) (checksum h)) (throw (ex-info "bad tar checksum" {})))
              prefix (field h 345 155)
              name (if (str/blank? prefix) (field h 0 100) (str prefix "/" (field h 0 100)))
              size (parse-octal (field h 124 12))
              start (+ off 512)
              end (+ start size)]
          (when (or (neg? size) (> end (alength buf))) (throw (ex-info "truncated tar" {})))
          (recur (+ start (* 512 (quot (+ size 511) 512)))
                 (if (#{0 48} (aget h 156))
                   (assoc acc name (Arrays/copyOfRange buf (int start) (int end)))
                   acc)))))))

(def ^:private ext->mime
  {"png" "image/png" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp" "svg" "image/svg+xml"})

(def ^:private mime->ext (assoc (zipmap (vals ext->mime) (keys ext->mime)) "image/jpg" "jpeg"))

(defn- data-url
  "[mime bytes] of a base64 data URL, or nil."
  [s]
  (when-let [[_ mime b64] (and (string? s) (re-matches #"(?s)data:([^;,]+);base64,(.*)" s))]
    (try [mime (.decode (Base64/getDecoder) ^String b64)]
         (catch IllegalArgumentException _ nil))))

(defn export-session
  "Tar bytes of a session's objects: session.edn plus one file per image."
  ^bytes [objects]
  (let [images (keep (fn [[id o]]
                       (when-let [[mime data] (and (= :image (:kind o)) (data-url (:src o)))]
                         [id (str "images/" id "." (mime->ext mime "bin")) data]))
                     objects)
        objects (reduce (fn [m [id path _]] (assoc-in m [id :src] path)) objects images)]
    (write-tar (into [["session.edn" (utf8 (pr-str {:version 1 :objects objects}))]]
                     (map (fn [[_ path data]] [path data]))
                     images))))

(defn- inline-image [o files]
  (if-let [^bytes data (and (= :image (:kind o)) (string? (:src o)) (get files (:src o)))]
    (let [ext (second (re-find #"\.([a-z0-9]+)$" (:src o)))]
      (assoc o :src (str "data:" (ext->mime ext "application/octet-stream") ";base64,"
                         (.encodeToString (Base64/getEncoder) data))))
    o))

(defn import-session
  "The objects of an exported session; ex-info when the bytes aren't one."
  [^bytes buf]
  (let [files (read-tar buf)
        ^bytes edn-bytes (or (get files "session.edn") (throw (ex-info "no session.edn" {})))
        data (try (edn/read-string (String. edn-bytes StandardCharsets/UTF_8))
                  (catch Exception e (throw (ex-info "unreadable session.edn" {} e))))
        objects (when (map? data) (:objects data))]
    (when-not (map? objects) (throw (ex-info "session.edn has no :objects map" {})))
    (into {} (comp (map val) (filter ops/valid-object?) (map (fn [o] [(:id o) (inline-image o files)])))
          objects)))
