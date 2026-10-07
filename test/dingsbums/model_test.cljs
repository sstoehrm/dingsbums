(ns dingsbums.model-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing]]
            [dingsbums.model :as m]))

(def a {:id "a" :kind :sticky :x 0 :y 0 :w 10 :h 10 :z 1 :fill "#fff176" :text "x"})
(def b {:id "b" :kind :shape :shape :rect :x 50 :y 0 :w 10 :h 10 :z 2})
(def ab {:id "ab" :kind :connection :from "a" :to "b" :z 3})
(def objs {"a" a "b" b "ab" ab})
(def db {:objects objs :history {:undo [] :redo []}})

(deftest ink-contrasts-with-the-fill
  (is (= "#222" (m/ink "#fff176")))
  (is (= "#222" (m/ink "#ffffff")))
  (is (= "#fff" (m/ink "#000000")))
  (is (= "#222" (m/ink "#ba68c8")))
  (is (= "#fff" (m/ink "#1f2937")))
  (is (= "#222" (m/ink nil)) "default sticky fill"))

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
        db2 (update db :objects m/apply-changes (m/moved objs start 5 5))
        db2 (update db2 :objects m/apply-changes (m/moved (:objects db2) start 10 0))
        [db3 ops] (m/finish-gesture db2 start)]
    (is (= 1 (count (get-in db3 [:history :undo]))))
    (is (= :op/patch (ffirst ops)) "gestures send geometry only, never an image's :src")
    (is (= #{{:id "a" :x 10 :y 0 :w 10 :h 10} {:id "b" :x 60 :y 0 :w 10 :h 10}} (set (second (first ops)))))
    (is (= start (:before (peek (get-in db3 [:history :undo])))))
    (is (= objs (:objects (first (m/undo db3)))))
    (is (= [db []] (m/finish-gesture db start)) "no movement, no entry")))

(deftest moves-apply-to-the-current-state
  (let [start (m/movable objs #{"a" "b"})]
    (is (= {"b" (assoc b :x 55)} (m/moved (dissoc objs "a") start 5 0)) "an object deleted meanwhile stays deleted")
    (is (= "#e57373" (get-in (m/moved (assoc-in objs ["a" :fill] "#e57373") start 5 0) ["a" :fill]))
        "a remote fill made mid-drag is kept")
    (is (= {"b" (assoc b :x 55)} (m/moved (assoc-in objs ["a" :locked?] true) start 5 0)) "locked meanwhile: stays put"))
  (let [[d ops] (m/finish-gesture (update db :objects dissoc "a") (m/movable objs #{"a"}))]
    (is (= [] ops) "a gesture on an object deleted meanwhile records and sends nothing")
    (is (empty? (get-in d [:history :undo])))))

(deftest upserts-go-one-object-per-frame
  (is (= [[:op/upsert [a]] [:op/upsert [b]] [:op/delete ["ab"]]] (m/ops-for {"a" a "b" b "ab" nil}))))
