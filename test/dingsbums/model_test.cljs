(ns dingsbums.model-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing]]
            [dingsbums.geom :as geom]
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

(deftest blends-with-the-board
  (is (m/blends? "#ffffff" "#ffffff"))
  (is (m/blends? "#ffffff" "#f6f1e6") "white on parchment")
  (is (m/blends? "#000000" "#161616") "black on carbonfox")
  (is (not (m/blends? "#fff176" "#f6f1e6")) "yellow on parchment stands out")
  (is (not (m/blends? "#ffffff" "#111827")))
  (is (not (m/blends? "junk" "#ffffff")) "not a colour: no"))

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
    (let [t {:id "t" :kind :text :text "x"}
          os (assoc locked "t" t)]
      (is (= {"t" (assoc t :color "#e57373")} (m/text-color-changes os #{"a" "b" "t"} "#e57373"))
          "locked and textless skipped")
      (is (= {"t" t} (m/text-color-changes (assoc os "t" (assoc t :color "#000000")) #{"t"} nil)) "nil: automatic again"))
    (is (= #{"b"} (set (keys (m/movable locked #{"a" "b" "ab"})))))))

(deftest text-color
  (let [t {:id "t" :kind :text :x 10 :y 10 :w 20 :h 20 :z 5}
        dark (assoc b :x 0 :y 0 :w 100 :h 100 :fill "#000000")]
    (is (nil? (m/text-color {"t" t} t)) "plain text: the theme's colour")
    (is (= "#222" (m/text-color objs a)) "sticky: ink for its fill")
    (is (= "#fff" (m/text-color {"t" t "b" dark} t)) "on a dark shape: light ink")
    (is (= "#e57373" (m/text-color {"t" t "b" dark} (assoc t :color "#e57373"))) "a chosen colour wins")
    (is (= "#e57373" (m/text-color objs (assoc a :color "#e57373"))))))

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

(deftest layering
  (let [o (fn [id z & kv] (apply assoc {:id id :kind :sticky :x 0 :y 0 :w 10 :h 10 :z z} kv))
        os {"p" (o "p" 1) "q" (o "q" 2) "r" (o "r" 3) "s" (o "s" 4)
            "f" (o "f" 9 :kind :frame) "g" (o "g" 10 :kind :frame) "pq" {:id "pq" :kind :connection :from "p" :to "q" :z 5}}
        order (fn [objects ids move] (mapv :id (geom/draw-order (m/apply-changes objects (m/layer-changes objects ids move)))))]
    (is (= ["f" "g" "pq" "q" "r" "s" "p"] (order os #{"p"} :front)))
    (is (= ["f" "g" "pq" "s" "p" "q" "r"] (order os #{"s"} :back)))
    (is (= ["f" "g" "pq" "q" "p" "r" "s"] (order os #{"p"} :forward)))
    (is (= ["f" "g" "pq" "p" "q" "s" "r"] (order os #{"s"} :backward)))
    (testing "frames stay below everything, each kind moves within its layer"
      (is (= ["g" "f" "pq" "p" "q" "r" "s"] (order os #{"f"} :front)))
      (is (= ["g" "f" "pq" "q" "r" "s" "p"] (order os #{"f" "p"} :front))))
    (testing "a multi-selection keeps its own order"
      (is (= ["f" "g" "pq" "r" "s" "p" "q"] (order os #{"p" "q"} :front)))
      (is (= ["f" "g" "pq" "r" "p" "q" "s"] (order os #{"p" "q"} :forward)))
      (is (= ["f" "g" "pq" "q" "p" "s" "r"] (order os #{"p" "r"} :forward)) "interleaved: each one step up"))
    (testing "only the moved objects change"
      (is (= #{"p"} (set (keys (m/layer-changes os #{"p"} :front)))))
      (is (= #{"p" "q"} (set (keys (m/layer-changes os #{"p"} :forward))))))
    (testing "already there: no changes"
      (is (= {} (m/layer-changes os #{"s"} :front)))
      (is (= {} (m/layer-changes os #{"s"} :forward)))
      (is (= {} (m/layer-changes os #{"p"} :back)))
      (is (= {} (m/layer-changes os #{"p" "q"} :backward)))
      (is (= {} (m/layer-changes os #{} :front))))
    (testing "equal z (two clients creating at once) still reorders"
      (let [tied (assoc os "q" (o "q" 1))
            lower (first (filter #{"p" "q"} (order tied #{} :front)))
            upper ({"p" "q" "q" "p"} lower)]
        (is (= [upper lower] (filterv #{"p" "q"} (order tied #{lower} :forward)))))
      (is (= #{"r" "s"} (set (keys (m/layer-changes (assoc os "q" (o "q" 1)) #{"r"} :forward))))
          "a tie the step does not touch keeps its z")
      (let [tied (assoc os "r" (o "r" 4))]
        (doseq [[sel other] [["r" "s"] ["s" "r"]]]
          (let [ch (m/layer-changes tied #{sel} :front)]
            (is (> (get-in ch [sel :z]) (:z (tied other))) "front means above the tie on every client")))))
    (testing "a missing z counts as 0"
      (is (= ["p" "q"] (order {"p" (o "p" -1) "q" (dissoc (o "q" 0) :z)} #{"p"} :back))))
    (testing "locked objects are not moved"
      (is (= {} (m/layer-changes (assoc-in os ["p" :locked?] true) #{"p"} :front)))
      (is (= ["f" "g" "pq" "q" "r" "s" "p"] (order (assoc-in os ["q" :locked?] true) #{"p" "q"} :front))))))

(deftest dots-and-measuring-boxes
  (let [dot (assoc (m/make {} :dot {:x 0 :y 0 :w 24 :h 24} nil) :id "d")
        box {:id "m" :kind :counter :x 0 :y 0 :w 100 :h 100 :z 1}
        os {"d" dot "m" box "a" a}]
    (is (= m/dot-fill (:fill dot)))
    (is (= {"d" (assoc dot :fill "#64b5f6")} (m/fill-changes os #{"d" "m"} "#64b5f6")) "dots take a fill, boxes don't")
    (is (= {} (m/fill-changes os #{"d"} nil)) "a dot keeps a fill")
    (is (= {"m" (assoc box :side :left)} (m/side-changes os #{"m" "a" "d"} :left)) "only boxes have a side")
    (is (= {} (m/side-changes (assoc-in os ["m" :locked?] true) #{"m"} :left)) "locked")
    (is (= "1" (m/readout os box)) "a counter counts its dots")
    (is (= "0" (m/readout {"m" box} box)))
    (is (= "Ø 1.2" (m/readout os (assoc box :kind :scale-x))) "the average on 0–10")
    (is (= "Ø 1.2 · 8.8" (m/readout os (assoc box :kind :scale-xy))) "y counts up from the bottom")
    (is (= "Ø –" (m/readout {"m" box} (assoc box :kind :scale-x))) "no dots: no average")))
