;; Packs a generated board into the .tar dingsbums' Import takes, then reads
;; it back through the server's import to show what would not survive.
;;
;;   bb -cp src .claude/skills/dingsbums-board/pack.clj <folder> [out.tar]
;;
;; <folder> holds session.edn and, for pictures, images/; the tar goes to
;; <folder>/board.tar unless out.tar is given. Exits 1 when an
;; object would be dropped, a connection misses an end or a picture is missing.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[dingsbums.ops :as ops]
         '[dingsbums.tar :as tar])

(let [[dir out] *command-line-args*
      _ (when-not dir
          (println "usage: bb -cp src .claude/skills/dingsbums-board/pack.clj <folder> [out.tar]")
          (System/exit 2))
      out (or out (str (fs/path dir "board.tar")))
      edn-file (fs/file dir "session.edn")
      objs (vals (:objects (edn/read-string (slurp edn-file))))
      images (when (fs/directory? (fs/path dir "images")) (fs/list-dir (fs/path dir "images")))
      entries (into [["session.edn" (fs/read-all-bytes edn-file)]]
                    (map (fn [p] [(str "images/" (fs/file-name p)) (fs/read-all-bytes p)]))
                    images)
      bytes (tar/write-tar entries)
      kept (tar/import-session bytes)
      problems (concat
                (for [o objs :when (not (ops/valid-object? o))]
                  (str "dropped, not a valid object: " (pr-str o)))
                (for [o objs :when (and (= :connection (:kind o)) (not (and (kept (:from o)) (kept (:to o)))))]
                  (str "connection " (:id o) " misses an end: " (:from o) " -> " (:to o)))
                (for [o (vals kept) :when (and (= :image (:kind o)) (not (re-find #"^data:" (str (:src o)))))]
                  (str "image " (:id o) " has no picture: " (:src o))))]
  (fs/write-bytes out bytes)
  (run! println problems)
  (println (str "wrote " out ": " (count kept) " of " (count objs) " objects import"))
  (System/exit (if (seq problems) 1 0)))
