(ns dingsbums.views
  "Landing screen and the DOM overlays on top of the board."
  (:require [hammer.core :refer [defc dispatch]]
            [dingsbums.board :as board]
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
                      export-url (str "/api/sessions/" sid "/export")]
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
  [[:select "Select" "↖"] [:text "Text" "T"] [:frame "Frame" "▭"] [:shape "Shapes" "◆"]
   [:sticky "Sticky note" "▤"] [:connection "Connection" "↗"]])

(def ^:private shapes [[:circle "●"] [:rect "■"] [:star "★"] [:arrow "➜"]])

(defc toolbar [] [tool [:tool] shape [:shape-kind]]
  [:div.toolbar
   [:div.shapes {:hidden (not= tool :shape)}
    (for [[k icon] shapes]
      ^{:key k} [:button {:class (when (= k shape) "active") :title (name k) :on-click [:tool/shape k]} icon])]
   [:div.tools
    (for [[k label icon] tools]
      ^{:key k} [:button {:class (when (= k tool) "active") :title label :on-click [:tool/select k]} icon])
    [:span.sep]
    [:button {:title "Undo (Ctrl+Z)" :on-click [:history/undo]} "↶"]
    [:button {:title "Redo (Ctrl+Shift+Z)" :on-click [:history/redo]} "↷"]]])

(defc selection-toolbar [] [sel [:selection] objects [:objects] cam [:camera] drag [:drag] editing [:editing]
                            os (vec (keep #(get objects %) sel))
                            b (geom/bounds (keep #(geom/obj-rect objects %) os))
                            pos (when b (geom/world->screen cam [(:x b) (:y b)]))
                            show? (boolean (and pos (nil? drag) (nil? editing)))
                            left (if pos (max 8 (first pos)) 0)
                            top (if pos (max 8 (- (second pos) 48)) 0)
                            colors (cond
                                     (and (seq os) (every? #(= :shape (:kind %)) os)) model/swatches
                                     (and (seq os) (every? #(#{:shape :sticky} (:kind %)) os)) (rest model/swatches))
                            locked? (boolean (and (seq os) (every? :locked? os)))
                            groupable? (>= (count (remove #(= :connection (:kind %)) os)) 2)
                            grouped? (boolean (some :group os))]
  [:div.sel-toolbar {:hidden (not show?) :style {:left (str left "px") :top (str top "px")}}
   (for [c colors]
     ^{:key (str c)} [:button.swatch {:title (or c "No fill") :style {:background-color (or c "transparent")}
                                      :on-click [:selection/fill c]}])
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
                      size (:size (board/fit draft (:w o) (:h o)))
                      style {:left (str (first pos) "px") :top (str (second pos) "px")
                             :width (str (* zoom (:w o)) "px") :height (str (* zoom (:h o)) "px")
                             :font-size (str (* zoom size) "px") :padding (str (* zoom geom/padding) "px")
                             :color (if (= :sticky (:kind o)) (model/ink (:fill o)) "var(--text-strong)")}]
  [:textarea.text-editor {:style style :value draft :ref focus!
                          :on-input (fn [^js e] (dispatch [:edit/input (.. e -target -value)]))
                          :on-blur [:edit/commit] :on-keydown edit-keydown}])

(defc app [] [route [:route] editing-id [:editing :id]]
  (if (= route :board)
    [:div.app
     [board/board]
     [session-bar]
     [toolbar]
     [selection-toolbar]
     (when editing-id [text-editor])]
    [landing]))
