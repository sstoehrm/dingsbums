# dingsbums Whiteboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A very simplified miro-like whiteboard: babashka backend holding sessions in one atom, hammer SPA drawing the board on Canvas 2D, live multi-user sync over a WebSocket, tar export/import, undo/redo.

**Architecture:** Pure cores, thin shells. `ops.cljc` (shared object ops), `sessions.clj` and `tar.clj` are pure and carry the backend tests; `server.clj` wires them to http-kit. On the frontend `geom.cljs` and `model.cljs` are pure; `events.cljs` holds every handler as a plain fn (tested directly) and registers them; `board.cljs` (defdraw), `views.cljs` (DOM overlays) and `core.cljs` (window listeners) are the untested shell, verified by build and in the browser.

**Tech Stack:** babashka 1.13 (http-kit server, babashka.http-client, cheshire built in), ClojureScript via shadow-cljs 3.5.4, hammer v0.1.2 (git dep, https), jsdom 30.1.1 for node tests.

**Spec:** `docs/superpowers/specs/2026-10-06-whiteboard-design.md`

## Global Constraints

- hammer: `io.github.sstoehrm/hammer {:git/url "https://github.com/sstoehrm/hammer.git" :git/tag "v0.1.2" :git/sha "14b64e3"}` (SSH is not available on this machine; the repo is public).
- shadow-cljs `3.5.4` in both `deps.edn` and `package.json`; **no** `org.clojure/clojurescript` pin.
- jsdom `30.1.1`; npm versions exact.
- No other dependencies, backend or frontend.
- Session ids: random UUIDs; no route lists them. Idle expiry: more than 15 min with no clients.
- Object `:id`: string, 1–64 chars; `:kind`: keyword. WS frame limit 20 MB (`:max-ws`); HTTP body limit 100 MB; pasted image data URL limit 15 MB.
- WS protocol: one EDN event vector per text frame; server reads with `clojure.edn` only.
- Under `:advanced`, hint JS interop objects with `^js`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Offline edits on reconnect** — ops queued while disconnected must survive the reconnect: the snapshot must not overwrite them. Pinned in Task 3 (`ops-sent-before-hello-are-in-the-snapshot`) and Task 6 (`tube-sync`: hello sent from `:on-connect`).
2. **Hostile or broken WS frames** (not EDN, wrong shape, unknown event, ids > 64 chars) — ignored, channel stays open, nothing forwarded; a client never dispatches server events outside the whitelist. Pinned in Task 1 (`op-shapes`), Task 3 (`malformed-frames-are-ignored`), Task 6 (`tube-sync` pushes `[:evil/event 1]`).
3. **Importing something that is not a dingsbums export** (random bytes, other tar, broken EDN) — 400, session untouched. Pinned in Task 2 (`import-rejects-non-exports`) and Task 3 (`import-rejects-garbage`).
4. **Locked objects inside a mixed selection** — delete/move/fill/resize/edit skip them, and a connection to a deleted unlocked object still goes. Pinned in Task 5 (`locks-protect-objects`) and Task 6 (`selecting-and-moving`, `text-editing`).
5. **Stale answers and vanished objects** — an HTTP answer for a session the user already left is ignored; an edit whose object was deleted remotely is dropped. Pinned in Task 6 (`routing`, `remote-events`) and Task 5 (`text-changes`).

---

## File Structure

| File | Responsibility |
|---|---|
| `.gitignore` | build output, deps caches |
| `bb.edn` | tasks `test`, `server`, `build` |
| `src/dingsbums/ops.cljc` | object validation, upsert, delete with connection cascade, op shape check |
| `src/dingsbums/sessions.clj` | the `sessions` atom and pure fns over it |
| `src/dingsbums/tar.clj` | ustar write/read; session export/import |
| `src/dingsbums/server.clj` | routes, static files, WS hub, sweeper, `-main` |
| `test/dingsbums/{ops,sessions,tar,server}_test.clj` | backend tests (`bb test`) |
| `deps.edn`, `shadow-cljs.edn`, `package.json` | frontend build |
| `public/index.html` | page + CSS |
| `src/dingsbums/geom.cljs` | camera, hit-testing, handles, resize, rects, shapes, text fitting |
| `src/dingsbums/model.cljs` | constructors, groups, locks, change sets, history |
| `src/dingsbums/events.cljs` | all handlers (plain fns) + fx + registration |
| `src/dingsbums/board.cljs` | defdraw board, drawing, pointer → events, text measure/fit cache |
| `src/dingsbums/views.cljs` | landing, session bar, toolbar, selection toolbar, text editor, app |
| `src/dingsbums/core.cljs` | `init`, window listeners (hash, keys, paste, resize) |
| `test/dingsbums/test_env.cljs` | jsdom globals for node tests |
| `test/dingsbums/{geom,model,events}_test.cljs` | frontend tests (`npm test`) |

---

### Task 1: Shared object ops and the sessions atom

**Files:**
- Create: `.gitignore`, `bb.edn`, `src/dingsbums/ops.cljc`, `src/dingsbums/sessions.clj`
- Test: `test/dingsbums/ops_test.clj`, `test/dingsbums/sessions_test.clj`

**Interfaces:**
- Produces: `dingsbums.ops/valid-object? [o] → bool`, `upsert [objects objs] → objects`, `cascade-ids [objects ids] → #{id}`, `delete [objects ids] → objects`, `op? [ev] → bool`.
- Produces: `dingsbums.sessions/sessions` (atom), `idle-ms`, `new-id [] → str`, `create [m sid now]`, `upsert [m sid objs]`, `delete [m sid ids]`, `replace-objects [m sid objects]`, `join [m sid ch]`, `leave [m sid ch now]`, `expire [m now]` — all `→ m`, all no-ops for an unknown `sid`.

- [ ] **Step 1: Scaffold `.gitignore` and `bb.edn`**

`.gitignore`:
```
node_modules/
public/js/
target/
.shadow-cljs/
.cpcache/
```

`bb.edn`:
```clojure
{:paths ["src" "test"]
 :tasks
 {test {:doc "Run the backend tests"
        :requires ([clojure.test :as t])
        :task (let [nss '[dingsbums.ops-test dingsbums.sessions-test]]
                (apply require nss)
                (let [{:keys [fail error]} (apply t/run-tests nss)]
                  (System/exit (if (zero? (+ fail error)) 0 1))))}}}
```

- [ ] **Step 2: Write the failing tests**

`test/dingsbums/ops_test.clj`:
```clojure
(ns dingsbums.ops-test
  (:require [clojure.test :refer [deftest is]]
            [dingsbums.ops :as ops]))

(def a {:id "a" :kind :sticky :x 0 :y 0 :w 10 :h 10})
(def b {:id "b" :kind :shape :x 50 :y 0 :w 10 :h 10})
(def ab {:id "ab" :kind :connection :from "a" :to "b"})
(def objs {"a" a "b" b "ab" ab})

(deftest upsert-puts-valid-objects-by-id
  (is (= {"a" a} (ops/upsert {} [a])))
  (is (= {"a" (assoc a :x 5)} (ops/upsert {"a" a} [(assoc a :x 5)])))
  (is (= {} (ops/upsert {} [{:id 1 :kind :x} {:id "x"} "junk" {:id "" :kind :text}
                            {:id (apply str (repeat 65 "x")) :kind :text}]))))

(deftest delete-cascades-to-connections
  (is (= {"b" b} (ops/delete objs ["a"])))
  (is (= {"a" a "b" b} (ops/delete objs ["ab"])))
  (is (= objs (ops/delete objs ["nope"]))))

(deftest op-shapes
  (is (ops/op? [:op/upsert [a]]))
  (is (ops/op? [:op/delete ["a"]]))
  (is (not (ops/op? [:op/delete [1]])))
  (is (not (ops/op? [:op/upsert [{:id "x"}]])))
  (is (not (ops/op? [:op/upsert a])))
  (is (not (ops/op? [:session/hello])))
  (is (not (ops/op? '(:op/delete ["a"]))))
  (is (not (ops/op? "[:op/delete]"))))
```

`test/dingsbums/sessions_test.clj`:
```clojure
(ns dingsbums.sessions-test
  (:require [clojure.test :refer [deftest is]]
            [dingsbums.sessions :as s]))

(def a {:id "a" :kind :sticky})
(def ab {:id "ab" :kind :connection :from "a" :to "b"})

(deftest create-and-object-ops
  (let [m (s/create {} "s1" 100)]
    (is (= {:objects {} :clients #{} :empty-since 100} (get m "s1")))
    (is (= {"a" a "ab" ab} (get-in (s/upsert m "s1" [a ab]) ["s1" :objects])))
    (is (= {} (get-in (-> m (s/upsert "s1" [a ab]) (s/delete "s1" ["a"])) ["s1" :objects])))
    (is (= {"x" a} (get-in (s/replace-objects m "s1" {"x" a}) ["s1" :objects])))
    (is (= m (s/upsert m "nope" [a])) "unknown session: no-op, nothing created")
    (is (= m (s/join m "nope" :ch)))))

(deftest join-and-leave-track-idle-time
  (let [m (-> {} (s/create "s1" 100) (s/join "s1" :c1) (s/join "s1" :c2))]
    (is (= #{:c1 :c2} (get-in m ["s1" :clients])))
    (is (nil? (get-in m ["s1" :empty-since])))
    (is (nil? (get-in (s/leave m "s1" :c1 500) ["s1" :empty-since])) "still one client")
    (is (= 600 (get-in (-> m (s/leave "s1" :c1 500) (s/leave "s1" :c2 600)) ["s1" :empty-since])))))

(deftest expire-after-15-idle-minutes
  (let [m (-> {} (s/create "idle" 0) (s/create "busy" 0) (s/join "busy" :c))]
    (is (= #{"idle" "busy"} (set (keys (s/expire m s/idle-ms)))) "exactly 15 min: kept")
    (is (= #{"busy"} (set (keys (s/expire m (inc s/idle-ms))))))))

(deftest ids-are-random-uuids
  (is (re-matches #"[0-9a-f-]{36}" (s/new-id)))
  (is (not= (s/new-id) (s/new-id))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `bb test`
Expected: FAIL — `Could not locate dingsbums/ops.cljc` (or similar).

- [ ] **Step 4: Implement**

`src/dingsbums/ops.cljc`:
```clojure
(ns dingsbums.ops
  "Object ops shared by server and client. objects is a map {id obj}.")

(defn valid-object?
  "o looks like a board object: a map with a string :id (1–64 chars) and a keyword :kind."
  [o]
  (and (map? o) (string? (:id o)) (<= 1 (count (:id o)) 64) (keyword? (:kind o))))

(defn upsert
  "objects with each valid obj of objs put under its :id; invalid ones are skipped."
  [objects objs]
  (into objects (comp (filter valid-object?) (map (juxt :id identity))) objs))

(defn cascade-ids
  "The set of ids plus the ids of connections whose :from or :to is one of them."
  [objects ids]
  (let [ids (set ids)]
    (into ids (keep (fn [[id o]] (when (or (ids (:from o)) (ids (:to o))) id))) objects)))

(defn delete
  "objects without ids and without the connections touching them."
  [objects ids]
  (apply dissoc objects (cascade-ids objects ids)))

(defn op?
  "ev is a well-formed [:op/upsert [obj ...]] or [:op/delete [id ...]]."
  [ev]
  (and (vector? ev) (= 2 (count ev)) (sequential? (second ev))
       (case (first ev)
         :op/upsert (every? valid-object? (second ev))
         :op/delete (every? string? (second ev))
         false)))
```

`src/dingsbums/sessions.clj`:
```clojure
(ns dingsbums.sessions
  "All sessions in one atom: {sid {:objects {id obj} :clients #{ch} :empty-since ms-or-nil}}.
  The fns below are pure over that map; server.clj swap!s them in. Every fn is a
  no-op for an unknown sid."
  (:require [dingsbums.ops :as ops]))

(def idle-ms (* 15 60 1000))

(defonce sessions (atom {}))

(defn new-id [] (str (random-uuid)))

