(ns dingsbums.views
  "Landing screen and the DOM overlays on top of the board."
  (:require [hammer.core :refer [defc dispatch]]
            [dingsbums.board :as board]
            [dingsbums.events :as events]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.themes :as themes]))

(defn- icon [& shapes] (into [:svg.icon {:viewBox "0 0 24 24" :aria-hidden "true"}] shapes))

(def ^:private icons
  "Stroke icons, drawn in the text colour."
  {:select (icon [:path {:d "M6 3l13 8-6 1.5L10 19z"}])
   :text (icon [:path {:d "M5 5h14"}] [:path {:d "M12 5v14"}])
   :frame (icon [:rect {:x 4 :y 5 :width 16 :height 14 :rx 2}])
   :shape (icon [:path {:d "M12 3l9 9-9 9-9-9z"}])
   :sticky (icon [:path {:d "M4 4h16v10l-6 6H4z"}] [:path {:d "M14 20v-6h6"}])
   :connection (icon [:path {:d "M5 19L19 5"}] [:path {:d "M10 5h9v9"}])
   :undo (icon [:path {:d "M9 14L4 9l5-5"}] [:path {:d "M4 9h10a6 6 0 0 1 0 12h-3"}])
   :redo (icon [:path {:d "M15 14l5-5-5-5"}] [:path {:d "M20 9H10a6 6 0 0 0 0 12h3"}])
   :hand (icon [:path {:d "M8 13V6a1.5 1.5 0 0 1 3 0v5"}] [:path {:d "M11 11V4.5a1.5 1.5 0 0 1 3 0V11"}]
               [:path {:d "M14 11V6a1.5 1.5 0 0 1 3 0v7c0 4-2.5 7-6 7-2.5 0-4-1.2-5.5-3.5L3.8 13a1.5 1.5 0 0 1 2.4-1.8L8 13"}])
   :grip (into (icon) (for [x [9 15] y [6 12 18]] [:circle {:cx x :cy y :r 1.5 :fill "currentColor" :stroke "none"}]))
   :minus (icon [:path {:d "M6 12h12"}])
   :plus (icon [:path {:d "M6 12h12"}] [:path {:d "M12 6v12"}])
   :circle (icon [:circle {:cx 12 :cy 12 :r 8}])
   :rect (icon [:rect {:x 4 :y 5 :width 16 :height 14 :rx 1}])
   :star (icon [:path {:d "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"}])
   :arrow (icon [:path {:d "M4 12h15"}] [:path {:d "M13 6l6 6-6 6"}])
   :front (icon [:path {:d "M6 3h12"}] [:path {:d "M12 21V8"}] [:path {:d "M7 12l5-5 5 5"}])
   :forward (icon [:path {:d "M12 19V5"}] [:path {:d "M7 10l5-5 5 5"}])
   :backward (icon [:path {:d "M12 5v14"}] [:path {:d "M7 14l5 5 5-5"}])
   :back (icon [:path {:d "M12 3v13"}] [:path {:d "M7 12l5 5 5-5"}] [:path {:d "M6 21h12"}])})

