(ns dingsbums.board
  "The board canvas: draws the objects through the camera and turns pointer
  input into events. Text is measured with an offscreen canvas."
  (:require [hammer.core :refer [dispatch]]
            [hammer.canvas :refer [defdraw]]
            [dingsbums.events :as events]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.ops :as ops]
            [dingsbums.themes :as themes]))

(def font-family
  "The board's text font, also the text editor's. System monospace fonts only:
  they are there before the first measure, so fit-cache never goes stale."
  "ui-monospace, \"Cascadia Code\", \"JetBrains Mono\", \"SF Mono\", Menlo, Consolas, \"DejaVu Sans Mono\", monospace")
(def ^:private font (str "px " font-family))
(def ^:private frame-radius 8)
(def ^:private sticky-radius 4)

(def user-colors
  "The colours of the people on a board, by the index the server gives out;
  dark enough for white text on them."
  ["#d32f2f" "#1976d2" "#388e3c" "#e65100" "#7b1fa2" "#00838f" "#c2185b" "#5d4037"])

(defn user-color [i] (nth user-colors (mod (or i 0) (count user-colors))))

(defn user-name [user] (or (:name user) ops/default-name))

(defonce ^:private measure-ctx (delay (.getContext (js/document.createElement "canvas") "2d")))

(defn measure [s size]
  (let [^js ctx @measure-ctx]
    (set! (.-font ctx) (str size font))
    (.-width (.measureText ctx s))))

(defonce ^:private fit-cache (js/Map.))

