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