(def ^:private skyline
  "The landing's moonlit skyline. A scene in its own colours, whatever the
  theme; only the torn bottom edge takes the page colour."
  [:svg.scene {:viewBox "0 0 1440 600" :preserveAspectRatio "xMidYMax slice" :aria-hidden "true"}
   [:defs
    [:linearGradient#sky-g {:x1 0 :y1 0 :x2 0 :y2 1}
     [:stop {:offset 0 :stop-color "#1d2540"}] [:stop {:offset 0.45 :stop-color "#3a4668"}]
     [:stop {:offset 0.74 :stop-color "#8c8a99"}] [:stop {:offset 1 :stop-color "#d8c7a8"}]]
    [:radialGradient#sky-glow {:cx 0.625 :cy 0.35 :r 0.34}
     [:stop {:offset 0 :stop-color "#f6ead0" :stop-opacity 0.5}] [:stop {:offset 1 :stop-color "#f6ead0" :stop-opacity 0}]]
    [:linearGradient#sky-water {:x1 0 :y1 0 :x2 0 :y2 1}
     [:stop {:offset 0 :stop-color "#3b4566"}] [:stop {:offset 1 :stop-color "#232a42"}]]
    ;; rough, painted edges and paper grain
    [:filter#sky-paint {:x "-5%" :y "-5%" :width "110%" :height "110%"}
     [:feTurbulence {:type "fractalNoise" :baseFrequency 0.022 :numOctaves 3 :seed 7 :result "n"}]
     [:feDisplacementMap {:in "SourceGraphic" :in2 "n" :scale 9 :xChannelSelector "R" :yChannelSelector "G"}]]
    [:filter#sky-soft {:x "-20%" :y "-50%" :width "140%" :height "200%"} [:feGaussianBlur {:stdDeviation 16}]]
    [:filter#sky-grain
     [:feTurbulence {:type "fractalNoise" :baseFrequency 0.85 :numOctaves 2 :seed 3}]
     [:feColorMatrix {:values "0 0 0 0 1  0 0 0 0 0.95  0 0 0 0 0.86  0 0 0 0.28 0"}]]]
   [:rect {:width 1440 :height 600 :fill "url(#sky-g)"}]
   [:rect {:width 1440 :height 600 :fill "url(#sky-glow)"}]
   (into [:g {:fill "#f3ead6"}]
         (for [[x y r o] [[120 80 1.2 0.7] [260 132 1 0.5] [410 64 1.4 0.6] [560 120 1 0.5] [690 54 1.3 0.7]
                          [820 102 1 0.5] [1200 70 1.2 0.6] [1330 118 1.5 0.6] [1400 48 1 0.5]]]
           [:circle {:cx x :cy y :r r :opacity o}]))
   [:circle {:cx 900 :cy 210 :r 46 :fill "#f4ead3" :filter "url(#sky-paint)"}]
   [:circle {:cx 916 :cy 200 :r 34 :fill "#e7d9bc" :opacity 0.35}]
   [:g {:filter "url(#sky-soft)" :opacity 0.55}
    [:ellipse {:cx 300 :cy 196 :rx 260 :ry 30 :fill "#9aa0b5"}]
    [:ellipse {:cx 860 :cy 254 :rx 330 :ry 26 :fill "#a7a6b2"}]
    [:ellipse {:cx 1260 :cy 214 :rx 190 :ry 20 :fill "#b9b3b4"}]]
   [:path {:filter "url(#sky-paint)" :fill "#5d6683" :opacity 0.8
           :d "M0 440 V372 H48 V352 H92 V384 H140 V330 H168 V300 H176 V286 H184 V300 H196 V330 H232 V368 H280 V344 H330 V392 H372 V356 H410 V318 H440 V356 H486 V376 H530 V340 H566 V310 H574 V296 H582 V310 H600 V340 H640 V380 H700 V350 H744 V366 H790 V322 H830 V372 H880 V346 H926 V384 H970 V336 H1004 V306 H1030 V336 H1070 V368 H1120 V340 H1160 V378 H1210 V328 H1240 V298 H1248 V280 H1256 V298 H1270 V328 H1310 V360 H1360 V340 H1400 V370 H1440 V440 Z"}]
   [:path {:filter "url(#sky-paint)" :fill "#2f3753"
           :d "M0 482 V420 H70 V396 H120 V430 H160 V372 H210 V410 H260 V388 H300 V350 H330 V388 H380 V420 H430 V380 H470 V404 H520 V362 H560 V340 H600 V362 H640 V416 H700 V392 H740 V368 H790 V400 H850 V376 H900 V418 H950 V384 H990 V352 H1040 V398 H1090 V372 H1140 V412 H1190 V380 H1230 V346 H1280 V384 H1330 V408 H1390 V388 H1440 V482 Z"}]
   (into [:g {:fill "#f2d89c" :opacity 0.85}]
         (for [[x y] [[176 384] [190 396] [310 362] [316 376] [452 392] [540 370] [572 352] [580 366]
                      [752 380] [1000 366] [1008 380] [1110 388] [1240 360] [1252 372]]]
           [:rect {:x x :y y :width 4 :height 6}]))
   [:rect {:y 480 :width 1440 :height 120 :fill "url(#sky-water)"}]
   (into [:g {:stroke "#f0e2c2" :stroke-linecap "round" :fill "none" :filter "url(#sky-paint)"}]
         (for [[d w o] [["M864 500 H936" 3 0.55] ["M874 516 H926" 2.5 0.45] ["M856 532 H944" 2 0.35]
                        ["M882 550 H918" 2 0.3] ["M868 568 H932" 1.5 0.22]]]
           [:path {:d d :stroke-width w :opacity o}]))
   [:path {:filter "url(#sky-paint)" :fill "#1c2236" :d "M0 600 V518 C 120 500 260 508 380 538 C 460 558 520 600 520 600 Z"}]
   [:path {:filter "url(#sky-paint)" :fill "#1c2236" :d "M1440 600 V528 C 1340 510 1240 520 1160 558 C 1120 578 1100 600 1100 600 Z"}]
   [:rect {:width 1440 :height 600 :filter "url(#sky-grain)"}]
   [:path {:filter "url(#sky-paint)" :style {:fill "var(--bg)"} :d "M-20 620 V588 Q 360 600 720 586 T 1460 590 V620 Z"}]])

(defc landing [] [input [:join-input] err [:error]]
  [:main.landing
   [:div.hero
    skyline
    [:header.landing-nav
     [:span.wordmark "dingsbums"]
     [:a {:href "https://github.com/sstoehrm/dingsbums"} "GitHub"]]]
   [:div.content
    [:div.intro
     [:p.eyebrow "A whiteboard for thinking out loud"]
     [:h1 "A quiet place to think out loud, together."]
     [:p.lead "Open a session, share its link, and draw on the same board in real time. "
      "No accounts and no setup: the link is the invitation."]]
    [:div.card
     [:h2 "Start a board"]
     [:p.note "You get a link to send to whoever should join."]
     [:button.primary {:on-click [:landing/create]} "Create new session"]
     [:div.or "or join one"]
     [:form.join {:on-submit (fn [^js e] (.preventDefault e) (dispatch [:landing/join]))}
      [:label {:for "join-id"} "Session id"]
      [:div.row
       [:input {:id "join-id" :placeholder "e.g. 4ea21bda-f029" :value input
                :on-input (fn [^js e] (dispatch [:landing/input (.. e -target -value)]))}]
       [:button {:type "submit"} "Join"]]]
     [:p.error err]]]])

;; until the board's first snapshot is in: a big board takes a while to arrive
(defc loading [] [online? [:online?]]
  [:main.loading {:role "status"}
   [:div.spinner]
   [:p (if online? "Loading board…" "Connecting…")]])

(defn- import-change [^js e]
  (let [^js input (.-target e)
        f (aget (.-files input) 0)]
    (set! (.-value input) "")
    (when f (dispatch [:import/file f]))))

(defn- theme-change [^js e]
  (let [v (.. e -target -value)]
    (.blur (.-target e))
    (dispatch [:theme/select (when (seq v) (keyword v))])))

(defc session-bar [] [sid [:session] online? [:online?] msg [:message] theme [:theme]
                      export-url (str "api/sessions/" sid "/export")]
  [:div.session-bar
   [:span.wordmark "dingsbums"]
   [:span.sep]
   [:code.sid sid]
   [:button {:title "Copy session id" :on-click [:session/copy-id]} "Copy"]
   [:a.button {:href export-url :download ""} "Export"]
   [:label.button "Import"
    [:input {:type "file" :accept ".tar" :hidden true :on-change import-change}]]
   [:select {:title "Theme (saved in this browser)" :value (if theme (name theme) "") :on-change theme-change}
    [:option {:value ""} "parchment (default)"]
    [:option {:value "os"} "follow the OS"]
    (for [n (rest themes/names)] ^{:key n} [:option {:value (name n)} (name n)])]
   [:span.badge {:hidden (boolean online?)} "offline"]
   [:span.msg msg]])

(def ^:private tools
  [[:select "Select (V)"] [:text "Text (T)"] [:frame "Frame (F)"] [:shape "Shapes (S, again: next shape)"]
   [:sticky "Sticky note (N)"] [:connection "Connection (L)"]])

(defc toolbar [] [tool [:tool] shape [:shape-kind]]
  [:div.toolbar
   [:div.shapes {:hidden (not= tool :shape)}
    (for [k model/shape-kinds]
      ^{:key k} [:button {:class (when (= k shape) "active") :title (name k) :aria-label (name k)
                          :on-click [:tool/shape k]}
                 (icons k)])]
   [:div.tools
    (for [[k label] tools]
      ^{:key k} [:button {:class (when (= k tool) "active") :title label :aria-label label :on-click [:tool/select k]}
                 (icons k)])
    [:span.sep]
    [:button {:title "Undo (Ctrl+Z)" :aria-label "Undo" :on-click [:history/undo]} (icons :undo)]
    [:button {:title "Redo (Ctrl+Shift+Z)" :aria-label "Redo" :on-click [:history/redo]} (icons :redo)]]])

(defc tool-hint [] [tool [:tool] shape [:shape-kind] drag [:drag]
                    hint (events/tool-hint tool shape)]
  [:div.hint {:hidden (or (nil? hint) (some? drag))} hint])

(defc zoom-bar [] [zoom [:camera :zoom] hand? [:hand?]]
  [:div.zoom-bar
   [:button {:class (when hand? "active") :on-click [:hand/toggle]
             :title "Hand mode (H): drag the canvas to pan, the wheel zooms, Shift- or Ctrl-drag selects"
             :aria-label "Hand mode"}
    (icons :hand)]
   [:span.sep]
   [:button {:title "Zoom out" :aria-label "Zoom out" :on-click [:camera/zoom-step 0.8]} (icons :minus)]
   [:span.zoom (str (js/Math.round (* 100 zoom)) "%")]
   [:button {:title "Zoom in" :aria-label "Zoom in" :on-click [:camera/zoom-step 1.25]} (icons :plus)]])

(def ^:private layer-moves
  [[:front "Bring to front" "Home"] [:forward "Bring forward"] [:backward "Send backward"]
   [:back "Send to back" "End"]])

(def ^:private mixed-fill "conic-gradient(#e57373, #fff176, #81c784, #64b5f6, #ba68c8, #e57373)")

(defn- common-color
  "The CSS background for a set of colours: the one colour, mixed-fill, or none."
  [cs none]
  (cond (< 1 (count cs)) mixed-fill (first cs) (first cs) :else none))

;; a menu needs about this much room above the toolbar, else it opens below
(def ^:private menu-room 170)

(defc selection-toolbar [] [sel [:selection] objects [:objects] cam [:camera] drag [:drag] editing [:editing]
                            sel-menu [:sel-menu] viewport [:viewport]
                            vw (first viewport)
                            menu (events/open-menu sel-menu sel)
                            os (vec (keep #(get objects %) sel))
                            b (geom/bounds (keep #(geom/obj-rect objects %) os))
                            pos (when b (geom/world->screen cam [(+ (:x b) (/ (:w b) 2)) (:y b)]))
                            show? (boolean (and pos (nil? drag) (nil? editing)))
                            cx (if pos (first pos) 0)
                            top (if pos (max 8 (- (second pos) 56)) 0)
                            colors (cond
                                     (and (seq os) (every? #(= :shape (:kind %)) os)) model/swatches
                                     (and (seq os) (every? #(#{:shape :sticky} (:kind %)) os)) (rest model/swatches))
                            fills (set (map :fill os))
                            texts? (boolean (and (seq os) (every? #(model/text-kinds (:kind %)) os)))
                            text-colors (set (map :color os))
                            locked? (boolean (and (seq os) (every? :locked? os)))
                            movable? (boolean (seq (model/movable objects sel)))
                            groupable? (>= (count (remove #(= :connection (:kind %)) os)) 2)
                            grouped? (boolean (some :group os))]
  ;; centered over the selection, kept 8px inside the viewport
  [:div.sel-toolbar {:hidden (not show?) :class (when (< top menu-room) "down")
                     :style {:left (str cx "px") :top (str top "px")
                             :transform (str "translateX(clamp(" (- 8 cx) "px, -50%, calc(" (- vw 8 cx) "px - 100%)))")}}
   [:button.grip {:hidden (not movable?) :title "Move: drag" :aria-label "Move"
                  :on-pointerdown #(board/grab! % :pointer/grip)}
    (icons :grip)]
   [:span.menu {:hidden (empty? colors)}
    [:button.menu-button {:title "Fill" :class (when (= menu :fill) "active") :on-click [:selection/menu :fill]}
     [:span.dot {:style {:background (common-color fills "transparent")}}]]
    (when (= menu :fill)
      [:div.sel-menu
       (for [c colors]
         ^{:key (str c)} [:button.swatch {:title (or c "No fill") :style {:background-color (or c "transparent")}
                                          :on-click [:selection/fill c]}])])]
   [:span.menu {:hidden (not texts?)}
    [:button.menu-button {:title "Text colour" :class (when (= menu :text) "active") :on-click [:selection/menu :text]}
     [:span.text-a "A" [:span.bar {:style {:background (common-color text-colors "currentColor")}}]]]
    (when (= menu :text)
      [:div.sel-menu
       (for [c model/text-colors]
         ^{:key (str c)} [:button.swatch {:title (or c "Automatic") :class (when-not c "auto")
                                          :style {:background-color (or c "transparent")}
                                          :on-click [:selection/text-color c]}
                          (when-not c "A")])])]
   [:span.menu {:hidden locked?}
    [:button {:title "Order" :class (when (= menu :layer) "active") :on-click [:selection/menu :layer]} "Order"]
    (when (= menu :layer)
      [:div.sel-menu.column
       (for [[k label key] layer-moves]
         ^{:key k} [:button {:on-click [:selection/layer k]} (icons k) [:span label] [:kbd key]])])]
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
                      pad (geom/text-padding (:kind o))
                      size (:size (board/fit draft (:w o) (:h o) pad))
                      style {:left (str (first pos) "px") :top (str (second pos) "px")
                             :width (str (* zoom (:w o)) "px") :height (str (* zoom (:h o)) "px")
                             :font-family board/font-family :font-size (str (* zoom size) "px") :padding (str (* zoom pad) "px")
                             :color (or (model/text-color objects o) "var(--text-strong)")}]
  [:textarea.text-editor {:style style :value draft :ref focus!
                          :on-input (fn [^js e] (dispatch [:edit/input (.. e -target -value)]))
                          :on-blur [:edit/commit] :on-keydown edit-keydown}])

(defc app [] [route [:route] loading? [:loading?] editing-id [:editing :id]]
  (cond
    (= route :landing) [landing]
    loading? [loading]
    :else
    [:div.app
     [board/board]
     [session-bar]
     [toolbar]
     [zoom-bar]
     [tool-hint]
     [selection-toolbar]
     (when editing-id [text-editor])]))
