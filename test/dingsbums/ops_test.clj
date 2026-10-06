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
