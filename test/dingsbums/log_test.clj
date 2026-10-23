(ns dingsbums.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [dingsbums.log :as log]))

(def a {:id "a" :kind :sticky :x 0 :y 0 :w 100 :h 100 :z 1 :text "hi"})
(def b {:id "b" :kind :shape :shape :rect :x 200 :y 0 :w 100 :h 100 :z 2})
(def ab {:id "ab" :kind :connection :from "a" :to "b" :z 3})
(def objs {"a" a "b" b "ab" ab})

(defn- whats [objects lg ev] (mapv (comp :what second) (log/entries objects lg ev "Ada" 1000)))

(deftest what-an-op-did
  (is (= [[ "c" {:at 1000 :by "Ada" :what :created}]]
         (log/entries objs {} [:op/upsert [{:id "c" :kind :text}]] "Ada" 1000)))
  (is (= [:restored] (whats {} {"a" [{:at 1 :by "Bo" :what :deleted}]} [:op/upsert [a]])) "undo of a delete")
  (is (= [:edited] (whats objs {} [:op/upsert [(assoc a :text "ho")]])))
  (is (= [:filled] (whats objs {} [:op/upsert [(assoc a :fill "#fff")]])))
  (is (= [:coloured] (whats objs {} [:op/upsert [(assoc a :color "#000")]])))
  (is (= [:locked] (whats objs {} [:op/upsert [(assoc a :locked? true)]])))
  (is (= [:unlocked] (whats {"a" (assoc a :locked? true)} {} [:op/upsert [a]])))
  (is (= [:grouped] (whats objs {} [:op/upsert [(assoc a :group "g")]])))
  (is (= [:ungrouped] (whats {"a" (assoc a :group "g")} {} [:op/upsert [a]])))
  (is (= [:reordered] (whats objs {} [:op/upsert [(assoc a :z 9)]])))
  (is (= [:moved] (whats objs {} [:op/upsert [(assoc a :x 5)]])))
  (is (= [:resized] (whats objs {} [:op/upsert [(assoc a :x 5 :w 95)]])) "a resize from the left moves it too")
  (is (= [:changed] (whats objs {} [:op/upsert [(assoc b :shape :star)]])))
  (is (= [:edited :filled] (whats objs {} [:op/upsert [(assoc a :text "x" :fill "#fff")]])))
  (is (= [] (whats objs {} [:op/upsert [a]])) "no change, no entry")
  (is (= [:moved] (whats objs {} [:op/patch [{:id "a" :x 5 :y 0}]])))
  (is (= [:resized] (whats objs {} [:op/patch [{:id "a" :w 50}]])))
  (is (= [] (whats objs {} [:op/patch [{:id "a" :x 0} {:id "nope" :x 3}]])) "unchanged or unknown")
  (is (= #{["a" :deleted] ["ab" :deleted]}
         (set (map (fn [[id e]] [id (:what e)]) (log/entries objs {} [:op/delete ["a" "nope"]] "Ada" 1000))))
      "the connections go with it"))

(deftest repeats-are-left-out-for-a-minute
  (let [lg (log/add {} (log/entries objs {} [:op/patch [{:id "a" :x 5}]] "Ada" 1000))]
    (testing "a drag sends many patches: one entry"
      (is (= [] (log/entries (assoc-in objs ["a" :x] 5) lg [:op/patch [{:id "a" :x 6}]] "Ada" 30000))))
    (is (= [:moved] (mapv (comp :what second) (log/entries objs lg [:op/patch [{:id "a" :x 6}]] "Ada" 61000)))
        "a minute later: again")
    (is (= ["Bo"] (mapv (comp :by second) (log/entries objs lg [:op/patch [{:id "a" :x 6}]] "Bo" 2000)))
        "someone else: again")
    (is (= [:moved] (mapv (comp :what second) (log/entries objs lg [:op/patch [{:id "a" :x 6}]] "Ada" 500)))
        "the last entry dated later (the clock stepped back): again")))

(deftest add-keeps-the-newest
  (let [lg (reduce (fn [lg i] (log/add lg [["a" {:at i :by "Ada" :what :moved}]])) {} (range 60))]
    (is (= log/cap (count (get lg "a"))))
    (is (= 59 (:at (peek (get lg "a")))))
    (is (instance? clojure.lang.PersistentVector (get lg "a")) "a fresh vector: a subvec would keep all of them")))

(deftest read-keeps-only-valid-entries-of-objects
  (let [ok {:at 5 :by "Ada" :what :created}]
    (is (= {"a" [ok]}
           (log/read {"a" [ok {:at -1 :by "Ada" :what :created} {:at 5 :by "" :what :created}
                           {:at 101 :by "Ada" :what :moved} {:at 9007199254740993 :by "Ada" :what :moved}
                           {:at 5 :by "Ada" :what :exploded} {:at 5 :by "Ada" :what :created :x 1} "junk"]
                      "gone" [ok]
                      "b" "junk"}
                     objs 100))
        "nothing from after now")
    (is (= {} (log/read nil objs 100)) "an export from before the log")
    (is (= {} (log/read [1 2] objs 100)))))