(defn fit
  "geom/fit-text with the canvas measure, cached by box size, padding and text."
  [text w h pad]
  (let [k (str w "|" h "|" pad "|" text)]
    (or (.get fit-cache k)
        (let [r (geom/fit-text measure text w h pad)]
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

(defn- text! [^js ctx {:keys [x y w h text kind]} color]
  (when (seq text)
    (let [pad (geom/text-padding kind)
          {:keys [size lines]} (fit text w h pad)]
      (.save ctx)
      (.beginPath ctx)
      (.rect ctx x y w h)
      (.clip ctx)
      (set! (.-font ctx) (str size font))
      (set! (.-textBaseline ctx) "top")
      (set! (.-fillStyle ctx) color)
      (doseq [[i line] (map-indexed vector lines)]
        (.fillText ctx line (+ x pad) (+ y pad (* i size geom/line-height))))
      (.restore ctx))))

(defn- arrowhead! [^js ctx [ax ay] [bx by] size]
  (let [a (js/Math.atan2 (- by ay) (- bx ax))]
    (.beginPath ctx)
    (.moveTo ctx bx by)
    (.lineTo ctx (- bx (* size (js/Math.cos (- a 0.4)))) (- by (* size (js/Math.sin (- a 0.4)))))
    (.lineTo ctx (- bx (* size (js/Math.cos (+ a 0.4)))) (- by (* size (js/Math.sin (+ a 0.4)))))
    (.closePath ctx)
    (.fill ctx)))

(def ^:private readout-size 24)
(def ^:private readout-gap 8)
(def readout-room
  "World height a measuring box's number takes above or below it."
  (+ readout-gap readout-size))
(def ^:private tick-size 12)

(defn- ticks!
  "0 to scale-max along the bottom edge, and the left one for two axes; labels
  every 5, inside the box."
  [^js ctx {:keys [x y w h kind]} theme]
  (let [n model/scale-max
        end (fn [i first last mid] (cond (zero? i) first (= n i) last :else mid))]
    (set! (.-strokeStyle ctx) (:text-muted theme))
    (set! (.-fillStyle ctx) (:text-muted theme))
    (set! (.-lineWidth ctx) 1)
    (set! (.-font ctx) (str tick-size font))
    (doseq [i (range (inc n))
            :let [major? (zero? (mod i 5))
                  len (if major? 10 5)
                  tx (+ x (* w (/ i n)))
                  ty (- (+ y h) (* h (/ i n)))]]
      (.beginPath ctx)
      (.moveTo ctx tx (+ y h))
      (.lineTo ctx tx (- (+ y h) len))
      (when (= :scale-xy kind)
        (.moveTo ctx x ty)
        (.lineTo ctx (+ x len) ty))
      (.stroke ctx)
      (when major?
        (set! (.-textBaseline ctx) "bottom")
        (set! (.-textAlign ctx) (end i "left" "right" "center"))
        (.fillText ctx (str i) tx (- (+ y h) 12))
        ;; the y axis's 0 is the x axis's
        (when (and (= :scale-xy kind) (pos? i))
          (set! (.-textBaseline ctx) (end i "bottom" "top" "middle"))
          (set! (.-textAlign ctx) "left")
          (.fillText ctx (str i) (+ x 12) ty))))))

(defn- average!
  "The average position of a scale's dots: a line across a one-axis scale, a
  crosshair on a two-axis one."
  [^js ctx dots {:keys [x y w h kind] :as o} theme]
  (when-let [[ax ay] (geom/average o dots)]
    (let [px (+ x (* ax w)) py (- (+ y h) (* ay h))]
      (set! (.-strokeStyle ctx) (:accent theme))
      (set! (.-lineWidth ctx) 2)
      (.beginPath ctx)
      (if (= :scale-x kind)
        (do (.moveTo ctx px y) (.lineTo ctx px (+ y h)) (.stroke ctx))
        (do (.setLineDash ctx #js [6 4])
            (.moveTo ctx px y) (.lineTo ctx px (+ y h))
            (.moveTo ctx x py) (.lineTo ctx (+ x w) py)
            (.stroke ctx)
            (.setLineDash ctx #js [])
            (.beginPath ctx)
            (.arc ctx px py 6 0 (* 2 js/Math.PI))
            (set! (.-fillStyle ctx) (:accent theme))
            (.fill ctx))))))

(defn- readout!
  "What a measuring box measured, outside it on its :side (default bottom)."
  [^js ctx dots {:keys [x y w h side] :as o} theme]
  (let [gap readout-gap
        [tx ty align baseline] (case side
                                 :top [(+ x (/ w 2)) (- y gap) "center" "bottom"]
                                 :left [(- x gap) (+ y (/ h 2)) "right" "middle"]
                                 :right [(+ x w gap) (+ y (/ h 2)) "left" "middle"]
                                 [(+ x (/ w 2)) (+ y h gap) "center" "top"])]
    (set! (.-font ctx) (str readout-size font))
    (set! (.-fillStyle ctx) (:text-strong theme))
    (set! (.-textAlign ctx) align)
    (set! (.-textBaseline ctx) baseline)
    (.fillText ctx (model/readout o dots) tx ty)))

(defn- box! [^js ctx objects {:keys [x y w h kind] :as o} theme]
  (let [dots (geom/dots-in objects o)]
    (.save ctx)
    (set! (.-strokeStyle ctx) (:sub theme))
    (.beginPath ctx)
    (.roundRect ctx x y w h frame-radius)
    (.stroke ctx)
    (when (not= :counter kind)
      (ticks! ctx o theme)
      (average! ctx dots o theme))
    (readout! ctx dots o theme)
    (.restore ctx)))

(defn- hairline-if-blends!
  "Strokes the current path with a hairline when fill would vanish into the board."
  [^js ctx fill theme zoom]
  (when (model/blends? fill (:bg theme))
    (set! (.-strokeStyle ctx) (:panel-border theme))
    (set! (.-lineWidth ctx) (/ 1 zoom))
    (.stroke ctx)))

(defn- object! [^js ctx objects o editing-id theme zoom]
  (set! (.-lineWidth ctx) 2)
  (set! (.-strokeStyle ctx) (:text-strong theme))
  (let [{:keys [x y w h]} o]
    (case (:kind o)
      :connection (when-let [[a] (geom/endpoints objects o)]
                    (let [[tx ty :as tip] (geom/border-point (get objects (:to o)) a)]
                      (.beginPath ctx)
                      (.moveTo ctx (first a) (second a))
                      (.lineTo ctx tx ty)
                      (.stroke ctx)
                      (set! (.-fillStyle ctx) (:text-strong theme))
                      (arrowhead! ctx a tip 14)))
      ;; a hairline at every zoom
      :frame (do (set! (.-strokeStyle ctx) (:sub theme))
                 (set! (.-lineWidth ctx) (/ 1 zoom))
                 (.beginPath ctx)
                 (.roundRect ctx x y w h frame-radius)
                 (.stroke ctx))
      :shape (do (path! ctx o)
                 (when-let [f (:fill o)] (set! (.-fillStyle ctx) f) (.fill ctx))
                 (.stroke ctx))
      ;; flat and no shadow: a note of colour on the board, with a hairline
      ;; only when its colour would vanish into the board's
      :sticky (do (set! (.-fillStyle ctx) (:fill o "#fff176"))
                  (.beginPath ctx)
                  (.roundRect ctx x y w h sticky-radius)
                  (.fill ctx)
                  (hairline-if-blends! ctx (:fill o "#fff176") theme zoom)
                  (when-not (= editing-id (:id o)) (text! ctx o (model/text-color objects o))))
      :text (when-not (= editing-id (:id o))
              (text! ctx o (or (model/text-color objects o) (:text-strong theme))))
      :dot (let [fill (:fill o model/dot-fill)]
             (set! (.-fillStyle ctx) fill)
             (path! ctx (assoc o :shape :circle))
             (.fill ctx)
             (hairline-if-blends! ctx fill theme zoom))
      (:counter :scale-x :scale-xy) (box! ctx objects o theme)
      :image (let [^js img (image (:src o))]
               (if (and (.-complete img) (pos? (.-naturalWidth img)))
                 (.drawImage ctx img x y w h)
                 (do (set! (.-fillStyle ctx) (:hover-plain theme)) (.fillRect ctx x y w h))))
      nil)))

(defn- selection! [^js ctx objects sel zoom theme]
  (set! (.-lineWidth ctx) (/ 1 zoom))
  (doseq [id sel
          :let [o (get objects id) r (when o (geom/obj-rect objects o))]
          :when r]
    (set! (.-strokeStyle ctx) (if (:locked? o) (:text-dim theme) (:accent theme)))
    (.setLineDash ctx (if (:locked? o) #js [(/ 4 zoom) (/ 4 zoom)] #js []))
    (.strokeRect ctx (- (:x r) (/ 3 zoom)) (- (:y r) (/ 3 zoom)) (+ (:w r) (/ 6 zoom)) (+ (:h r) (/ 6 zoom))))
  (.setLineDash ctx #js [])
  (let [o (when (= 1 (count sel)) (get objects (first sel)))]
    (when (model/resizable? o)
      (set! (.-fillStyle ctx) (:panel theme))
      (set! (.-strokeStyle ctx) (:accent theme))
      (let [s (/ geom/handle-px zoom)]
        (doseq [[_ [hx hy]] (geom/handles o)]
          (.fillRect ctx (- hx (/ s 2)) (- hy (/ s 2)) s s)
          (.strokeRect ctx (- hx (/ s 2)) (- hy (/ s 2)) s s))))))

(defn- drag! [^js ctx objects drag zoom theme]
  (set! (.-lineWidth ctx) (/ 1 zoom))
  (set! (.-strokeStyle ctx) (:accent theme))
  (let [dash #js [(/ 4 zoom) (/ 4 zoom)]]
    (case (:type drag)
      :create (let [{:keys [x y w h]} (geom/rect-from-points (:start drag) (:current drag))]
                (.setLineDash ctx dash)
                (.strokeRect ctx x y w h)
                (.setLineDash ctx #js []))
      :band (let [{:keys [x y w h]} (geom/rect-from-points (:start drag) (:current drag))]
              (set! (.-fillStyle ctx) (:accent theme))
              (set! (.-globalAlpha ctx) 0.1)
              (.fillRect ctx x y w h)
              (set! (.-globalAlpha ctx) 1)
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

(defn- peer-selections!
  "What the others have selected, outlined in their colours outside my own outline."
  [^js ctx objects peers zoom]
  (set! (.-lineWidth ctx) (/ 2 zoom))
  (doseq [{:keys [selection color]} (vals peers)
          id selection
          :let [o (get objects id) r (when o (geom/obj-rect objects o))]
          :when r]
    (set! (.-strokeStyle ctx) (user-color color))
    (.strokeRect ctx (- (:x r) (/ 6 zoom)) (- (:y r) (/ 6 zoom)) (+ (:w r) (/ 12 zoom)) (+ (:h r) (/ 12 zoom)))))

(def ^:private pointer-path (delay (js/Path2D. "M0 0l13 8-6 1.5-3 6.5z")))

(defn- peer-cursors!
  "The others' pointers with their names, in screen pixels at every zoom."
  [^js ctx cam peers]
  (set! (.-font ctx) "12px system-ui, sans-serif")
  (set! (.-textBaseline ctx) "middle")
  (doseq [{:keys [cursor color] :as user} (vals peers) :when cursor
          :let [[x y] (geom/world->screen cam cursor) c (user-color color) label (user-name user)]]
    (.save ctx)
    (.translate ctx x y)
    (set! (.-lineWidth ctx) 1.5)
    (set! (.-strokeStyle ctx) "#ffffff")
    (set! (.-fillStyle ctx) c)
    (.stroke ctx @pointer-path)
    (.fill ctx @pointer-path)
    (let [w (+ 12 (.-width (.measureText ctx label)))]
      (.beginPath ctx)
      (.roundRect ctx 10 16 w 20 4)
      (.fill ctx)
      (set! (.-fillStyle ctx) "#ffffff")
      (.fillText ctx label 16 26))
    (.restore ctx)))

(defn- draw!
  "One frame. _tick is the image-load counter: naming it makes a loaded image redraw."
  [^js ctx w h objects cam sel drag editing theme _tick]
  (.clearRect ctx 0 0 w h)
  (.save ctx)
  (.scale ctx (:zoom cam) (:zoom cam))
  (.translate ctx (- (:x cam)) (- (:y cam)))
  (doseq [o (geom/draw-order objects)] (object! ctx objects o (:id editing) theme (:zoom cam)))
  (selection! ctx objects sel (:zoom cam) theme)
  (when drag (drag! ctx objects drag (:zoom cam) theme))
  (.restore ctx))

(defn- pointer [^js e x y]
  {:sx x :sy y :button (.-button e) :shift? (.-shiftKey e) :ctrl? (or (.-ctrlKey e) (.-metaKey e))
   :t (.-timeStamp e)})

;; the last press was on the grip, not the board: its clicks land here too
(defonce ^:private grabbed? (volatile! false))

(defn- on-down [^js e {:keys [x y]}]
  (vreset! grabbed? false)
  (.setPointerCapture (.-target e) (.-pointerId e))
  (dispatch [:pointer/down (pointer e x y)]))

(defn grab!
  "Hands a pointer pressed on the selection toolbar's grip to the board: the
  grip's press is dispatched with the board's coordinates, and the pointer's
  moves and release then reach the board as if pressed on it."
  [^js e]
  (when-let [^js c (.querySelector js/document ".board")]
    (.preventDefault e)
    (vreset! grabbed? true)
    (.setPointerCapture c (.-pointerId e))
    (let [r (.getBoundingClientRect c)]
      (dispatch [:pointer/grip (pointer e (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r)))]))))

(defn- on-move [^js e {:keys [x y]}] (dispatch [:pointer/move (pointer e x y)]))
(defn- on-up [^js e {:keys [x y]}] (dispatch [:pointer/up (pointer e x y)]))
(defn- on-leave [_ _] (dispatch [:pointer/leave]))
(defn- on-dblclick [^js e {:keys [x y]}]
  (when-not @grabbed? (dispatch [:pointer/dblclick (pointer e x y)])))

(defn- on-wheel [^js e {:keys [x y]}]
  (.preventDefault e)
  (let [mode (.-deltaMode e) page (.-innerHeight js/window)]
    (dispatch [:wheel {:dx (events/wheel-px (.-deltaX e) mode page) :dy (events/wheel-px (.-deltaY e) mode page)
                       :ctrl? (or (.-ctrlKey e) (.-metaKey e)) :shift? (.-shiftKey e) :sx x :sy y}])))

(defdraw board [] [objects [:objects] cam [:camera] sel [:selection] drag [:drag]
                   editing [:editing] tick [:img-tick] pref [:theme] dark? [:os-dark?]
                   theme (themes/effective pref dark?)]
  {:attrs {:class "board"}
   :on-pointerdown on-down :on-pointermove on-move :on-pointerup on-up :on-pointerleave on-leave
   :on-dblclick on-dblclick :on-wheel on-wheel}
  (fn [ctx {:keys [w h]}] (draw! ctx w h objects cam sel drag editing theme tick)))

;; the others on a canvas of their own over the board: their pointers move
;; many times a second, and the board need not redraw for each
(defdraw peers-layer [] [objects [:objects] cam [:camera] peers [:peers]]
  {:attrs {:class "peers"}}
  (fn [^js ctx {:keys [w h]}]
    (.clearRect ctx 0 0 w h)
    (.save ctx)
    (.scale ctx (:zoom cam) (:zoom cam))
    (.translate ctx (- (:x cam)) (- (:y cam)))
    (peer-selections! ctx objects peers (:zoom cam))
    (.restore ctx)
    (peer-cursors! ctx cam peers)))
