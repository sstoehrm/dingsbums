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

(deftest objects-are-type-checked
  (is (ops/valid-object? {:id "c-1_X" :kind :shape :shape :star :x 1 :y 2.5 :w 3 :h 4 :z 7 :fill nil
                          :group "g" :locked? true}))
  (is (ops/valid-object? {:id "c" :kind :connection :from "a" :to "b" :z 1}))
  (is (ops/valid-object? {:id "i" :kind :image :x 0 :y 0 :w 1 :h 1 :src "data:image/png;base64,AA"}))
  (is (not (ops/valid-object? {:id "q" :kind :sticky :z "x"})) "a string :z would break sorting for every client")
  (is (not (ops/valid-object? {:id "q" :kind :text :text 42})))
  (is (not (ops/valid-object? {:id "q" :kind :blob})) "unknown kind")
  (is (not (ops/valid-object? {:id "q" :kind :shape :shape :hexagon})))
  (is (not (ops/valid-object? {:id "q" :kind :text :nested [[[[1]]]]})) "unknown keys")
  (is (not (ops/valid-object? {:id "../../x" :kind :image})) "ids become tar entry names")
  (is (not (ops/valid-object? {:id "éé" :kind :text}))))

(deftest patches-move-existing-objects-only
  (is (= {"a" (assoc a :x 7 :w 3)} (ops/patch {"a" a} [{:id "a" :x 7 :w 3}])))
  (is (= {"a" a} (ops/patch {"a" a} [{:id "gone" :x 7}])) "no resurrection")
  (is (= {"a" a} (ops/patch {"a" a} [{:id "a" :x "7"} {:id "a" :text "x"}])) "geometry numbers only")
  (is (ops/op? [:op/patch [{:id "a" :x 1 :y 2}]]))
  (is (not (ops/op? [:op/patch [{:id "a" :fill "red"}]]))))
