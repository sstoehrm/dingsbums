(ns dingsbums.views
  "Landing screen and the DOM overlays on top of the board."
  (:require [hammer.core :refer [defc dispatch]]
            [dingsbums.board :as board]
            [dingsbums.events :as events]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.themes :as themes]))

(defc landing [] [input [:join-input] err [:error]]
  [:main.landing
   [:h1 "dingsbums"]
   [:button.primary {:on-click [:landing/create]} "Create new session"]
   [:form.join {:on-submit (fn [^js e] (.preventDefault e) (dispatch [:landing/join]))}
    [:input {:placeholder "Session id" :value input
             :on-input (fn [^js e] (dispatch [:landing/input (.. e -target -value)]))}]
    [:button {:type "submit"} "Join session"]]
   [:p.error err]])

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
   [:code.sid sid]
   [:button {:title "Copy session id" :on-click [:session/copy-id]} "Copy"]
   [:a.button {:href export-url :download ""} "Export"]
   [:label.button "Import"
    [:input {:type "file" :accept ".tar" :hidden true :on-change import-change}]]
   [:select {:title "Theme (saved in this browser)" :value (if theme (name theme) "") :on-change theme-change}
    [:option {:value ""} "default (follow the OS)"]
    (for [n themes/names] ^{:key n} [:option {:value (name n)} (name n)])]
   [:span.badge {:hidden (boolean online?)} "offline"]
   [:span.msg msg]])

(def ^:private tools
  [[:select "Select (V)" "↖"] [:text "Text (T)" "T"] [:frame "Frame (F)" "▭"] [:shape "Shapes (S, again: next shape)" "◆"]
   [:sticky "Sticky note (N)" "▤"] [:connection "Connection (L)" "↗"]])

(def ^:private shape-icons {:circle "●" :rect "■" :star "★" :arrow "➜"})

(defc toolbar [] [tool [:tool] shape [:shape-kind]]
  [:div.toolbar
   [:div.shapes {:hidden (not= tool :shape)}
    (for [k model/shape-kinds]
      ^{:key k} [:button {:class (when (= k shape) "active") :title (name k) :on-click [:tool/shape k]} (shape-icons k)])]
   [:div.tools
    (for [[k label icon] tools]
      ^{:key k} [:button {:class (when (= k tool) "active") :title label :on-click [:tool/select k]} icon])
    [:span.sep]
    [:button {:title "Undo (Ctrl+Z)" :on-click [:history/undo]} "↶"]
    [:button {:title "Redo (Ctrl+Shift+Z)" :on-click [:history/redo]} "↷"]]])

(defc tool-hint [] [tool [:tool] shape [:shape-kind] drag [:drag]
                    hint (events/tool-hint tool shape)]
  [:div.hint {:hidden (or (nil? hint) (some? drag))} hint])

(defc zoom-bar [] [zoom [:camera :zoom] hand? [:hand?]]
  [:div.zoom-bar
   [:button {:class (when hand? "active") :on-click [:hand/toggle]
             :title "Hand mode (H): drag the canvas to pan, the wheel zooms, Shift- or Ctrl-drag selects"}
    "✋"]
   [:span.sep]
   [:button {:title "Zoom out" :on-click [:camera/zoom-step 0.8]} "−"]
   [:span.zoom (str (js/Math.round (* 100 zoom)) "%")]
   [:button {:title "Zoom in" :on-click [:camera/zoom-step 1.25]} "+"]])

(def ^:private layer-moves
  [[:front "Bring to front" "⤒" "Home"] [:forward "Bring forward" "↑"] [:backward "Send backward" "↓"]
   [:back "Send to back" "⤓" "End"]])

(def ^:private mixed-fill "conic-gradient(#e57373, #fff176, #81c784, #64b5f6, #ba68c8, #e57373)")

(defc selection-toolbar [] [sel [:selection] objects [:objects] cam [:camera] drag [:drag] editing [:editing]
                            sel-menu [:sel-menu] viewport [:viewport]
                            vw (first viewport)
                            menu (events/open-menu sel-menu sel)
                            os (vec (keep #(get objects %) sel))
                            b (geom/bounds (keep #(geom/obj-rect objects %) os))
                            pos (when b (geom/world->screen cam [(+ (:x b) (/ (:w b) 2)) (:y b)]))
                            show? (boolean (and pos (nil? drag) (nil? editing)))
                            cx (if pos (first pos) 0)
                            top (if pos (max 8 (- (second pos) 48)) 0)
                            colors (cond
                                     (and (seq os) (every? #(= :shape (:kind %)) os)) model/swatches
                                     (and (seq os) (every? #(#{:shape :sticky} (:kind %)) os)) (rest model/swatches))
                            fills (set (map :fill os))
                            locked? (boolean (and (seq os) (every? :locked? os)))
                            groupable? (>= (count (remove #(= :connection (:kind %)) os)) 2)
                            grouped? (boolean (some :group os))]
  ;; centered over the selection, kept 8px inside the viewport
  [:div.sel-toolbar {:hidden (not show?)
                     :style {:left (str cx "px") :top (str top "px")
                             :transform (str "translateX(clamp(" (- 8 cx) "px, -50%, calc(" (- vw 8 cx) "px - 100%)))")}}
   [:span.menu {:hidden (empty? colors)}
    [:button.swatch {:title "Fill" :class (when (= menu :fill) "active")
                     :style {:background (cond (< 1 (count fills)) mixed-fill (first fills) (first fills) :else "transparent")}
                     :on-click [:selection/menu :fill]}]
    (when (= menu :fill)
      [:div.sel-menu
       (for [c colors]
         ^{:key (str c)} [:button.swatch {:title (or c "No fill") :style {:background-color (or c "transparent")}
                                          :on-click [:selection/fill c]}])])]
   [:span.menu {:hidden locked?}
    [:button {:title "Order" :class (when (= menu :layer) "active") :on-click [:selection/menu :layer]} "Order ▾"]
    (when (= menu :layer)
      [:div.sel-menu.column
       (for [[k label icon key] layer-moves]
         ^{:key k} [:button {:on-click [:selection/layer k]} [:span icon] [:span label] [:kbd key]])])]
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
                             :color (if-let [b (if (= :sticky (:kind o)) o (geom/backdrop objects o))]
                                      (model/ink (:fill b))
                                      "var(--text-strong)")}]
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