(defn create [m sid now] (assoc m sid {:objects {} :clients #{} :empty-since now}))

(defn- when-exists [m sid f] (if (contains? m sid) (f m) m))

(defn upsert [m sid objs] (when-exists m sid #(update-in % [sid :objects] ops/upsert objs)))

(defn delete [m sid ids] (when-exists m sid #(update-in % [sid :objects] ops/delete ids)))

(defn replace-objects [m sid objects] (when-exists m sid #(assoc-in % [sid :objects] objects)))

(defn join [m sid ch]
  (when-exists m sid #(-> % (update-in [sid :clients] conj ch) (assoc-in [sid :empty-since] nil))))

(defn leave [m sid ch now]
  (when-exists m sid (fn [m]
                       (let [m (update-in m [sid :clients] disj ch)]
                         (cond-> m (empty? (get-in m [sid :clients])) (assoc-in [sid :empty-since] now))))))

(defn expire
  "m without the sessions that have had no clients for more than 15 minutes at now."
  [m now]
  (into {} (remove (fn [[_ {:keys [clients empty-since]}]]
                     (and (empty? clients) empty-since (> (- now empty-since) idle-ms))))
        m))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb test`
Expected: `0 failures, 0 errors.`, exit 0.

- [ ] **Step 6: Commit**

```bash
git add .gitignore bb.edn src/dingsbums/ops.cljc src/dingsbums/sessions.clj test/dingsbums/ops_test.clj test/dingsbums/sessions_test.clj
git commit -m "Backend: shared object ops and sessions atom" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Tar codec and session export/import

**Files:**
- Create: `src/dingsbums/tar.clj`
- Modify: `bb.edn` (add `dingsbums.tar-test` to the test list)
- Test: `test/dingsbums/tar_test.clj`

**Interfaces:**
- Consumes: `dingsbums.ops/valid-object?`.
- Produces: `dingsbums.tar/write-tar [[[name bytes] ...]] → bytes`, `read-tar [bytes] → {name bytes}` (throws `ex-info` on malformed input), `export-session [objects] → bytes`, `import-session [bytes] → objects` (throws `ex-info` when not a dingsbums export).

- [ ] **Step 1: Add the test ns to `bb.edn`**

Change the `nss` vector to:
```clojure
'[dingsbums.ops-test dingsbums.sessions-test dingsbums.tar-test]
```

- [ ] **Step 2: Write the failing tests**

`test/dingsbums/tar_test.clj`:
```clojure
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

(deftest import-round-trips-export
  (is (= objects (tar/import-session (tar/export-session objects)))))

(deftest import-drops-invalid-objects
  (let [t (tar/write-tar [["session.edn" (utf8 (pr-str {:version 1 :objects {"ok" {:id "ok" :kind :text}
                                                                              "bad" {:kind :text}
                                                                              "junk" 42}}))]])]
    (is (= {"ok" {:id "ok" :kind :text}} (tar/import-session t)))))

(deftest import-rejects-non-exports
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["other.txt" (utf8 "x")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["session.edn" (utf8 "{:objects")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (tar/write-tar [["session.edn" (utf8 "[1 2]")]]))))
  (is (thrown? clojure.lang.ExceptionInfo (tar/import-session (byte-array 3)))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `bb test`
Expected: FAIL — `Could not locate dingsbums/tar`.

- [ ] **Step 4: Implement**

`src/dingsbums/tar.clj`:
```clojure
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Interop check with GNU tar**

Run:
```bash
bb -cp src -e "(require '[dingsbums.tar :as t]) (java.nio.file.Files/write (java.nio.file.Path/of \"target/check.tar\" (make-array String 0)) (t/export-session {\"t\" {:id \"t\" :kind :text :text \"hi\"}}) (make-array java.nio.file.OpenOption 0))" && tar tvf target/check.tar
```
(`mkdir -p target` first.) Expected: one line listing `session.edn`, no tar error.

- [ ] **Step 7: Commit**

```bash
git add bb.edn src/dingsbums/tar.clj test/dingsbums/tar_test.clj
git commit -m "Backend: ustar codec and session export/import" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: HTTP + WebSocket server

**Files:**
- Create: `src/dingsbums/server.clj`, `public/index.html` (minimal; Task 7 replaces it)
- Modify: `bb.edn` (test list + `server` task)
- Test: `test/dingsbums/server_test.clj`

**Interfaces:**
- Consumes: Task 1 `ops/op?`, all of `dingsbums.sessions`; Task 2 `tar/export-session`, `tar/import-session`.
- Produces: `dingsbums.server/handler [req] → resp`, `start! [port] → server` (http-kit server object, `:legacy-return-value? false`), `sweep! []`, `-main [& [port]]`, `max-frame`.
- Wire protocol (consumed by Task 6): client sends `[:session/hello]`, `[:op/upsert [obj...]]`, `[:op/delete [id...]]`; server sends `[:session/snapshot objects]`, `[:session/missing]`, and forwards ops verbatim (re-printed) to other clients.

- [ ] **Step 1: `bb.edn` — test list and server task**

```clojure
{:paths ["src" "test"]
 :tasks
 {test {:doc "Run the backend tests"
        :requires ([clojure.test :as t])
        :task (let [nss '[dingsbums.ops-test dingsbums.sessions-test dingsbums.tar-test dingsbums.server-test]]
                (apply require nss)
                (let [{:keys [fail error]} (apply t/run-tests nss)]
                  (System/exit (if (zero? (+ fail error)) 0 1))))}
  server {:doc "Run the server: bb server [port] (default 8080)"
          :task (apply (requiring-resolve 'dingsbums.server/-main) *command-line-args*)}}}
```

- [ ] **Step 2: Minimal `public/index.html`** (so static serving is testable)

```html
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><title>dingsbums</title></head>
<body><div id="app"></div><script src="/js/main.js"></script></body>
</html>
```

- [ ] **Step 3: Write the failing tests**

`test/dingsbums/server_test.clj`:
```clojure
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
  (is (= 404 (:status (server/handler {:request-method :get :uri "/../bb.edn"}))) "no path traversal"))

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
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `bb test`
Expected: FAIL — `Could not locate dingsbums/server`.

- [ ] **Step 5: Implement**

`src/dingsbums/server.clj`:
```clojure
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
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `bb test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 7: Smoke-run the server**

Run (background): `bb server 8090`; then `curl -s -X POST localhost:8090/api/sessions` → `{"id":"<uuid>"}`; `curl -si localhost:8090/ | head -1` → `HTTP/1.1 200 OK`. Stop the server.

- [ ] **Step 8: Commit**

```bash
git add bb.edn public/index.html src/dingsbums/server.clj test/dingsbums/server_test.clj
git commit -m "Backend: http-kit server, session API, ws hub, sweeper" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend build scaffold and geometry

**Files:**
- Create: `deps.edn`, `shadow-cljs.edn`, `package.json`, `src/dingsbums/geom.cljs`, `test/dingsbums/test_env.cljs`
- Modify: `bb.edn` (add `build` task)
- Test: `test/dingsbums/geom_test.cljs`

**Interfaces:**
- Produces (`dingsbums.geom`): `min-size` 20, `handle-px` 8, `padding` 8, `line-height` 1.2;
  `screen->world [cam [sx sy]] → [wx wy]`, `world->screen [cam [wx wy]] → [sx sy]`, `zoom-at [cam factor [sx sy]] → cam`, `pan [cam dsx dsy] → cam` (drag semantics: content follows the pointer);
  `rect-from-points [p1 p2] → rect`, `center [rect] → [x y]`, `inside? [rect p]`, `contains-rect? [outer inner]`, `bounds [rects] → rect|nil`;
  `endpoints [objects conn] → [[ax ay] [bx by]]|nil`, `obj-rect [objects o] → rect|nil`, `border-point [rect from-point] → [x y]`;
  `draw-order [objects] → seq` (frames, then connections, then the rest; by `:z`), `hit [objects p tol] → obj|nil`, `ids-in-rect [objects rect] → seq of ids`;
  `handles [rect] → {:nw :ne :sw :se → [x y]}`, `handle-at [rect p tol] → corner|nil`, `resize [o corner p] → o`;
  `shape-points [o] → [[x y] ...]` (star, arrow);
  `wrap [measure text size max-w] → [line ...]`, `fit-text [measure text w h] → {:size :lines}`; `measure` is `(fn [s size] width)`.
- Test helper: `dingsbums.test-env` (require first in every cljs test ns).

- [ ] **Step 1: Build files**

`deps.edn`:
```clojure
{:paths ["src" "test"]
 :deps {thheller/shadow-cljs {:mvn/version "3.5.4"}
        io.github.sstoehrm/hammer {:git/url "https://github.com/sstoehrm/hammer.git"
                                   :git/tag "v0.1.2" :git/sha "14b64e3"}}}
```

`shadow-cljs.edn`:
```clojure
{:deps true
 :builds {:app {:target :browser
                :output-dir "public/js"
                :asset-path "/js"
                :modules {:main {:init-fn dingsbums.core/init}}}
          :test {:target :node-test
                 :output-to "target/test.js"
                 :ns-regexp "-test$"}}}
```

`package.json`:
```json
{
  "name": "dingsbums",
  "private": true,
  "scripts": {
    "test": "shadow-cljs compile test && node target/test.js"
  },
  "devDependencies": {
    "jsdom": "30.1.1",
    "shadow-cljs": "3.5.4"
  }
}
```

Add to `bb.edn` `:tasks`:
```clojure
  build {:doc "Release build of the frontend into public/js"
         :task (shell "npx shadow-cljs release app")}
```

`test/dingsbums/test_env.cljs`:
```clojure
(ns dingsbums.test-env
  "Browser globals for node tests; require it first in every test ns."
  (:require ["jsdom" :refer [JSDOM]]))

(defonce env
  (let [d (JSDOM. "<!DOCTYPE html><html><body></body></html>" #js {:url "http://localhost/"})
        w (.-window d)]
    (set! js/globalThis.window w)
    (set! js/globalThis.document (.-document w))
    (set! js/globalThis.location (.-location w))
    (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16)))
    d))
```

Run: `npm install`
Expected: installs without errors.

- [ ] **Step 2: Write the failing tests**

`test/dingsbums/geom_test.cljs`:
```clojure
(ns dingsbums.geom-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing]]
            [dingsbums.geom :as g]))

(def cam {:x 100 :y 50 :zoom 2})

(deftest camera-transforms
  (is (= [100 50] (g/screen->world cam [0 0])))
  (is (= [150 100] (g/screen->world cam [100 100])))
  (is (= [100 100] (g/world->screen cam [150 100])))
  (testing "zoom keeps the world point under the cursor"
    (let [c (g/zoom-at cam 2 [100 100])]
      (is (= 4 (:zoom c)))
      (is (= [150 100] (g/screen->world c [100 100])))))
  (is (= 4 (:zoom (g/zoom-at cam 10 [0 0]))) "clamped to 4")
  (is (= 0.1 (:zoom (g/zoom-at cam 0.001 [0 0]))) "clamped to 0.1")
  (is (= {:x 90 :y 50 :zoom 2} (g/pan cam 20 0)) "dragging right by 20px moves the camera 10 world units left"))

(def objs {"f" {:id "f" :kind :frame :x 0 :y 0 :w 500 :h 500 :z 9}
           "r" {:id "r" :kind :shape :shape :rect :x 10 :y 10 :w 100 :h 100 :z 1}
           "c" {:id "c" :kind :shape :shape :circle :x 200 :y 10 :w 100 :h 100 :z 2}
           "s" {:id "s" :kind :sticky :x 50 :y 50 :w 100 :h 100 :z 3}
           "rc" {:id "rc" :kind :connection :from "r" :to "c" :z 4}})

(deftest draw-order-frames-then-connections-then-the-rest
  (is (= ["f" "rc" "r" "c" "s"] (map :id (g/draw-order objs)))))

(deftest hit-testing
  (is (= "s" (:id (g/hit objs [60 60] 3))) "topmost wins")
  (is (= "r" (:id (g/hit objs [20 20] 3))))
  (is (= "c" (:id (g/hit objs [250 60] 3))) "inside the circle")
  (is (= "f" (:id (g/hit objs [205 15] 3))) "circle's bbox corner misses it; the frame below catches it")
  (is (= "rc" (:id (g/hit objs [170 61] 3))) "near the connection line")
  (is (= "f" (:id (g/hit objs [170 70] 3))) "too far from the line")
  (is (nil? (g/hit objs [600 600] 3))))

(deftest handles-and-resize
  (let [o {:x 0 :y 0 :w 100 :h 50}]
    (is (= :se (g/handle-at o [102 49] 4)))
    (is (= :nw (g/handle-at o [-3 2] 4)))
    (is (nil? (g/handle-at o [50 25] 4)))
    (is (= {:x 0 :y 0 :w 150 :h 80} (g/resize o :se [150 80])))
    (is (= {:x -10 :y -10 :w 110 :h 60} (g/resize o :nw [-10 -10])))
    (is (= {:x 0 :y 0 :w 20 :h 20} (g/resize o :se [-50 -50])) "min 20×20, opposite corner fixed")
    (is (= {:x 80 :y 30 :w 20 :h 20} (g/resize o :nw [500 500])))))

(deftest rects
  (is (= {:x 10 :y 5 :w 20 :h 15} (g/rect-from-points [30 20] [10 5])))
  (is (= {:x 0 :y 0 :w 300 :h 110} (g/bounds [{:x 0 :y 0 :w 10 :h 10} {:x 200 :y 10 :w 100 :h 100}])))
  (is (nil? (g/bounds [])))
  (is (= #{"r" "s"} (set (g/ids-in-rect objs {:x 0 :y 0 :w 160 :h 160}))))
  (is (= {:x 60 :y 60 :w 190 :h 0} (g/obj-rect objs (objs "rc"))) "a connection's rect spans its endpoints")
  (is (= [100 50] (g/border-point {:x 100 :y 0 :w 100 :h 100} [0 50])) "where a line from the left enters"))

(deftest shapes
  (is (= 10 (count (g/shape-points {:shape :star :x 0 :y 0 :w 100 :h 100}))))
  (is (= [200 50] (nth (g/shape-points {:shape :arrow :x 100 :y 0 :w 100 :h 100}) 3)) "arrow tip"))

(def measure (fn [s size] (* (count s) size 0.5)))

(deftest text-fitting
  (is (= {:size 40 :lines ["hello" "world"]} (g/fit-text measure "hello world" 116 116)))
  (is (= ["ab c"] (:lines (g/fit-text measure "ab c" 1000 30))) "height-bound: one line")
  (is (= 6 (:size (g/fit-text measure (apply str (repeat 500 "x ")) 40 40))) "never below 6")
  (is (= ["a" "" "b"] (g/wrap measure "a\n\nb" 10 1000)) "newlines kept")
  (is (= ["abcdef" "ghij"] (g/wrap measure "abcdefghij" 10 30)) "long words break by character"))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — compile error, `dingsbums.geom` not available.

- [ ] **Step 4: Implement**

`src/dingsbums/geom.cljs`:
```clojure
(ns dingsbums.geom
  "Pure geometry in world coordinates: camera transforms, hit-testing, handles,
  resize, rectangles, shapes and text fitting. A camera is {:x :y :zoom}:
  screen = (world - cam) * zoom."
  (:require [clojure.string :as str]))

(def min-size 20)
(def min-zoom 0.1)
(def max-zoom 4)
(def handle-px 8)
(def padding 8)
(def line-height 1.2)

(defn- abs* [n] (js/Math.abs n))
(defn- sq [n] (* n n))

;; camera

(defn screen->world [{:keys [x y zoom]} [sx sy]] [(+ x (/ sx zoom)) (+ y (/ sy zoom))])

(defn world->screen [{:keys [x y zoom]} [wx wy]] [(* (- wx x) zoom) (* (- wy y) zoom)])

(defn zoom-at
  "cam zoomed by factor around screen point [sx sy], zoom clamped to 0.1–4."
  [cam factor [sx sy]]
  (let [z (-> (* (:zoom cam) factor) (max min-zoom) (min max-zoom))
        [wx wy] (screen->world cam [sx sy])]
    {:x (- wx (/ sx z)) :y (- wy (/ sy z)) :zoom z}))

(defn pan
  "cam after the content was dragged by [dsx dsy] screen pixels."
  [cam dsx dsy]
  (-> cam (update :x - (/ dsx (:zoom cam))) (update :y - (/ dsy (:zoom cam)))))

;; rectangles

(defn rect-from-points [[x1 y1] [x2 y2]]
  {:x (min x1 x2) :y (min y1 y2) :w (abs* (- x2 x1)) :h (abs* (- y2 y1))})

(defn center [{:keys [x y w h]}] [(+ x (/ w 2)) (+ y (/ h 2))])

(defn inside? [{:keys [x y w h]} [px py]] (and (<= x px (+ x w)) (<= y py (+ y h))))

(defn contains-rect? [outer {:keys [x y w h]}]
  (and (inside? outer [x y]) (inside? outer [(+ x w) (+ y h)])))

(defn bounds
  "The smallest rect around rects, or nil for none."
  [rects]
  (when (seq rects)
    (let [x1 (apply min (map :x rects)) y1 (apply min (map :y rects))
          x2 (apply max (map #(+ (:x %) (:w %)) rects)) y2 (apply max (map #(+ (:y %) (:h %)) rects))]
      {:x x1 :y y1 :w (- x2 x1) :h (- y2 y1)})))

;; objects

(defn endpoints
  "[[ax ay] [bx by]]: centers of a connection's ends, nil when one is missing."
  [objects {:keys [from to]}]
  (let [a (get objects from) b (get objects to)]
    (when (and a b) [(center a) (center b)])))

(defn obj-rect
  "o's rect; a connection's spans its endpoints."
  [objects o]
  (if (= :connection (:kind o))
    (when-let [[a b] (endpoints objects o)] (rect-from-points a b))
    (select-keys o [:x :y :w :h])))

(defn border-point
  "Where the line from point [fx fy] to the rect's center crosses its border."
  [{:keys [w h] :as r} [fx fy]]
  (let [[cx cy] (center r)
        dx (- fx cx) dy (- fy cy)
        tx (if (zero? dx) js/Infinity (/ (/ w 2) (abs* dx)))
        ty (if (zero? dy) js/Infinity (/ (/ h 2) (abs* dy)))
        t (min 1 tx ty)]
    [(+ cx (* dx t)) (+ cy (* dy t))]))

(defn- layer [o] (case (:kind o) :frame 0 :connection 1 2))

(defn draw-order
  "Objects bottom to top: frames, then connections, then everything else; :z within."
  [objects]
  (sort-by (juxt layer :z) (vals objects)))

(defn- seg-dist
  "Distance from p to the segment a–b."
  [[px py] [ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay)
        len2 (+ (sq dx) (sq dy))
        t (if (zero? len2) 0 (-> (/ (+ (* (- px ax) dx) (* (- py ay) dy)) len2) (max 0) (min 1)))]
    (js/Math.sqrt (+ (sq (- px (+ ax (* t dx)))) (sq (- py (+ ay (* t dy))))))))

(defn- hit? [objects o [px py :as p] tol]
  (cond
    (= :connection (:kind o))
    (when-let [[a b] (endpoints objects o)] (<= (seg-dist p a b) tol))

    (and (= :shape (:kind o)) (= :circle (:shape o)))
    (let [[cx cy] (center o)]
      (<= (+ (sq (/ (- px cx) (/ (:w o) 2))) (sq (/ (- py cy) (/ (:h o) 2)))) 1))

    :else (inside? o p)))

(defn hit
  "The topmost object at world point p, or nil; tol is the connection tolerance in world units."
  [objects p tol]
  (->> (draw-order objects) reverse (filter #(hit? objects % p tol)) first))

(defn ids-in-rect
  "Ids of the objects lying fully inside rect."
  [objects rect]
  (keep (fn [[id o]] (when-let [r (obj-rect objects o)] (when (contains-rect? rect r) id))) objects))

;; handles

(defn handles [{:keys [x y w h]}]
  {:nw [x y] :ne [(+ x w) y] :sw [x (+ y h)] :se [(+ x w) (+ y h)]})

(defn handle-at
  "The corner of rect within tol of p, or nil."
  [rect [px py] tol]
  (some (fn [[k [hx hy]]] (when (and (<= (abs* (- px hx)) tol) (<= (abs* (- py hy)) tol)) k))
        (handles rect)))

(defn resize
  "o with corner k dragged to [px py]; the opposite corner stays, min 20×20."
  [o k [px py]]
  (let [{:keys [x y w h]} o
        x2 (+ x w) y2 (+ y h)
        [nx1 ny1 nx2 ny2] (case k
                            :nw [(min px (- x2 min-size)) (min py (- y2 min-size)) x2 y2]
                            :ne [x (min py (- y2 min-size)) (max px (+ x min-size)) y2]
                            :sw [(min px (- x2 min-size)) y x2 (max py (+ y min-size))]
                            :se [x y (max px (+ x min-size)) (max py (+ y min-size))])]
    (assoc o :x nx1 :y ny1 :w (- nx2 nx1) :h (- ny2 ny1))))

;; shapes

(defn shape-points
  "Polygon corners of a :star or :arrow filling the object's rect."
  [{:keys [x y w h shape]}]
  (case shape
    :star (let [[cx cy] (center {:x x :y y :w w :h h})]
            (vec (for [i (range 10)]
                   (let [r (if (even? i) 1 0.4)
                         a (- (* i (/ js/Math.PI 5)) (/ js/Math.PI 2))]
                     [(+ cx (* r (/ w 2) (js/Math.cos a))) (+ cy (* r (/ h 2) (js/Math.sin a)))]))))
    :arrow [[x (+ y (* 0.3 h))] [(+ x (* 0.6 w)) (+ y (* 0.3 h))] [(+ x (* 0.6 w)) y]
            [(+ x w) (+ y (/ h 2))]
            [(+ x (* 0.6 w)) (+ y h)] [(+ x (* 0.6 w)) (+ y (* 0.7 h))] [x (+ y (* 0.7 h))]]
    nil))

;; text

(defn- break-word
  "word split into chunks no wider than max-w (at least one char each)."
  [measure word size max-w]
  (loop [cs (seq word) cur "" out []]
    (if-let [c (first cs)]
      (let [t (str cur c)]
        (if (and (seq cur) (> (measure t size) max-w))
          (recur (rest cs) (str c) (conj out cur))
          (recur (rest cs) t out)))
      (conj out cur))))

(defn wrap
  "text word-wrapped to max-w at font size; explicit newlines kept."
  [measure text size max-w]
  (vec (mapcat (fn [para]
                 (loop [words (mapcat #(if (> (measure % size) max-w) (break-word measure % size max-w) [%])
                                      (str/split para #" +"))
                        line nil
                        out []]
                   (if-let [w (first words)]
                     (let [t (if line (str line " " w) w)]
                       (if (and line (> (measure t size) max-w))
                         (recur (rest words) w (conj out line))
                         (recur (rest words) t out)))
                     (conj out (or line "")))))
               (str/split-lines text))))

(defn fit-text
  "{:size :lines}: the largest font size in 6..200 at which text, wrapped, fits
  w×h minus padding (line height 1.2×size); size 6 when nothing fits."
  [measure text w h]
  (let [mw (- w (* 2 padding))
        mh (- h (* 2 padding))
        fits (fn [size] (let [lines (wrap measure text size mw)]
                          (when (<= (* (count lines) size line-height) mh) lines)))]
    (loop [lo 6 hi 200 best nil]
      (if (> lo hi)
        (or best {:size 6 :lines (wrap measure text 6 mw)})
        (let [mid (quot (+ lo hi) 2)]
          (if-let [lines (fits mid)]
            (recur (inc mid) hi {:size mid :lines lines})
            (recur lo (dec mid) best)))))))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `npm test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 6: Commit**

```bash
git add deps.edn shadow-cljs.edn package.json package-lock.json bb.edn src/dingsbums/geom.cljs test/dingsbums/test_env.cljs test/dingsbums/geom_test.cljs
git commit -m "Frontend: build scaffold and geometry" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Board model and undo history

**Files:**
- Create: `src/dingsbums/model.cljs`
- Test: `test/dingsbums/model_test.cljs`

**Interfaces:**
- Consumes: `dingsbums.ops/cascade-ids`.
- Produces (`dingsbums.model`): `default-size` `{kind [w h]}`, `sticky-fill`, `swatches` (vector, first is `nil` = no fill), `history-cap` 100, `text-kinds` `#{:text :sticky}`;
  `new-id`, `next-z [objects]`, `make [objects kind rect extra] → obj`;
  `expand-groups [objects ids] → #{id}`, `movable [objects ids] → {id obj}` (unlocked, non-connection);
  change-set builders, each `→ {id obj-or-nil}`: `group-changes [objects ids]`, `ungroup-changes [objects ids]`, `lock-changes [objects ids locked?]`, `delete-changes [objects ids]`, `fill-changes [objects ids color]`, `text-changes [objects id text]`, `moved [start dx dy]` (start is `{id obj}`);
  `apply-changes [objects changes] → objects`, `ops-for [changes] → [[:op/upsert [obj...]] [:op/delete [id...]]]` (each only when non-empty);
  `commit [db changes] → [db ops]`, `undo [db] → [db ops]`, `redo [db] → [db ops]`, `finish-gesture [db before] → [db ops]`.
  `db` here is any map with `:objects` and `:history {:undo [] :redo []}`.

- [ ] **Step 1: Write the failing tests**

`test/dingsbums/model_test.cljs`:
```clojure
(ns dingsbums.model-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing]]
            [dingsbums.model :as m]))

(def a {:id "a" :kind :sticky :x 0 :y 0 :w 10 :h 10 :z 1 :fill "#fff176" :text "x"})
(def b {:id "b" :kind :shape :shape :rect :x 50 :y 0 :w 10 :h 10 :z 2})
(def ab {:id "ab" :kind :connection :from "a" :to "b" :z 3})
(def objs {"a" a "b" b "ab" ab})
(def db {:objects objs :history {:undo [] :redo []}})

(deftest make-assigns-id-z-and-kind-defaults
  (let [o (m/make objs :sticky {:x 1 :y 2 :w 3 :h 4} nil)]
    (is (string? (:id o)))
    (is (= 4 (:z o)))
    (is (= {:kind :sticky :x 1 :y 2 :w 3 :h 4 :fill "#fff176" :text ""} (dissoc o :id :z))))
  (is (= {:kind :connection :from "a" :to "b"} (dissoc (m/make objs :connection nil {:from "a" :to "b"}) :id :z)))
  (is (= 1 (:z (m/make {} :text {:x 0 :y 0 :w 1 :h 1} nil)))))

(deftest groups
  (let [grouped (assoc objs "a" (assoc a :group "g") "b" (assoc b :group "g"))]
    (is (= #{"a" "b"} (m/expand-groups grouped ["a"])))
    (is (= #{"ab"} (m/expand-groups grouped ["ab"])))
    (is (= {"a" a} (m/ungroup-changes (assoc objs "a" (assoc a :group "g")) #{"a" "b"}))))
  (let [ch (m/group-changes objs #{"a" "b" "ab"})]
    (is (= #{"a" "b"} (set (keys ch))) "connections are not grouped")
    (is (= 1 (count (set (map :group (vals ch))))))
    (is (string? (:group (ch "a")))))
  (is (empty? (m/group-changes objs #{"a"})) "needs two objects"))

(deftest locks-protect-objects
  (let [locked (assoc objs "a" (assoc a :locked? true))]
    (is (= {"a" (assoc a :locked? true) "b" (assoc b :locked? true)} (m/lock-changes objs #{"a" "b"} true)))
    (is (= {"a" a} (m/lock-changes locked #{"a"} false)))
    (is (= {"b" nil "ab" nil} (m/delete-changes locked #{"a" "b"})) "locked a stays; the connection goes with b")
    (is (= {"b" (assoc b :fill "#e57373")} (m/fill-changes locked #{"a" "b" "ab"} "#e57373")) "locked and unfillable skipped")
    (is (= {} (m/fill-changes objs #{"a"} nil)) "a sticky keeps a fill")
    (is (= #{"b"} (set (keys (m/movable locked #{"a" "b" "ab"})))))))

(deftest text-changes
  (let [t {:id "t" :kind :text :text "x"}]
    (is (= {"a" (assoc a :text "hi")} (m/text-changes objs "a" "hi")))
    (is (= {"t" nil} (m/text-changes {"t" t} "t" "  ")) "an emptied text object is deleted")
    (is (= {"a" (assoc a :text "")} (m/text-changes objs "a" "")) "an emptied sticky stays")
    (is (= {} (m/text-changes {"t" t} "t" "x")) "unchanged")
    (is (= {} (m/text-changes {} "t" "x")) "object deleted meanwhile")
    (is (= {} (m/text-changes {"t" (assoc t :locked? true)} "t" "y")) "locked")
    (is (= {} (m/text-changes objs "b" "y")) "shapes have no text")))

(deftest commit-records-and-emits-ops
  (let [c {:id "c" :kind :text :text "c"}
        [db2 ops] (m/commit db {"c" c "ab" nil})]
    (is (= (-> objs (dissoc "ab") (assoc "c" c)) (:objects db2)))
    (is (= [[:op/upsert [c]] [:op/delete ["ab"]]] ops))
    (is (= [{:before {"c" nil "ab" ab} :after {"c" c "ab" nil}}] (get-in db2 [:history :undo]))))
  (is (= [db []] (m/commit db {})))
  (is (= [db []] (m/commit db {"a" a "zz" nil})) "changes equal to the current state are dropped"))

(deftest undo-redo
  (let [[db2] (m/commit db {"a" (assoc a :x 99)})
        [db3 ops3] (m/undo db2)
        [db4 ops4] (m/redo db3)]
    (is (= objs (:objects db3)))
    (is (= [[:op/upsert [a]]] ops3))
    (is (= 99 (get-in db4 [:objects "a" :x])))
    (is (= [[:op/upsert [(assoc a :x 99)]]] ops4))
    (is (= [db []] (m/undo db)) "nothing to undo")
    (testing "a new action clears redo"
      (let [[db5] (m/commit db3 {"b" (assoc b :x 1)})]
        (is (empty? (get-in db5 [:history :redo])))))
    (testing "undoing a create deletes, redo recreates"
      (let [c {:id "c" :kind :text}
            [d] (m/commit db {"c" c})
            [d ops] (m/undo d)]
        (is (= [[:op/delete ["c"]]] ops))
        (is (= [[:op/upsert [c]]] (second (m/redo d))))))))

(deftest history-is-capped-at-100
  (let [d (reduce (fn [d i] (first (m/commit d {"a" (assoc a :x i)}))) db (range 150))
        d (nth (iterate (comp first m/undo) d) 100)]
    (is (= 49 (get-in d [:objects "a" :x])) "the oldest 50 entries are gone")
    (is (= [d []] (m/undo d)))))

(deftest a-gesture-is-one-history-entry
  (let [start (m/movable objs #{"a" "b"})
        db2 (-> db
                (update :objects m/apply-changes (m/moved start 5 5))
                (update :objects m/apply-changes (m/moved start 10 0)))
        [db3 ops] (m/finish-gesture db2 start)]
    (is (= 1 (count (get-in db3 [:history :undo]))))
    (is (= :op/upsert (ffirst ops)))
    (is (= #{(assoc a :x 10) (assoc b :x 60)} (set (second (first ops)))))
    (is (= start (:before (peek (get-in db3 [:history :undo])))))
    (is (= objs (:objects (first (m/undo db3)))))
    (is (= [db []] (m/finish-gesture db start)) "no movement, no entry")))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `dingsbums.model` not available.

- [ ] **Step 3: Implement**

`src/dingsbums/model.cljs`:
```clojure
(ns dingsbums.model
  "Pure board model: constructors, groups, locks and the undo history. A change
  set is {id obj-or-nil}; nil means the object is removed. A history entry is
  {:before changes :after changes} over the same ids."
  (:require [clojure.string :as str]
            [dingsbums.ops :as ops]))

(def default-size {:text [200 50] :frame [400 300] :shape [120 120] :sticky [200 200]})
(def sticky-fill "#fff176")
(def swatches [nil "#fff176" "#ffb74d" "#e57373" "#81c784" "#64b5f6" "#ba68c8" "#ffffff" "#000000"])
(def history-cap 100)
(def text-kinds #{:text :sticky})

(defn new-id [] (str (random-uuid)))

(defn next-z [objects] (inc (reduce max 0 (keep :z (vals objects)))))

(defn make
  "A new object of kind: fresh id, top z, rect (nil for connections), kind defaults, then extra."
  [objects kind rect extra]
  (merge {:id (new-id) :kind kind :z (next-z objects)}
         (select-keys rect [:x :y :w :h])
         (case kind :sticky {:fill sticky-fill :text ""} :text {:text ""} {})
         extra))

(defn expand-groups
  "ids plus every object sharing a group with one of them."
  [objects ids]
  (let [groups (into #{} (keep #(:group (get objects %))) ids)]
    (into (set ids) (keep (fn [[id o]] (when (contains? groups (:group o)) id))) objects)))

(defn- unlocked [objects ids]
  (keep #(let [o (get objects %)] (when (and o (not (:locked? o))) o)) ids))

(defn movable
  "{id obj} of the unlocked, non-connection objects among ids."
  [objects ids]
  (into {} (comp (remove #(= :connection (:kind %))) (map (juxt :id identity))) (unlocked objects ids)))

(defn moved [start dx dy]
  (update-vals start #(-> % (update :x + dx) (update :y + dy))))

(defn group-changes [objects ids]
  (let [os (remove #(= :connection (:kind %)) (keep objects ids))]
    (if (< (count os) 2)
      {}
      (let [g (new-id)] (into {} (map (fn [o] [(:id o) (assoc o :group g)])) os)))))

(defn ungroup-changes [objects ids]
  (into {} (keep (fn [id] (when-let [o (get objects id)] (when (:group o) [id (dissoc o :group)])))) ids))

(defn lock-changes [objects ids locked?]
  (into {} (keep (fn [id] (when-let [o (get objects id)]
                            [id (if locked? (assoc o :locked? true) (dissoc o :locked?))])))
        ids))

(defn delete-changes
  "Removes the unlocked objects among ids and the connections touching them."
  [objects ids]
  (into {} (comp (filter #(contains? objects %)) (map (fn [id] [id nil])))
        (ops/cascade-ids objects (map :id (unlocked objects ids)))))

(defn fill-changes
  "Sets the fill of unlocked shapes and stickies; nil (no fill) only for shapes."
  [objects ids color]
  (into {} (keep (fn [o] (when (and (#{:shape :sticky} (:kind o)) (or color (= :shape (:kind o))))
                           [(:id o) (assoc o :fill color)])))
        (unlocked objects ids)))

(defn text-changes
  "Sets the text of object id; an emptied :text object is removed."
  [objects id text]
  (let [o (get objects id)]
    (cond
      (or (nil? o) (:locked? o) (not (text-kinds (:kind o)))) {}
      (and (= :text (:kind o)) (str/blank? text)) {id nil}
      (= text (:text o)) {}
      :else {id (assoc o :text text)})))

(defn apply-changes [objects changes]
  (reduce-kv (fn [m id o] (if o (assoc m id o) (dissoc m id))) objects changes))

(defn ops-for
  "The tube events that apply changes on the server and the other clients."
  [changes]
  (let [ups (vec (keep val changes))
        dels (vec (keep (fn [[id o]] (when (nil? o) id)) changes))]
    (cond-> [] (seq ups) (conj [:op/upsert ups]) (seq dels) (conj [:op/delete dels]))))

(defn- push-entry [history entry]
  {:undo (vec (take-last history-cap (conj (:undo history []) entry))) :redo []})

(defn commit
  "[db ops]: changes applied to (:objects db) and recorded as one history entry.
  Changes equal to the current state are dropped; if none remain, nothing happens."
  [db changes]
  (let [objects (:objects db)
        changes (into {} (remove (fn [[id o]] (= o (get objects id)))) changes)]
    (if (empty? changes)
      [db []]
      [(-> db
           (update :history push-entry {:before (into {} (map (fn [id] [id (get objects id)])) (keys changes))
                                        :after changes})
           (update :objects apply-changes changes))
       (ops-for changes)])))

(defn- step [db from to k]
  (if-let [e (peek (get-in db [:history from]))]
    (let [changes (k e)]
      [(-> db
           (update-in [:history from] pop)
           (update-in [:history to] (fnil conj []) e)
           (update :objects apply-changes changes))
       (ops-for changes)])
    [db []]))

(defn undo [db] (step db :undo :redo :before))
(defn redo [db] (step db :redo :undo :after))

(defn finish-gesture
  "[db ops] for a drag/resize that started from `before` ({id obj}) and has
  already been applied to (:objects db): one history entry, final upserts."
  [db before]
  (let [after (into {} (map (fn [id] [id (get-in db [:objects id])])) (keys before))]
    (if (= before after)
      [db []]
      [(update db :history push-entry {:before before :after after}) (ops-for after)])))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
git add src/dingsbums/model.cljs test/dingsbums/model_test.cljs
git commit -m "Frontend: board model and undo history" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Events, sync and interaction logic

**Files:**
- Create: `src/dingsbums/events.cljs`
- Test: `test/dingsbums/events_test.cljs`

**Interfaces:**
- Consumes: Task 4 `geom`, Task 5 `model`, Task 1 `ops/upsert`, `ops/delete`; hammer `reg-event`, `reg-fx`, `dispatch`, `hammer.http` (`:http`), `hammer.tubes` (`::create`, `::destroy`, `send!`).
- Produces: `initial-db`; `key-action [{:key :ctrl? :shift?}] → event|nil`; every handler below as a plain fn `(db & args) → effect map|nil`, registered under the event id in brackets. Pointer event maps are `{:sx :sy :button :shift? :t}` (canvas-local CSS px, ms).

| Event | Fn | Event | Fn |
|---|---|---|---|
| `:route/changed sid` | `route-changed` | `:pointer/down pe` | `pointer-down` |
| `:session/exists sid _` | `session-exists` | `:pointer/move pe` | `pointer-move` |
| `:session/not-found _` | `session-not-found` | `:pointer/up pe` | `pointer-up` |
| `:session/missing` | `session-missing` | `:pointer/dblclick pe` | `dblclick` |
| `:session/created {:id}` | `session-created` | `:edit/start` | `edit-start` |
| `:session/copy-id` | `copy-id` | `:edit/input s` | `edit-input` |
| `:session/snapshot objs` | `snapshot` | `:edit/commit` | `edit-commit` |
| `:op/upsert objs` | `remote-upsert` | `:edit/cancel` | `edit-cancel` |
| `:op/delete ids` | `remote-delete` | `:selection/fill c` | `selection-fill` |
| `:tube/connected` | `tube-connected` | `:selection/lock bool` | `selection-lock` |
| `:tube/disconnected` | `tube-disconnected` | `:selection/group` | `selection-group` |
| `:landing/input s` | `landing-input` | `:selection/ungroup` | `selection-ungroup` |
| `:landing/create` | `landing-create` | `:selection/delete` | `selection-delete` |
| `:landing/join` | `landing-join` | `:selection/clear` | `selection-clear` |
| `:landing/failed _` | `landing-failed` | `:history/undo` | `history-undo` |
| `:tool/select k` | `select-tool` | `:history/redo` | `history-redo` |
| `:tool/shape k` | `select-shape` | `:paste/text s` | `paste-text` |
| `:camera/pan dx dy` | `camera-pan` | `:paste/image src w h` | `paste-image` |
| `:camera/zoom f sx sy` | `camera-zoom` | `:import/file file` | `import-file` |
| `:viewport w h` | `set-viewport` | `:import/done _` | `import-done` |
| `:space bool` | `set-space` | `:import/failed _` | `import-failed` |
| `:image/loaded` | `image-loaded` | | |

- fx: `:tube/send [ev ...]`, `:set-hash s`, `:clipboard/write s`.
- db keys (used by Task 7 views/board): `:route` (`:landing`/`:checking`/`:board`), `:session`, `:join-input`, `:error`, `:message`, `:online?`, `:objects`, `:history`, `:selection` (set), `:tool`, `:shape-kind`, `:camera`, `:drag` (`{:type :pan|:create|:connect|:band|:move|:resize ...}`), `:editing` (`{:id :draft}`), `:space?`, `:img-tick`, `:viewport` `[w h]`.

- [ ] **Step 1: Write the failing tests**

`test/dingsbums/events_test.cljs`:
```clojure
(ns dingsbums.events-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [hammer.core :refer [mount! dispatch-sync]]
            [hammer.state :as state]
            [hammer.testing :as t]
            [hammer.tubes :as tubes]
            [dingsbums.events :as ev]))

(def board (assoc ev/initial-db :route :board :session "s1"))
(def sticky {:id "a" :kind :sticky :x 0 :y 0 :w 100 :h 100 :z 1 :fill "#fff176" :text "hi"})
(def shape {:id "b" :kind :shape :shape :rect :x 200 :y 0 :w 100 :h 100 :z 2})
(def with-objs (assoc board :objects {"a" sticky "b" shape}))

(defn- pe [sx sy & {:as opts}] (merge {:sx sx :sy sy :button 0 :shift? false :t 0} opts))

(defn- drag
  "Effect map of pointer-up after down at [x1 y1] and a move to [x2 y2]."
  [db [x1 y1] [x2 y2]]
  (let [db (:db (ev/pointer-down db (pe x1 y1)))
        db (or (:db (ev/pointer-move db (pe x2 y2 :t 100))) db)]
    (ev/pointer-up db (pe x2 y2 :t 100))))

(defn- objects-of [fx] (vals (get-in fx [:db :objects])))

(deftest key-actions
  (is (= [:history/undo] (ev/key-action {:key "z" :ctrl? true})))
  (is (= [:history/redo] (ev/key-action {:key "Z" :ctrl? true :shift? true})))
  (is (= [:history/redo] (ev/key-action {:key "y" :ctrl? true})))
  (is (= [:selection/group] (ev/key-action {:key "g" :ctrl? true})))
  (is (= [:selection/ungroup] (ev/key-action {:key "G" :ctrl? true :shift? true})))
  (is (= [:edit/start] (ev/key-action {:key "F2"})))
  (is (= [:selection/delete] (ev/key-action {:key "Delete"})))
  (is (= [:selection/delete] (ev/key-action {:key "Backspace"})))
  (is (= [:selection/clear] (ev/key-action {:key "Escape"})))
  (is (= [:space true] (ev/key-action {:key " "})))
  (is (nil? (ev/key-action {:key "c" :ctrl? true})) "browser copy untouched")
  (is (nil? (ev/key-action {:key "a"}))))

(deftest routing
  (let [fx (ev/route-changed with-objs "")]
    (is (= :landing (get-in fx [:db :route])))
    (is (= {} (get-in fx [:db :objects])))
    (is (contains? fx :hammer.tubes/destroy)))
  (let [fx (ev/route-changed ev/initial-db " abc ")]
    (is (= [:checking "abc"] ((juxt :route :session) (:db fx))))
    (is (= "/api/sessions/abc" (get-in fx [:http :uri])))
    (is (= [:session/exists "abc"] (get-in fx [:http :on-success]))))
  (is (nil? (ev/route-changed board "s1")) "already on that board")
  (is (nil? (ev/session-exists (assoc board :session "other") "s1")) "stale answer for a session we left")
  (is (= {:url "ws://localhost/ws" :params {:session "s1"}}
         (select-keys (:hammer.tubes/create (ev/session-exists (assoc board :route :checking) "s1")) [:url :params])))
  (let [fx (ev/session-not-found (assoc board :route :checking))]
    (is (= [:landing "Session not found"] ((juxt :route :error) (:db fx))))
    (is (= "" (:set-hash fx))))
  (let [fx (ev/session-missing with-objs)]
    (is (= [:landing "Session expired or not found" {}] ((juxt :route :error :objects) (:db fx))))
    (is (contains? fx :hammer.tubes/destroy)))
  (is (= "x" (:set-hash (ev/landing-join (assoc ev/initial-db :join-input " x ")))))
  (is (nil? (ev/landing-join (assoc ev/initial-db :join-input "  "))))
  (is (= "new" (:set-hash (ev/session-created ev/initial-db {:id "new"})))))

(deftest creating-objects
  (testing "click creates a default-size object centered on the point"
    (let [fx (drag (assoc board :tool :sticky) [300 300] [300 300])
          [o] (objects-of fx)]
      (is (= {:kind :sticky :x 200 :y 200 :w 200 :h 200} (select-keys o [:kind :x :y :w :h])))
      (is (= :select (get-in fx [:db :tool])))
      (is (= #{(:id o)} (get-in fx [:db :selection])))
      (is (= [[:op/upsert [o]]] (:tube/send fx)))))
  (testing "drag spans the rect; text starts editing"
    (let [fx (drag (assoc board :tool :text) [10 10] [110 60])
          [o] (objects-of fx)]
      (is (= {:kind :text :x 10 :y 10 :w 100 :h 50} (select-keys o [:kind :x :y :w :h])))
      (is (= {:id (:id o) :draft ""} (get-in fx [:db :editing])))))
  (testing "shapes take the picked shape"
    (is (= :star (:shape (first (objects-of (drag (assoc board :tool :shape :shape-kind :star) [0 0] [0 0]))))))
    (is (= :circle (get-in (ev/select-shape board :circle) [:db :shape-kind]))))
  (testing "connection from a to b; released on nothing it cancels"
    (let [c (first (filter #(= :connection (:kind %))
                           (objects-of (drag (assoc with-objs :tool :connection) [50 50] [250 50]))))]
      (is (= ["a" "b"] ((juxt :from :to) c))))
    (is (= 2 (count (objects-of (drag (assoc with-objs :tool :connection) [50 50] [900 900])))))))

(deftest selecting-and-moving
  (testing "drag moves, sends at most every 33 ms, one history entry"
    (let [db (:db (ev/pointer-down with-objs (pe 10 10)))
          fx1 (ev/pointer-move db (pe 20 10 :t 10))
          fx2 (ev/pointer-move (:db fx1) (pe 30 10 :t 40))
          fx3 (ev/pointer-up (:db fx2) (pe 30 10 :t 50))]
      (is (= #{"a"} (:selection db)))
      (is (nil? (:tube/send fx1)) "throttled")
      (is (= [[:op/upsert [(assoc sticky :x 20)]]] (:tube/send fx2)))
      (is (= [[:op/upsert [(assoc sticky :x 20)]]] (:tube/send fx3)) "final position on pointer-up")
      (is (= 1 (count (get-in fx3 [:db :history :undo]))))))
  (testing "locked objects are selected but stay put"
    (let [fx (drag (assoc-in with-objs [:objects "a" :locked?] true) [10 10] [60 60])]
      (is (= #{"a"} (get-in fx [:db :selection])))
      (is (= 0 (get-in fx [:db :objects "a" :x])))
      (is (nil? (:tube/send fx)))))
  (testing "clicking a group member selects the group; shift toggles"
    (let [db (-> with-objs (assoc-in [:objects "a" :group] "g") (assoc-in [:objects "b" :group] "g"))]
      (is (= #{"a" "b"} (:selection (:db (ev/pointer-down db (pe 10 10))))))
      (is (= #{} (:selection (:db (ev/pointer-down (assoc db :selection #{"a" "b"}) (pe 10 10 :shift? true))))))))
  (testing "rubber band"
    (is (= #{"a"} (get-in (drag with-objs [-10 -10] [150 150]) [:db :selection])))
    (is (= #{} (get-in (drag (assoc with-objs :selection #{"a"}) [500 500] [500 500]) [:db :selection]))
        "click on empty clears"))
  (testing "corner handle resizes the single selected object"
    (let [fx (drag (assoc with-objs :selection #{"a"}) [100 100] [150 120])]
      (is (= {:x 0 :y 0 :w 150 :h 120} (select-keys (get-in fx [:db :objects "a"]) [:x :y :w :h])))
      (is (= 1 (count (get-in fx [:db :history :undo])))))))

(deftest navigation
  (let [db (:db (ev/pointer-down board (pe 100 100 :button 1)))
        db (:db (ev/pointer-move db (pe 150 120)))]
    (is (= {:x -50 :y -20 :zoom 1} (:camera db)) "middle-drag pans"))
  (is (= :pan (get-in (ev/pointer-down (assoc board :space? true) (pe 0 0)) [:db :drag :type])) "space+drag pans")
  (is (nil? (ev/pointer-down board (pe 0 0 :button 2))) "right button ignored")
  (is (= {:x 10 :y 20 :zoom 1} (get-in (ev/camera-pan board 10 20) [:db :camera])) "wheel scrolls")
  (is (= 2 (get-in (ev/camera-zoom board 2 0 0) [:db :camera :zoom]))))

(deftest text-editing
  (let [fx (ev/dblclick with-objs (pe 10 10))
        db (:db (ev/edit-input (:db fx) "hello"))
        fx2 (ev/edit-commit db)]
    (is (= {:id "a" :draft "hi"} (get-in fx [:db :editing])))
    (is (= "hello" (get-in fx2 [:db :objects "a" :text])))
    (is (nil? (get-in fx2 [:db :editing])))
    (is (= [[:op/upsert [(assoc sticky :text "hello")]]] (:tube/send fx2)))
    (is (= "hi" (get-in (ev/edit-cancel db) [:db :objects "a" :text])) "Esc aborts"))
  (is (nil? (ev/dblclick (assoc-in with-objs [:objects "a" :locked?] true) (pe 10 10))) "locked: no edit")
  (is (nil? (ev/dblclick with-objs (pe 210 10))) "shapes have no text")
  (is (= {:id "a" :draft "hi"} (get-in (ev/edit-start (assoc with-objs :selection #{"a"})) [:db :editing])) "F2")
  (testing "clicking the canvas commits the edit"
    (let [fx (ev/pointer-down (assoc with-objs :editing {:id "a" :draft "new"}) (pe 900 900))]
      (is (= "new" (get-in fx [:db :objects "a" :text])))
      (is (= [[:op/upsert [(assoc sticky :text "new")]]] (:tube/send fx)))))
  (testing "an aborted new text object is removed"
    (is (empty? (objects-of (ev/edit-cancel (:db (drag (assoc board :tool :text) [0 0] [0 0]))))))))

(deftest selection-actions
  (let [db (assoc with-objs :selection #{"a" "b"})]
    (let [os (objects-of (ev/selection-group db))]
      (is (= 1 (count (set (map :group os)))))
      (is (every? :group os)))
    (let [fx (ev/selection-delete db)]
      (is (empty? (get-in fx [:db :objects])))
      (is (= #{} (get-in fx [:db :selection])))
      (is (= #{"a" "b"} (set (second (first (:tube/send fx)))))))
    (is (every? :locked? (objects-of (ev/selection-lock db true))))
    (is (= "#e57373" (get-in (ev/selection-fill db "#e57373") [:db :objects "b" :fill])))
    (is (= {:selection #{} :tool :select} (select-keys (:db (ev/selection-clear (assoc db :tool :text))) [:selection :tool])))))

(deftest undo-redo-events
  (let [fx (ev/selection-delete (assoc with-objs :selection #{"a"}))
        fx2 (ev/history-undo (:db fx))
        fx3 (ev/history-redo (:db fx2))]
    (is (= sticky (get-in fx2 [:db :objects "a"])))
    (is (= [[:op/upsert [sticky]]] (:tube/send fx2)))
    (is (= [[:op/delete ["a"]]] (:tube/send fx3)))
    (is (= #{} (get-in fx3 [:db :selection])))))

(deftest remote-events
  (let [db (assoc with-objs :selection #{"a" "b"} :history {:undo [{:before {} :after {}}] :redo []})
        db2 (:db (ev/snapshot db {"b" shape}))]
    (is (= {"b" shape} (:objects db2)))
    (is (= #{"b"} (:selection db2)))
    (is (= {:undo [] :redo []} (:history db2)))
    (is (= #{"b"} (:selection (:db (ev/remote-delete db ["a"])))))
    (is (= 5 (get-in (ev/remote-upsert db [(assoc sticky :x 5)]) [:db :objects "a" :x])))
    (is (nil? (get-in (ev/remote-delete (assoc db :editing {:id "a" :draft "x"}) ["a"]) [:db :editing]))
        "an edit of a remotely deleted object is dropped")))

(deftest pasting
  (let [o (first (objects-of (ev/paste-text (assoc board :viewport [800 600]) "hello")))]
    (is (= {:kind :text :text "hello" :x 250 :y 250 :w 300 :h 100} (select-keys o [:kind :text :x :y :w :h]))))
  (let [o (first (objects-of (ev/paste-image (assoc board :viewport [800 600]) "data:image/png;base64,AA" 1200 300)))]
    (is (= [600 150] [(:w o) (:h o)]) "scaled to fit 600×600"))
  (is (= "Image too large (max 15 MB)"
         (get-in (ev/paste-image board (apply str (repeat 15000001 "a")) 10 10) [:db :message])))
  (is (nil? (ev/paste-text ev/initial-db "x")) "ignored on the landing screen"))

;; tube wiring, through hammer with a fake WebSocket

(def sockets (atom []))

(defn- fake-ws [url]
  (let [ws #js {:url url :sent #js [] :readyState 0}]
    (set! (.-send ws) (fn [s] (.push (.-sent ws) s)))
    (set! (.-close ws) (fn [] (set! (.-readyState ws) 3)))
    (swap! sockets conj ws)
    ws))

(defn- push! [^js ws ev] ((.-onmessage ws) #js {:data (pr-str ev)}))

(use-fixtures :each {:before #(do (t/reset-app!) (reset! sockets []) (tubes/set-websocket! fake-ws))
                     :after #(do (t/reset-app!) (tubes/set-websocket! nil))})

(deftest tube-sync
  (mount! [:div] (js/document.createElement "div") (assoc ev/initial-db :route :checking :session "s1"))
  (dispatch-sync [:session/exists "s1" nil])
  (let [^js ws (first @sockets)
        sent #(vec (.-sent ws))]
    (is (= "ws://localhost/ws?session=s1" (.-url ws)))
    (set! (.-readyState ws) 1)
    ((.-onopen ws) #js {})
    (t/flush!)
    (is (= ["[:session/hello]"] (sent)) "hello goes out after any queued ops")
    (is (:online? @state/app-db))
    (push! ws [:session/snapshot {"a" sticky}])
    (push! ws [:evil/event 1])
    (t/flush!)
    (is (= {"a" sticky} (:objects @state/app-db)) "snapshot applied; the unknown event was dropped")
    (dispatch-sync [:pointer/down (pe 10 10)])
    (dispatch-sync [:pointer/up (pe 10 10)])
    (dispatch-sync [:selection/delete])
    (is (= "[:op/delete [\"a\"]]" (last (sent))))
    (dispatch-sync [:history/undo])
    (is (= (pr-str [:op/upsert [sticky]]) (last (sent))))
    (t/check-errors!)))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: FAIL — `dingsbums.events` not available.

- [ ] **Step 3: Implement**

`src/dingsbums/events.cljs`:
```clojure
(ns dingsbums.events
  "Event handlers. Each is a plain fn (db & args) → effect map (nil: nothing),
  registered at the bottom, so tests call them directly. Local mutations go
  through model/commit and send their ops with :tube/send."
  (:require [clojure.string :as str]
            [hammer.core :refer [reg-event reg-fx dispatch]]
            [hammer.http]
            [hammer.tubes :as tubes]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.ops :as ops]))

(def initial-db
  {:route :landing :session nil :join-input "" :error nil :message nil :online? false
   :objects {} :history {:undo [] :redo []} :selection #{} :tool :select :shape-kind :rect
   :camera {:x 0 :y 0 :zoom 1} :drag nil :editing nil :space? false :img-tick 0
   :viewport [1024 768]})

(def ^:private board-reset
  (select-keys initial-db [:objects :history :selection :tool :camera :drag :editing :message :online?]))

(def send-interval 33)
(def hit-px 6)
(def max-image-chars 15000000)
(def remote-events #{:op/upsert :op/delete :session/snapshot :session/missing})
(def creating-tools #{:text :frame :shape :sticky})

(reg-fx :tube/send (fn [evs] (run! tubes/send! evs)))
(reg-fx :set-hash (fn [h] (set! (.-hash js/location) h)))
(reg-fx :clipboard/write (fn [s] (some-> js/navigator .-clipboard (.writeText s) (.catch (fn [_] nil)))))

(defn- with-ops [db ops] (cond-> {:db db} (seq ops) (assoc :tube/send ops)))

(defn- commit [db changes] (let [[db ops] (model/commit db changes)] (with-ops db ops)))

(defn- prune
  "db with selection and edit limited to objects that still exist."
  [db]
  (let [objects (:objects db)]
    (-> db
        (update :selection #(into #{} (filter (partial contains? objects)) %))
        (update :editing #(when (and % (contains? objects (:id %))) %)))))

(defn ws-url []
  (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/ws"))

(defn receive
  "Tube :on-receive: dispatch only the events the server may send."
  [ev]
  (when (contains? remote-events (first ev)) (dispatch ev)))

;; routing and session

(defn route-changed [db sid]
  (let [sid (str/trim (or sid ""))]
    (cond
      (str/blank? sid)
      {:db (merge db board-reset {:route :landing :session nil}) :hammer.tubes/destroy {}}

      (and (= sid (:session db)) (= :board (:route db)))
      nil

      :else
      {:db (merge db board-reset {:route :checking :session sid :error nil})
       :hammer.tubes/destroy {}
       :http {:uri (str "/api/sessions/" (js/encodeURIComponent sid)) :response-format :text
              :on-success [:session/exists sid] :on-failure [:session/not-found]}})))

(defn session-exists [db sid & _]
  (when (= sid (:session db))
    {:db (assoc db :route :board)
     :hammer.tubes/create {:url (ws-url) :params {:session sid} :on-receive receive
                           :on-connect [:tube/connected] :on-disconnect [:tube/disconnected]}}))

(defn session-not-found [db & _]
  {:db (assoc db :route :landing :session nil :error "Session not found") :set-hash ""})

(defn session-missing [db]
  {:db (merge db board-reset {:route :landing :session nil :error "Session expired or not found"})
   :hammer.tubes/destroy {}
   :set-hash ""})

(defn landing-input [db s] {:db (assoc db :join-input s)})

(defn landing-create [_]
  {:http {:method :post :uri "/api/sessions" :on-success [:session/created] :on-failure [:landing/failed]}})

(defn session-created [db {:keys [id]}] {:db (assoc db :error nil) :set-hash id})

(defn landing-failed [db & _] {:db (assoc db :error "Could not create a session")})

(defn landing-join [db]
  (let [sid (str/trim (:join-input db))]
    (when-not (str/blank? sid) {:db (assoc db :error nil) :set-hash sid})))

(defn copy-id [db] {:db (assoc db :message "Copied") :clipboard/write (:session db)})

;; sync

(defn tube-connected [db] {:db (assoc db :online? true) :tube/send [[:session/hello]]})
(defn tube-disconnected [db] {:db (assoc db :online? false)})

(defn snapshot [db objects]
  {:db (prune (assoc db :objects objects :history {:undo [] :redo []} :drag nil))})

(defn remote-upsert [db objs] {:db (update db :objects ops/upsert objs)})
(defn remote-delete [db ids] {:db (prune (update db :objects ops/delete ids))})

;; tools, camera, misc

(defn select-tool [db tool] {:db (assoc db :tool tool)})
(defn select-shape [db shape] {:db (assoc db :tool :shape :shape-kind shape)})
(defn camera-pan [db dx dy] {:db (update db :camera geom/pan (- dx) (- dy))})
(defn camera-zoom [db factor sx sy] {:db (update db :camera geom/zoom-at factor [sx sy])})
(defn set-viewport [db w h] {:db (assoc db :viewport [w h])})
(defn set-space [db down?] {:db (assoc db :space? down?)})
(defn image-loaded [db] {:db (update db :img-tick inc)})

;; pointer

(defn- world [db {:keys [sx sy]}] (geom/screen->world (:camera db) [sx sy]))
(defn- tol [db px] (/ px (get-in db [:camera :zoom])))
(defn- hit-at [db p] (geom/hit (:objects db) p (tol db hit-px)))

(defn- finish-edit
  "[db ops] with an active text edit committed."
  [db]
  (if-let [{:keys [id draft]} (:editing db)]
    (model/commit (assoc db :editing nil) (model/text-changes (:objects db) id draft))
    [db []]))

(defn- resize-target
  "[obj corner] when p is on a handle of the single selected, unlocked, non-connection object."
  [db p]
  (let [sel (:selection db)
        o (when (= 1 (count sel)) (get-in db [:objects (first sel)]))]
    (when (and o (not (:locked? o)) (not= :connection (:kind o)))
      (when-let [k (geom/handle-at o p (tol db geom/handle-px))] [o k]))))

(defn- press [db {:keys [sx sy button shift? t]} p]
  (let [tool (:tool db) hit (hit-at db p) sel (:selection db)]
    (cond
      (or (= button 1) (:space? db)) (assoc db :drag {:type :pan :last [sx sy]})
      (creating-tools tool) (assoc db :drag {:type :create :start p :current p})
      (= tool :connection) (cond-> db
                             (and hit (not= :connection (:kind hit)))
                             (assoc :drag {:type :connect :from (:id hit) :current p}))
      :else
      (if-let [[o k] (resize-target db p)]
        (assoc db :drag {:type :resize :handle k :start {(:id o) o} :last-sent t})
        (if hit
          (let [ids (model/expand-groups (:objects db) [(:id hit)])]
            (if shift?
              (assoc db :selection (if (every? sel ids) (reduce disj sel ids) (into sel ids)))
              (let [sel (if (contains? sel (:id hit)) sel ids)]
                (assoc db :selection sel
                          :drag {:type :move :origin p :start (model/movable (:objects db) sel) :last-sent t}))))
          (assoc db :selection (if shift? sel #{})
                    :drag {:type :band :start p :current p :shift? shift?}))))))

(defn pointer-down [db {:keys [button] :as e}]
  (when (<= button 1)
    (let [[db ops] (finish-edit (assoc db :message nil))]
      (with-ops (press db e (world db e)) ops))))

(defn- live-update
  "Applies changes locally; sends them when the last send is send-interval ago."
  [db changes t]
  (let [db (update db :objects model/apply-changes changes)]
    (if (>= (- t (get-in db [:drag :last-sent])) send-interval)
      (with-ops (assoc-in db [:drag :last-sent] t) (model/ops-for changes))
      {:db db})))

(defn pointer-move [db {:keys [sx sy t] :as e}]
  (let [drag (:drag db) p (world db e)]
    (case (:type drag)
      :pan (let [[lx ly] (:last drag)]
             {:db (-> db (update :camera geom/pan (- sx lx) (- sy ly)) (assoc-in [:drag :last] [sx sy]))})
      (:create :band :connect) {:db (assoc-in db [:drag :current] p)}
      :move (let [[ox oy] (:origin drag) [px py] p]
              (live-update db (model/moved (:start drag) (- px ox) (- py oy)) t))
      :resize (let [[id o] (first (:start drag))]
                (live-update db {id (geom/resize o (:handle drag) p)} t))
      nil)))

(defn- create-object [db start end]
  (let [kind (:tool db)
        r (geom/rect-from-points start end)
        [dw dh] (model/default-size kind)
        rect (if (and (< (:w r) 5) (< (:h r) 5))
               {:x (- (first start) (/ dw 2)) :y (- (second start) (/ dh 2)) :w dw :h dh}
               (-> r (update :w max geom/min-size) (update :h max geom/min-size)))
        o (model/make (:objects db) kind rect (when (= kind :shape) {:shape (:shape-kind db)}))]
    (commit (assoc db :tool :select :selection #{(:id o)}
                      :editing (when (= kind :text) {:id (:id o) :draft ""}))
            {(:id o) o})))

(defn- connect [db from p]
  (let [hit (hit-at db p)]
    (if (and hit (not= :connection (:kind hit)) (not= (:id hit) from))
      (let [o (model/make (:objects db) :connection nil {:from from :to (:id hit)})]
        (commit (assoc db :tool :select :selection #{(:id o)}) {(:id o) o}))
      {:db db})))

(defn- band-select [db {:keys [start shift?]} p]
  (let [band (geom/rect-from-points start p)
        ids (when (or (> (:w band) 2) (> (:h band) 2)) (geom/ids-in-rect (:objects db) band))]
    {:db (assoc db :selection (into (if shift? (:selection db) #{}) (model/expand-groups (:objects db) ids)))}))

(defn pointer-up [db e]
  (when-let [drag (:drag db)]
    (let [db (assoc db :drag nil) p (world db e)]
      (case (:type drag)
        :create (create-object db (:start drag) p)
        :connect (connect db (:from drag) p)
        :band (band-select db drag p)
        (:move :resize) (let [[db ops] (model/finish-gesture db (:start drag))] (with-ops db ops))
        {:db db}))))

(defn dblclick [db e]
  (let [hit (hit-at db (world db e))]
    (when (and (= :select (:tool db)) hit (model/text-kinds (:kind hit)) (not (:locked? hit)))
      {:db (assoc db :selection #{(:id hit)} :editing {:id (:id hit) :draft (:text hit "")})})))

;; text editing

(defn edit-start [db]
  (let [sel (:selection db)
        o (when (= 1 (count sel)) (get-in db [:objects (first sel)]))]
    (when (and o (model/text-kinds (:kind o)) (not (:locked? o)))
      {:db (assoc db :editing {:id (:id o) :draft (:text o "")})})))

(defn edit-input [db s] (when (:editing db) {:db (assoc-in db [:editing :draft] s)}))

(defn edit-commit [db] (when (:editing db) (let [[db ops] (finish-edit db)] (with-ops db ops))))

(defn edit-cancel [db]
  (when-let [{:keys [id]} (:editing db)]
    (let [db (assoc db :editing nil)
          o (get-in db [:objects id])]
      (if (and (= :text (:kind o)) (str/blank? (:text o)))
        (commit db {id nil})
        {:db db}))))

;; selection

(defn- change-selection [db f & args]
  (update (commit db (apply f (:objects db) (:selection db) args)) :db prune))

(defn selection-fill [db color] (change-selection db model/fill-changes color))
(defn selection-lock [db locked?] (change-selection db model/lock-changes locked?))
(defn selection-group [db] (change-selection db model/group-changes))
(defn selection-ungroup [db] (change-selection db model/ungroup-changes))
(defn selection-delete [db] (change-selection db model/delete-changes))
(defn selection-clear [db] {:db (assoc db :selection #{} :tool :select)})

;; history

(defn- history [f db] (let [[db ops] (f (assoc db :drag nil))] (with-ops (prune db) ops)))
(defn history-undo [db] (history model/undo db))
(defn history-redo [db] (history model/redo db))

;; paste

(defn- place [db kind w h extra]
  (let [[vw vh] (:viewport db)
        [cx cy] (geom/screen->world (:camera db) [(/ vw 2) (/ vh 2)])
        o (model/make (:objects db) kind {:x (- cx (/ w 2)) :y (- cy (/ h 2)) :w w :h h} extra)]
    (commit (assoc db :tool :select :selection #{(:id o)}) {(:id o) o})))

(defn paste-text [db text]
  (when (= :board (:route db)) (place db :text 300 100 {:text text})))

(defn paste-image [db src w h]
  (when (= :board (:route db))
    (if (> (count src) max-image-chars)
      {:db (assoc db :message "Image too large (max 15 MB)")}
      (let [w (max 1 w) h (max 1 h) s (min 1 (/ 600 w) (/ 600 h))]
        (place db :image (* w s) (* h s) {:src src})))))

;; import

(defn import-file [db file]
  {:db (assoc db :message "Importing…")
   :http {:method :post :uri (str "/api/sessions/" (js/encodeURIComponent (:session db)) "/import")
          :body file :headers {"Content-Type" "application/x-tar"} :response-format :text
          :on-success [:import/done] :on-failure [:import/failed]}})

(defn import-done [db & _] {:db (assoc db :message nil)})
(defn import-failed [db & _] {:db (assoc db :message "Import failed: not a dingsbums export")})

;; keys

(defn key-action
  "The event for a key press outside text inputs, or nil to leave it to the browser."
  [{:keys [key ctrl? shift?]}]
  (let [k (str/lower-case key)]
    (cond
      (and ctrl? (= k "z")) (if shift? [:history/redo] [:history/undo])
      (and ctrl? (= k "y")) [:history/redo]
      (and ctrl? (= k "g")) (if shift? [:selection/ungroup] [:selection/group])
      ctrl? nil
      (= key "F2") [:edit/start]
      (#{"Delete" "Backspace"} key) [:selection/delete]
      (= key "Escape") [:selection/clear]
      (= key " ") [:space true])))

(doseq [[id f] {:route/changed route-changed
                :session/exists session-exists
                :session/not-found session-not-found
                :session/missing session-missing
                :session/created session-created
                :session/copy-id copy-id
                :session/snapshot snapshot
                :op/upsert remote-upsert
                :op/delete remote-delete
                :tube/connected tube-connected
                :tube/disconnected tube-disconnected
                :landing/input landing-input
                :landing/create landing-create
                :landing/join landing-join
                :landing/failed landing-failed
                :tool/select select-tool
                :tool/shape select-shape
                :camera/pan camera-pan
                :camera/zoom camera-zoom
                :viewport set-viewport
                :space set-space
                :image/loaded image-loaded
                :pointer/down pointer-down
                :pointer/move pointer-move
                :pointer/up pointer-up
                :pointer/dblclick dblclick
                :edit/start edit-start
                :edit/input edit-input
                :edit/commit edit-commit
                :edit/cancel edit-cancel
                :selection/fill selection-fill
                :selection/lock selection-lock
                :selection/group selection-group
                :selection/ungroup selection-ungroup
                :selection/delete selection-delete
                :selection/clear selection-clear
                :history/undo history-undo
                :history/redo history-redo
                :paste/text paste-text
                :paste/image paste-image
                :import/file import-file
                :import/done import-done
                :import/failed import-failed}]
  (reg-event id f))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: `0 failures, 0 errors.` If a test fails, use superpowers:systematic-debugging — do not weaken the assertion.

- [ ] **Step 5: Commit**

```bash
git add src/dingsbums/events.cljs test/dingsbums/events_test.cljs
git commit -m "Frontend: events, sync and interaction logic" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Board canvas, overlays and app shell

**Files:**
- Create: `src/dingsbums/board.cljs`, `src/dingsbums/views.cljs`, `src/dingsbums/core.cljs`
- Modify: `public/index.html` (full page + CSS)

**Interfaces:**
- Consumes: Task 6 event ids and db keys; Task 4 `geom`; Task 5 `model/swatches`.
- Produces: `dingsbums.board/board` (defdraw), `board/fit [text w h] → {:size :lines}`; `dingsbums.views/app` (root defc); `dingsbums.core/init` (shadow `:init-fn`), `core/reload` (`^:dev/after-load`).

No unit tests: this is the rendering shell. Verification is the `:advanced` build (no warnings) and Task 8 in the browser.

- [ ] **Step 1: `src/dingsbums/board.cljs`**

```clojure
(ns dingsbums.board
  "The board canvas: draws the objects through the camera and turns pointer
  input into events. Text is measured with an offscreen canvas."
  (:require [hammer.core :refer [dispatch]]
            [hammer.canvas :refer [defdraw]]
            [dingsbums.geom :as geom]))

(def ^:private font "px sans-serif")
(def ^:private select-color "#1e88e5")

(defonce ^:private measure-ctx (delay (.getContext (js/document.createElement "canvas") "2d")))

(defn measure [s size]
  (let [^js ctx @measure-ctx]
    (set! (.-font ctx) (str size font))
    (.-width (.measureText ctx s))))

(defonce ^:private fit-cache (js/Map.))

(defn fit
  "geom/fit-text with the canvas measure, cached by box size and text."
  [text w h]
  (let [k (str w "|" h "|" text)]
    (or (.get fit-cache k)
        (let [r (geom/fit-text measure text w h)]
          (when (> (.-size fit-cache) 5000) (.clear fit-cache))
          (.set fit-cache k r)
          r))))

(defonce ^:private images (js/Map.))

(defn- image
  "A cached <img> for src; :image/loaded redraws once it has loaded."
  [src]
  (or (.get images src)
      (let [img (js/Image.)]
        (set! (.-onload img) #(dispatch [:image/loaded]))
        (set! (.-src img) src)
        (.set images src img)
        img)))

(defn- path! [^js ctx o]
  (let [{:keys [x y w h]} o]
    (.beginPath ctx)
    (case (:shape o)
      :circle (.ellipse ctx (+ x (/ w 2)) (+ y (/ h 2)) (/ w 2) (/ h 2) 0 0 (* 2 js/Math.PI))
      (:star :arrow) (let [[[px py] & more] (geom/shape-points o)]
                       (.moveTo ctx px py)
                       (doseq [[qx qy] more] (.lineTo ctx qx qy))
                       (.closePath ctx))
      (.rect ctx x y w h))))

(defn- text! [^js ctx {:keys [x y w h text]}]
  (when (seq text)
    (let [{:keys [size lines]} (fit text w h)]
      (.save ctx)
      (.beginPath ctx)
      (.rect ctx x y w h)
      (.clip ctx)
      (set! (.-font ctx) (str size font))
      (set! (.-textBaseline ctx) "top")
      (set! (.-fillStyle ctx) "#000")
      (doseq [[i line] (map-indexed vector lines)]
        (.fillText ctx line (+ x geom/padding) (+ y geom/padding (* i size geom/line-height))))
      (.restore ctx))))

(defn- arrowhead! [^js ctx [ax ay] [bx by] size]
  (let [a (js/Math.atan2 (- by ay) (- bx ax))]
    (.beginPath ctx)
    (.moveTo ctx bx by)
    (.lineTo ctx (- bx (* size (js/Math.cos (- a 0.4)))) (- by (* size (js/Math.sin (- a 0.4)))))
    (.lineTo ctx (- bx (* size (js/Math.cos (+ a 0.4)))) (- by (* size (js/Math.sin (+ a 0.4)))))
    (.closePath ctx)
    (.fill ctx)))

(defn- object! [^js ctx objects o editing-id]
  (set! (.-lineWidth ctx) 2)
  (set! (.-strokeStyle ctx) "#000")
  (let [{:keys [x y w h]} o]
    (case (:kind o)
      :connection (when-let [[a] (geom/endpoints objects o)]
                    (let [[tx ty :as tip] (geom/border-point (get objects (:to o)) a)]
                      (.beginPath ctx)
                      (.moveTo ctx (first a) (second a))
                      (.lineTo ctx tx ty)
                      (.stroke ctx)
                      (set! (.-fillStyle ctx) "#000")
                      (arrowhead! ctx a tip 14)))
      :frame (.strokeRect ctx x y w h)
      :shape (do (path! ctx o)
                 (when-let [f (:fill o)] (set! (.-fillStyle ctx) f) (.fill ctx))
                 (.stroke ctx))
      :sticky (do (set! (.-fillStyle ctx) (:fill o "#fff176"))
                  (.fillRect ctx x y w h)
                  (.strokeRect ctx x y w h)
                  (when-not (= editing-id (:id o)) (text! ctx o)))
      :text (when-not (= editing-id (:id o)) (text! ctx o))
      :image (let [^js img (image (:src o))]
               (if (and (.-complete img) (pos? (.-naturalWidth img)))
                 (.drawImage ctx img x y w h)
                 (do (set! (.-fillStyle ctx) "#eee") (.fillRect ctx x y w h))))
      nil)))

(defn- selection! [^js ctx objects sel zoom]
  (set! (.-lineWidth ctx) (/ 1.5 zoom))
  (doseq [id sel
          :let [o (get objects id) r (when o (geom/obj-rect objects o))]
          :when r]
    (set! (.-strokeStyle ctx) (if (:locked? o) "#9e9e9e" select-color))
    (.setLineDash ctx (if (:locked? o) #js [(/ 4 zoom) (/ 4 zoom)] #js []))
    (.strokeRect ctx (- (:x r) (/ 3 zoom)) (- (:y r) (/ 3 zoom)) (+ (:w r) (/ 6 zoom)) (+ (:h r) (/ 6 zoom))))
  (.setLineDash ctx #js [])
  (let [o (when (= 1 (count sel)) (get objects (first sel)))]
    (when (and o (not (:locked? o)) (not= :connection (:kind o)))
      (set! (.-fillStyle ctx) "#fff")
      (set! (.-strokeStyle ctx) select-color)
      (let [s (/ geom/handle-px zoom)]
        (doseq [[_ [hx hy]] (geom/handles o)]
          (.fillRect ctx (- hx (/ s 2)) (- hy (/ s 2)) s s)
          (.strokeRect ctx (- hx (/ s 2)) (- hy (/ s 2)) s s))))))

(defn- drag! [^js ctx objects drag zoom]
  (set! (.-lineWidth ctx) (/ 1 zoom))
  (set! (.-strokeStyle ctx) select-color)
  (let [dash #js [(/ 4 zoom) (/ 4 zoom)]]
    (case (:type drag)
      :create (let [{:keys [x y w h]} (geom/rect-from-points (:start drag) (:current drag))]
                (.setLineDash ctx dash)
                (.strokeRect ctx x y w h)
                (.setLineDash ctx #js []))
      :band (let [{:keys [x y w h]} (geom/rect-from-points (:start drag) (:current drag))]
              (set! (.-fillStyle ctx) "rgba(30,136,229,0.1)")
              (.fillRect ctx x y w h)
              (.strokeRect ctx x y w h))
      :connect (when-let [o (get objects (:from drag))]
                 (let [[ax ay] (geom/center o) [bx by] (:current drag)]
                   (.setLineDash ctx dash)
                   (.beginPath ctx)
                   (.moveTo ctx ax ay)
                   (.lineTo ctx bx by)
                   (.stroke ctx)
                   (.setLineDash ctx #js [])))
      nil)))

(defn- draw!
  "One frame. _tick is the image-load counter: naming it makes a loaded image redraw."
  [^js ctx w h objects cam sel drag editing _tick]
  (.clearRect ctx 0 0 w h)
  (.save ctx)
  (.scale ctx (:zoom cam) (:zoom cam))
  (.translate ctx (- (:x cam)) (- (:y cam)))
  (doseq [o (geom/draw-order objects)] (object! ctx objects o (:id editing)))
  (selection! ctx objects sel (:zoom cam))
  (when drag (drag! ctx objects drag (:zoom cam)))
  (.restore ctx))

(defn- pointer [^js e x y]
  {:sx x :sy y :button (.-button e) :shift? (.-shiftKey e) :t (.-timeStamp e)})

(defn- on-down [^js e {:keys [x y]}]
  (.setPointerCapture (.-target e) (.-pointerId e))
  (dispatch [:pointer/down (pointer e x y)]))

(defn- on-move [^js e {:keys [x y]}] (dispatch [:pointer/move (pointer e x y)]))
(defn- on-up [^js e {:keys [x y]}] (dispatch [:pointer/up (pointer e x y)]))
(defn- on-dblclick [^js e {:keys [x y]}] (dispatch [:pointer/dblclick (pointer e x y)]))

(defn- on-wheel [^js e {:keys [x y]}]
  (.preventDefault e)
  (dispatch (if (or (.-ctrlKey e) (.-metaKey e))
              [:camera/zoom (js/Math.exp (* -0.01 (.-deltaY e))) x y]
              [:camera/pan (.-deltaX e) (.-deltaY e)])))

(defdraw board [] [objects [:objects] cam [:camera] sel [:selection] drag [:drag]
                   editing [:editing] tick [:img-tick]]
  {:attrs {:class "board"}
   :on-pointerdown on-down :on-pointermove on-move :on-pointerup on-up
   :on-dblclick on-dblclick :on-wheel on-wheel}
  (fn [ctx {:keys [w h]}] (draw! ctx w h objects cam sel drag editing tick)))
```

- [ ] **Step 2: `src/dingsbums/views.cljs`**

```clojure
(ns dingsbums.views
  "Landing screen and the DOM overlays on top of the board."
  (:require [hammer.core :refer [defc dispatch]]
            [dingsbums.board :as board]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]))

(defc landing [] [input [:join-input] err [:error]]
  [:main.landing
   [:h1 "dingsbums"]
   [:button.primary {:on-click [:landing/create]} "Create new session"]
   [:form.join {:on-submit (fn [^js e] (.preventDefault e) (dispatch [:landing/join]))}
    [:input {:placeholder "Session id" :value input
             :on-input (fn [^js e] (dispatch [:landing/input (.. e -target -value)]))}]
    [:button {:type "submit"} "Join session"]]
   [:p.error err]])

(defn- import-change [^js e]
  (let [^js input (.-target e)
        f (aget (.-files input) 0)]
    (set! (.-value input) "")
    (when f (dispatch [:import/file f]))))

(defc session-bar [] [sid [:session] online? [:online?] msg [:message]
                      export-url (str "/api/sessions/" sid "/export")]
  [:div.session-bar
   [:code.sid sid]
   [:button {:title "Copy session id" :on-click [:session/copy-id]} "Copy"]
   [:a.button {:href export-url :download ""} "Export"]
   [:label.button "Import"
    [:input {:type "file" :accept ".tar" :hidden true :on-change import-change}]]
   [:span.badge {:hidden (boolean online?)} "offline"]
   [:span.msg msg]])

(def ^:private tools
  [[:select "Select" "↖"] [:text "Text" "T"] [:frame "Frame" "▭"] [:shape "Shapes" "◆"]
   [:sticky "Sticky note" "▤"] [:connection "Connection" "↗"]])

(def ^:private shapes [[:circle "●"] [:rect "■"] [:star "★"] [:arrow "➜"]])

(defc toolbar [] [tool [:tool] shape [:shape-kind]]
  [:div.toolbar
   [:div.shapes {:hidden (not= tool :shape)}
    (for [[k icon] shapes]
      ^{:key k} [:button {:class (when (= k shape) "active") :title (name k) :on-click [:tool/shape k]} icon])]
   [:div.tools
    (for [[k label icon] tools]
      ^{:key k} [:button {:class (when (= k tool) "active") :title label :on-click [:tool/select k]} icon])
    [:span.sep]
    [:button {:title "Undo (Ctrl+Z)" :on-click [:history/undo]} "↶"]
    [:button {:title "Redo (Ctrl+Shift+Z)" :on-click [:history/redo]} "↷"]]])

(defc selection-toolbar [] [sel [:selection] objects [:objects] cam [:camera] drag [:drag] editing [:editing]
                            os (vec (keep #(get objects %) sel))
                            b (geom/bounds (keep #(geom/obj-rect objects %) os))
                            pos (when b (geom/world->screen cam [(:x b) (:y b)]))
                            show? (boolean (and pos (nil? drag) (nil? editing)))
                            left (if pos (max 8 (first pos)) 0)
                            top (if pos (max 8 (- (second pos) 48)) 0)
                            colors (cond
                                     (and (seq os) (every? #(= :shape (:kind %)) os)) model/swatches
                                     (and (seq os) (every? #(#{:shape :sticky} (:kind %)) os)) (rest model/swatches))
                            locked? (boolean (and (seq os) (every? :locked? os)))
                            groupable? (>= (count (remove #(= :connection (:kind %)) os)) 2)
                            grouped? (boolean (some :group os))]
  [:div.sel-toolbar {:hidden (not show?) :style {:left (str left "px") :top (str top "px")}}
   (for [c colors]
     ^{:key (str c)} [:button.swatch {:title (or c "No fill") :style {:background-color (or c "transparent")}
                                      :on-click [:selection/fill c]}])
   [:button {:on-click [:selection/lock (not locked?)]} (if locked? "Unlock" "Lock")]
   [:button {:hidden (not groupable?) :on-click [:selection/group]} "Group"]
   [:button {:hidden (not grouped?) :on-click [:selection/ungroup]} "Ungroup"]
   [:button {:on-click [:selection/delete]} "Delete"]])

(defn- focus! [^js el] (when el (.focus el)))

(defn- edit-keydown [^js e]
  (when (= "Escape" (.-key e))
    (.preventDefault e)
    (dispatch [:edit/cancel])))

(defc text-editor [] [editing [:editing] objects [:objects] cam [:camera]
                      o (get objects (:id editing))
                      draft (or (:draft editing) "")
                      zoom (:zoom cam)
                      pos (geom/world->screen cam [(:x o) (:y o)])
                      size (:size (board/fit draft (:w o) (:h o)))
                      style {:left (str (first pos) "px") :top (str (second pos) "px")
                             :width (str (* zoom (:w o)) "px") :height (str (* zoom (:h o)) "px")
                             :font-size (str (* zoom size) "px") :padding (str (* zoom geom/padding) "px")}]
  [:textarea.text-editor {:style style :value draft :ref focus!
                          :on-input (fn [^js e] (dispatch [:edit/input (.. e -target -value)]))
                          :on-blur [:edit/commit] :on-keydown edit-keydown}])

(defc app [] [route [:route] editing-id [:editing :id]]
  (if (= route :board)
    [:div.app
     [board/board]
     [session-bar]
     [toolbar]
     [selection-toolbar]
     (when editing-id [text-editor])]
    [landing]))
```

- [ ] **Step 3: `src/dingsbums/core.cljs`**

```clojure
(ns dingsbums.core
  "Entry point: mounts the app and wires window listeners to events."
  (:require [clojure.string :as str]
            [hammer.core :refer [mount! dispatch]]
            [dingsbums.events :as events]
            [dingsbums.views :as views]))

(defn- root [] (js/document.getElementById "app"))

(defn- hash-id []
  (let [h (subs (.-hash js/location) 1)]
    (try (js/decodeURIComponent h) (catch :default _ h))))

(defn- typing? [^js e] (contains? #{"INPUT" "TEXTAREA"} (.. e -target -tagName)))

(defn- on-keydown [^js e]
  (when-not (typing? e)
    (when-let [action (events/key-action {:key (.-key e) :ctrl? (or (.-ctrlKey e) (.-metaKey e))
                                          :shift? (.-shiftKey e)})]
      (.preventDefault e)
      (dispatch action))))

(defn- on-keyup [^js e] (when (= " " (.-key e)) (dispatch [:space false])))

(defn- paste-image! [^js file]
  (let [r (js/FileReader.)]
    (set! (.-onload r)
          (fn [_]
            (let [src (.-result r)
                  img (js/Image.)]
              (set! (.-onload img) (fn [_] (dispatch [:paste/image src (.-naturalWidth img) (.-naturalHeight img)])))
              (set! (.-src img) src))))
    (.readAsDataURL r file)))

(defn- on-paste [^js e]
  (when-not (typing? e)
    (let [^js data (.-clipboardData e)
          file (some (fn [^js item]
                       (when (and (= "file" (.-kind item)) (str/starts-with? (.-type item) "image/"))
                         (.getAsFile item)))
                     (js/Array.from (.-items data)))
          text (.getData data "text/plain")]
      (cond
        file (do (.preventDefault e) (paste-image! file))
        (not (str/blank? text)) (do (.preventDefault e) (dispatch [:paste/text text]))))))

(defn- on-resize [] (dispatch [:viewport (.-innerWidth js/window) (.-innerHeight js/window)]))

(defn init []
  (mount! [views/app] (root) events/initial-db)
  (.addEventListener js/window "hashchange" #(dispatch [:route/changed (hash-id)]))
  (.addEventListener js/window "keydown" on-keydown)
  (.addEventListener js/window "keyup" on-keyup)
  (.addEventListener js/window "paste" on-paste)
  (.addEventListener js/window "resize" on-resize)
  (on-resize)
  (dispatch [:route/changed (hash-id)]))

(defn ^:dev/after-load reload [] (mount! [views/app] (root)))
```

- [ ] **Step 4: Replace `public/index.html`**

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>dingsbums</title>
<style>
  * { box-sizing: border-box; }
  [hidden] { display: none !important; }
  html, body { margin: 0; height: 100%; font-family: system-ui, sans-serif; background: #f5f5f4; color: #222; }
  button, .button { font: inherit; font-size: 14px; padding: 6px 10px; border: 1px solid #ccc; border-radius: 6px;
                    background: #fff; cursor: pointer; color: inherit; text-decoration: none; line-height: 1.2; }
  button.active { background: #1e88e5; border-color: #1e88e5; color: #fff; }
  .landing { max-width: 360px; margin: 18vh auto 0; display: flex; flex-direction: column; gap: 12px; padding: 0 16px; }
  .landing h1 { margin: 0 0 8px; }
  .landing .join { display: flex; gap: 8px; }
  .landing input { flex: 1; min-width: 0; font: inherit; padding: 6px 10px; border: 1px solid #ccc; border-radius: 6px; }
  .error { color: #c62828; min-height: 1.2em; margin: 0; }
  .app { position: fixed; inset: 0; overflow: hidden; background: #fafafa; }
  .board { touch-action: none; }
  .session-bar, .toolbar, .sel-toolbar { position: absolute; display: flex; gap: 6px; align-items: center;
    background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 6px; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
  .session-bar { top: 12px; left: 12px; }
  .session-bar .sid { font-size: 13px; padding: 0 4px; user-select: all; }
  .badge { background: #c62828; color: #fff; border-radius: 4px; padding: 2px 6px; font-size: 12px; }
  .msg { font-size: 13px; color: #555; }
  .toolbar { left: 12px; bottom: 12px; flex-direction: column; align-items: stretch; }
  .toolbar .tools, .toolbar .shapes { display: flex; gap: 4px; }
  .toolbar button { min-width: 36px; }
  .sep { width: 1px; background: #ddd; margin: 0 4px; }
  .sel-toolbar { z-index: 2; }
  .swatch { width: 22px; height: 22px; padding: 0; border-radius: 50%; }
  .text-editor { position: absolute; z-index: 3; margin: 0; border: none; outline: 2px solid #1e88e5;
    background: transparent; resize: none; overflow: hidden; font-family: sans-serif; line-height: 1.2; }
</style>
</head>
<body>
<div id="app"></div>
<script src="/js/main.js"></script>
</body>
</html>
```

- [ ] **Step 5: Release build**

Run: `bb build`
Expected: `[:app] Build completed. (... 0 warnings ...)`. Any warning is a failure: fix it (most likely a missing `^js` hint).

- [ ] **Step 6: All suites still green**

Run: `npm test && bb test`
Expected: both `0 failures, 0 errors.`

- [ ] **Step 7: Commit**

```bash
git add public/index.html src/dingsbums/board.cljs src/dingsbums/views.cljs src/dingsbums/core.cljs
git commit -m "Frontend: board canvas, overlays and app shell" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: End-to-end check in the browser and README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Run it**

`bb build`, then `bb server 8080` in the background. Open two browser tabs (claude-in-chrome if available; otherwise ask the user to click through).

- [ ] **Step 2: Walk the spec** — each line must hold; any failure goes through superpowers:systematic-debugging with a regression test where the logic is testable.

| Check | Expected |
|---|---|
| Landing → Create | board opens, URL `/#<uuid>`, id shown top left |
| Tab 2: paste the id, Join | same board |
| Each tool (text, frame, 4 shapes, sticky, connection) | creates the object, visible in tab 2 within ~100 ms |
| Drag, resize, fill swatch, lock (then drag: no move), group (click selects both), ungroup | mirrored in tab 2 |
| Double-click / F2 on sticky, type, click outside | text saved, font fits the box; Esc aborts |
| Paste an image and a line of text | image object / text object at view center |
| Ctrl+Z / Ctrl+Shift+Z and ↶ ↷ | undo/redo, mirrored in tab 2 |
| Wheel, ctrl+wheel, space+drag | pan, zoom at cursor, pan |
| Export, clear board, Import the file | board restored in both tabs |
| Stop/restart the server | offline badge; after restart: "Session expired or not found" |
| `/#nope` | landing, "Session not found" |

- [ ] **Step 3: README**

```markdown
# dingsbums

A very simplified miro-like whiteboard: babashka backend, [hammer](https://github.com/sstoehrm/hammer) frontend.

## Run

    npm install
    bb build          # frontend release build into public/js
    bb server         # http://localhost:8080 (bb server <port> for another)

Development: `bb server` plus `npx shadow-cljs watch app` (hot reload; bb serves public/).

## Test

    bb test           # backend
    npm test          # frontend

Design: `docs/superpowers/specs/2026-10-06-whiteboard-design.md`.
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "README: run and test instructions" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
