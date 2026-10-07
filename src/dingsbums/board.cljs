(ns dingsbums.board
  "The board canvas: draws the objects through the camera and turns pointer
  input into events. Text is measured with an offscreen canvas."
  (:require [hammer.core :refer [dispatch]]
            [hammer.canvas :refer [defdraw]]
            [dingsbums.events :as events]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.themes :as themes]))

(def ^:private font "px sans-serif")
(def ^:private frame-radius 8)
(def ^:private sticky-radius 4)

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

(defn- text! [^js ctx {:keys [x y w h text]} color]
  (when (seq text)
    (let [{:keys [size lines]} (fit text w h)]
      (.save ctx)
      (.beginPath ctx)
      (.rect ctx x y w h)
      (.clip ctx)
      (set! (.-font ctx) (str size font))
      (set! (.-textBaseline ctx) "top")
      (set! (.-fillStyle ctx) color)
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
      :frame (do (set! (.-strokeStyle ctx) (:sub theme))
                 (.beginPath ctx)
                 (.roundRect ctx x y w h frame-radius)
                 (.stroke ctx))
      :shape (do (path! ctx o)
                 (when-let [f (:fill o)] (set! (.-fillStyle ctx) f) (.fill ctx))
                 (.stroke ctx))
      ;; no outline but a drop shadow: a note on the board, not a filled rect
      :sticky (do (.save ctx)
                  (set! (.-shadowColor ctx) "rgba(0, 0, 0, .3)")
                  ;; shadows ignore the transform: scale by zoom and the canvas's pixel ratio
                  (let [k (* zoom js/window.devicePixelRatio)]
                    (set! (.-shadowBlur ctx) (* 8 k))
                    (set! (.-shadowOffsetY ctx) (* 3 k)))
                  (set! (.-fillStyle ctx) (:fill o "#fff176"))
                  (.beginPath ctx)
                  (.roundRect ctx x y w h sticky-radius)
                  (.fill ctx)
                  (.restore ctx)
                  (when-not (= editing-id (:id o)) (text! ctx o (model/ink (:fill o)))))
      :text (when-not (= editing-id (:id o)) (text! ctx o (:text-strong theme)))
      :image (let [^js img (image (:src o))]
               (if (and (.-complete img) (pos? (.-naturalWidth img)))
                 (.drawImage ctx img x y w h)
                 (do (set! (.-fillStyle ctx) (:hover-plain theme)) (.fillRect ctx x y w h))))
      nil)))

(defn- selection! [^js ctx objects sel zoom theme]
  (set! (.-lineWidth ctx) (/ 1.5 zoom))
  (doseq [id sel
          :let [o (get objects id) r (when o (geom/obj-rect objects o))]
          :when r]
    (set! (.-strokeStyle ctx) (if (:locked? o) (:text-dim theme) (:accent theme)))
    (.setLineDash ctx (if (:locked? o) #js [(/ 4 zoom) (/ 4 zoom)] #js []))
    (.strokeRect ctx (- (:x r) (/ 3 zoom)) (- (:y r) (/ 3 zoom)) (+ (:w r) (/ 6 zoom)) (+ (:h r) (/ 6 zoom))))
  (.setLineDash ctx #js [])
  (let [o (when (= 1 (count sel)) (get objects (first sel)))]
    (when (and o (not (:locked? o)) (not= :connection (:kind o)))
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
  {:sx x :sy y :button (.-button e) :shift? (.-shiftKey e) :t (.-timeStamp e)})

(defn- on-down [^js e {:keys [x y]}]
  (.setPointerCapture (.-target e) (.-pointerId e))
  (dispatch [:pointer/down (pointer e x y)]))

(defn- on-move [^js e {:keys [x y]}] (dispatch [:pointer/move (pointer e x y)]))
(defn- on-up [^js e {:keys [x y]}] (dispatch [:pointer/up (pointer e x y)]))
(defn- on-dblclick [^js e {:keys [x y]}] (dispatch [:pointer/dblclick (pointer e x y)]))

(defn- on-wheel [^js e {:keys [x y]}]
  (.preventDefault e)
  (dispatch (events/wheel-action {:dx (.-deltaX e) :dy (.-deltaY e) :ctrl? (or (.-ctrlKey e) (.-metaKey e))
                                  :shift? (.-shiftKey e) :sx x :sy y})))

(defdraw board [] [objects [:objects] cam [:camera] sel [:selection] drag [:drag]
                   editing [:editing] tick [:img-tick] pref [:theme] dark? [:os-dark?]
                   theme (themes/effective pref dark?)]
  {:attrs {:class "board"}
   :on-pointerdown on-down :on-pointermove on-move :on-pointerup on-up
   :on-dblclick on-dblclick :on-wheel on-wheel}
  (fn [ctx {:keys [w h]}] (draw! ctx w h objects cam sel drag editing theme tick)))
