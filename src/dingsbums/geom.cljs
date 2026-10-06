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
