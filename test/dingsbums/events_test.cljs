(ns dingsbums.events-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [hammer.core :refer [mount! dispatch-sync]]
            [hammer.state :as state]
            [hammer.testing :as t]
            [hammer.tubes :as tubes]
            [dingsbums.events :as ev]
            [dingsbums.geom :as geom]
            [dingsbums.themes :as themes]))

(def board (assoc ev/initial-db :route :board :session "s1"))
(def sticky {:id "a" :kind :sticky :x 0 :y 0 :w 100 :h 100 :z 1 :fill "#fff176" :text "hi"})
(def shape {:id "b" :kind :shape :shape :rect :x 200 :y 0 :w 100 :h 100 :z 2})
(def with-objs (assoc board :objects {"a" sticky "b" shape}))

(defn- pe [sx sy & {:as opts}] (merge {:sx sx :sy sy :button 0 :shift? false :t 0} opts))

(defn- drag
  "Effect map of pointer-up after down at [x1 y1] and a move to [x2 y2]."
  [db [x1 y1] [x2 y2]]
  (let [db (:db (ev/pointer-down db (pe x1 y1)))
        db (or (:db (ev/pointer-move db (pe x2 y2 :t 100))) db)]
    (ev/pointer-up db (pe x2 y2 :t 100))))

(defn- objects-of [fx] (vals (get-in fx [:db :objects])))

(deftest key-actions
  (is (= [:history/undo] (ev/key-action {:key "z" :ctrl? true})))
  (is (= [:history/redo] (ev/key-action {:key "Z" :ctrl? true :shift? true})))
  (is (= [:history/redo] (ev/key-action {:key "y" :ctrl? true})))
  (is (= [:selection/group] (ev/key-action {:key "g" :ctrl? true})))
  (is (= [:selection/ungroup] (ev/key-action {:key "G" :ctrl? true :shift? true})))
  (is (= [:edit/start] (ev/key-action {:key "F2"})))
  (is (= [:selection/delete] (ev/key-action {:key "Delete"})))
  (is (= [:selection/delete] (ev/key-action {:key "Backspace"})))
  (is (= [:selection/clear] (ev/key-action {:key "Escape"})))
  (is (= [:space true] (ev/key-action {:key " "})))
  (is (= [:selection/layer :front] (ev/key-action {:key "Home"})))
  (is (= [:selection/layer :back] (ev/key-action {:key "End"})))
  (is (nil? (ev/key-action {:key "c" :ctrl? true})) "browser copy untouched")
  (is (nil? (ev/key-action {:key "a"}))))

(deftest wheel
  (let [cam (fn [db e] (get-in (ev/wheel db (merge {:dx 0 :dy 0 :sx 0 :sy 0} e)) [:db :camera]))
        zoom (fn [db e] (:zoom (cam db e)))
        hand (assoc board :hand? true)]
    (is (= {:x 3 :y 40 :zoom 1} (cam board {:dx 3 :dy 40})))
    (is (= {:x 40 :y 0 :zoom 1} (cam board {:dy 40 :shift? true})) "shift scrolls sideways")
    (is (= {:x 40 :y 0 :zoom 1} (cam board {:dx 40 :shift? true})) "browsers that already swap the axes")
    (is (= 1.15 (zoom board {:dy -100 :ctrl? true})) "a mouse notch zooms in 15%")
    (is (= (/ 1 1.15) (zoom board {:dy 100 :ctrl? true})) "and out 15%")
    (is (= 1.15 (zoom board {:dy -1000 :ctrl? true})) "never more per event")
    (is (= (js/Math.exp 0.04) (zoom board {:dy -4 :ctrl? true})) "a trackpad pinch stays proportional")
    (is (= [5 6] (geom/world->screen (cam board {:dy -100 :ctrl? true :sx 5 :sy 6}) [5 6])) "at the pointer")
    (is (= 1.15 (zoom hand {:dy -100})) "hand mode: the wheel zooms")
    (is (= {:x 40 :y 0 :zoom 1} (cam hand {:dy 40 :shift? true})) "hand mode: shift still scrolls sideways")
    (is (= {:x 40 :y 0 :zoom 1} (cam hand {:dx 40})) "hand mode: a sideways wheel scrolls sideways"))
  (is (= 40 (ev/wheel-px 40 0 800)) "pixels")
  (is (= 48 (ev/wheel-px 3 1 800)) "lines (Firefox)")
  (is (= 800 (ev/wheel-px 1 2 800)) "pages"))

(deftest tool-keys
  (doseq [[k tool] {"v" :select "t" :text "f" :frame "n" :sticky "l" :connection}]
    (is (= [:tool/select tool] (ev/key-action {:key k})) k))
  (is (= [:tool/select :text] (ev/key-action {:key "T"})) "caps lock")
  (is (= [:tool/select :text] (ev/key-action {:key "T" :shift? true})) "with shift too: the tooltips show capitals")
  (is (= [:tool/shapes] (ev/key-action {:key "s"})))
  (is (= [:hand/toggle] (ev/key-action {:key "h"})))
  (is (nil? (ev/key-action {:key "f" :alt? true})) "Alt+letter is the browser's")
  (is (nil? (ev/key-action {:key "s" :repeat? true})) "holding a key does not cycle")
  (is (nil? (ev/hand-toggle ev/initial-db)) "not on the landing page")
  (is (= [:shape :rect] ((juxt :tool :shape-kind) (:db (ev/shapes-key board)))) "S picks the shape tool")
  (is (= :star (get-in (ev/shapes-key (assoc board :tool :shape :shape-kind :rect)) [:db :shape-kind])) "again: the next shape")
  (is (= :circle (get-in (ev/shapes-key (assoc board :tool :shape :shape-kind :arrow)) [:db :shape-kind])) "round"))

(deftest tool-toggle-and-hint
  (is (= :sticky (get-in (ev/select-tool board :sticky) [:db :tool])))
  (is (= :select (get-in (ev/select-tool (assoc board :tool :sticky) :sticky) [:db :tool])) "again: back to Select")
  (is (= :select (get-in (ev/select-tool board :select) [:db :tool])))
  (is (nil? (ev/tool-hint :select :rect)))
  (is (= "Click or drag on the board to place a sticky note · N or Esc to cancel" (ev/tool-hint :sticky :rect)))
  (is (= "Click or drag on the board to place a star · S for the next shape, Esc to cancel" (ev/tool-hint :shape :star)))
  (is (= "Drag from one object to another to connect them · L or Esc to cancel" (ev/tool-hint :connection :rect)))
  (doseq [t [:text :frame]] (is (string? (ev/tool-hint t :rect)) t)))

(deftest theme
  (let [fx (ev/theme-select board :nord)]
    (is (= :nord (get-in fx [:db :theme])))
    (is (= [:nord (:nord themes/themes)] (:theme/apply fx))))
  (is (= [nil (:parchment themes/themes)] (:theme/apply (ev/theme-select board nil))) "the default is parchment")
  (is (= [nil (:parchment themes/themes)] (:theme/apply (ev/theme-os board true))) "whatever the OS says")
  (is (= [:os (:light themes/themes)] (:theme/apply (ev/theme-select board :os))))
  (is (= [nil (:parchment themes/themes)] (:theme/apply (ev/theme-select board :parchment))) "parchment is the default")
  (is (= [:os (:dark themes/themes)] (:theme/apply (ev/theme-os (assoc board :theme :os) true))) "follow the OS")
  (let [fx (ev/theme-os (assoc board :theme :paper) true)]
    (is (true? (get-in fx [:db :os-dark?])))
    (is (= [:paper (:paper themes/themes)] (:theme/apply fx)) "a picked theme wins over the OS"))
  (is (= [nil (:parchment themes/themes)] (:theme/apply (ev/theme-select board :nope))) "unknown names are dropped"))

(deftest themes-are-only-colours
  (let [ks (set (keys (:parchment themes/themes)))]
    (is (= :parchment (first themes/names)))
    (is (every? #(= ks (set (keys %))) (vals themes/themes)) "every theme sets the same keys")
    (is (every? #(re-matches #"#[0-9a-fA-F]{3,8}" %) (mapcat vals (vals themes/themes))) "and only colours")
    (is (every? themes/pref? (cons :os themes/names)))
    (is (not (themes/pref? :nope)))))

(deftest routing
  (let [fx (ev/route-changed with-objs "")]
    (is (= :landing (get-in fx [:db :route])))
    (is (= {} (get-in fx [:db :objects])))
    (is (contains? fx :hammer.tubes/destroy)))
  (is (false? (get-in (ev/route-changed (assoc board :loading? true) "") [:db :loading?])) "left while loading")
  (let [fx (ev/route-changed ev/initial-db " abc ")]
    (is (= [:checking "abc"] ((juxt :route :session) (:db fx))))
    (is (true? (get-in fx [:db :loading?])) "the loading screen shows until the snapshot")
    (is (= "api/sessions/abc" (get-in fx [:http :uri])) "relative: works under a proxy's path prefix")
    (is (= [:session/exists "abc"] (get-in fx [:http :on-success]))))
  (is (nil? (ev/route-changed board "s1")) "already on that board")
  (is (nil? (ev/session-exists (assoc board :session "other") "s1")) "stale answer for a session we left")
  (is (= {:url "ws://localhost/ws" :params {:session "s1"}}
         (select-keys (:hammer.tubes/create (ev/session-exists (assoc board :route :checking) "s1")) [:url :params])))
  (let [fx (ev/session-not-found (assoc board :route :checking :loading? true) "s1")]
    (is (= [:landing "Session not found" false] ((juxt :route :error :loading?) (:db fx))))
    (is (= "" (:set-hash fx))))
  (let [fx (ev/session-missing with-objs)]
    (is (= [:landing "Session expired or not found" {}] ((juxt :route :error :objects) (:db fx))))
    (is (false? (get-in (ev/session-missing (assoc board :loading? true)) [:db :loading?])))
    (is (contains? fx :hammer.tubes/destroy)))
  (is (= "x" (:set-hash (ev/landing-join (assoc ev/initial-db :join-input " x ")))))
  (is (nil? (ev/landing-join (assoc ev/initial-db :join-input "  "))))
  (is (= "new" (:set-hash (ev/session-created ev/initial-db {:id "new"})))))

(deftest ws-url
  (is (= "ws://localhost/ws" (ev/ws-url)))
  (is (= "wss://example.com/dingsbums/ws" (ev/ws-url "https://example.com/dingsbums/#s1")) "under a proxy's path prefix")
  (is (= "ws://example.com:8080/ws" (ev/ws-url "http://example.com:8080/"))))

(deftest creating-objects
  (testing "click creates a default-size object centered on the point"
    (let [fx (drag (assoc board :tool :sticky) [300 300] [300 300])
          [o] (objects-of fx)]
      (is (= {:kind :sticky :x 200 :y 200 :w 200 :h 200} (select-keys o [:kind :x :y :w :h])))
      (is (= :select (get-in fx [:db :tool])))
      (is (= #{(:id o)} (get-in fx [:db :selection])))
      (is (= [[:op/upsert [o]]] (:tube/send fx)))))
  (testing "drag spans the rect; text starts editing"
    (let [fx (drag (assoc board :tool :text) [10 10] [110 60])
          [o] (objects-of fx)]
      (is (= {:kind :text :x 10 :y 10 :w 100 :h 50} (select-keys o [:kind :x :y :w :h])))
      (is (= {:id (:id o) :draft ""} (get-in fx [:db :editing])))))
  (testing "shapes take the picked shape"
    (is (= :star (:shape (first (objects-of (drag (assoc board :tool :shape :shape-kind :star) [0 0] [0 0]))))))
    (is (= :circle (get-in (ev/select-shape board :circle) [:db :shape-kind]))))
  (testing "the tool at the press counts, not a key pressed mid-drag"
    (let [db (:db (ev/pointer-down (assoc board :tool :sticky) (pe 0 0)))
          fx (ev/pointer-up (:db (ev/select-tool db :select)) (pe 0 0 :t 100))]
      (is (= [:sticky] (map :kind (objects-of fx))))))
  (testing "connection from a to b; released on nothing it cancels"
    (let [c (first (filter #(= :connection (:kind %))
                           (objects-of (drag (assoc with-objs :tool :connection) [50 50] [250 50]))))]
      (is (= ["a" "b"] ((juxt :from :to) c))))
    (is (= 2 (count (objects-of (drag (assoc with-objs :tool :connection) [50 50] [900 900])))))))

(deftest selecting-and-moving
  (testing "drag moves, sends at most every 33 ms, one history entry"
    (let [db (:db (ev/pointer-down with-objs (pe 10 10)))
          fx1 (ev/pointer-move db (pe 20 10 :t 10))
          fx2 (ev/pointer-move (:db fx1) (pe 30 10 :t 40))
          fx3 (ev/pointer-up (:db fx2) (pe 30 10 :t 50))]
      (is (= #{"a"} (:selection db)))
      (is (nil? (:tube/send fx1)) "throttled")
      (is (= [[:op/patch [{:id "a" :x 20 :y 0 :w 100 :h 100}]]] (:tube/send fx2)))
      (is (= [[:op/patch [{:id "a" :x 20 :y 0 :w 100 :h 100}]]] (:tube/send fx3)) "final position on pointer-up")
      (is (= 1 (count (get-in fx3 [:db :history :undo]))))))
  (testing "locked objects are selected but stay put"
    (let [fx (drag (assoc-in with-objs [:objects "a" :locked?] true) [10 10] [60 60])]
      (is (= #{"a"} (get-in fx [:db :selection])))
      (is (= 0 (get-in fx [:db :objects "a" :x])))
      (is (nil? (:tube/send fx)))))
  (testing "clicking a group member selects the group; shift toggles"
    (let [db (-> with-objs (assoc-in [:objects "a" :group] "g") (assoc-in [:objects "b" :group] "g"))]
      (is (= #{"a" "b"} (:selection (:db (ev/pointer-down db (pe 10 10))))))
      (is (= #{} (:selection (:db (ev/pointer-down (assoc db :selection #{"a" "b"}) (pe 10 10 :shift? true))))))))
  (testing "rubber band"
    (is (= #{"a"} (get-in (drag with-objs [-10 -10] [150 150]) [:db :selection])))
    (is (= #{} (get-in (drag (assoc with-objs :selection #{"a"}) [500 500] [500 500]) [:db :selection]))
        "click on empty clears"))
  (testing "corner handle resizes the single selected object"
    (let [fx (drag (assoc with-objs :selection #{"a"}) [100 100] [150 120])]
      (is (= {:x 0 :y 0 :w 150 :h 120} (select-keys (get-in fx [:db :objects "a"]) [:x :y :w :h])))
      (is (= 1 (count (get-in fx [:db :history :undo])))))))

(deftest grip-moving
  (testing "the toolbar's grip moves the selection from wherever it is grabbed, zoomed out too"
    (let [db (assoc with-objs :selection #{"a" "b"} :sel-menu {:menu :layer :for #{"a" "b"}}
                              :camera {:x 0 :y 0 :zoom 0.5})
          down (ev/grip-down db (pe 400 -40))
          fx1 (ev/pointer-move (:db down) (pe 420 -30 :t 40))
          fx2 (ev/pointer-up (:db fx1) (pe 420 -30 :t 50))]
      (is (= :move (get-in down [:db :drag :type])))
      (is (nil? (get-in down [:db :sel-menu])) "an open menu closes")
      (is (= [[40 20] [240 20]] (map (juxt :x :y) (map #(get-in fx2 [:db :objects %]) ["a" "b"]))))
      (is (= 2 (count (second (first (:tube/send fx1))))) "sent live")
      (is (= 1 (count (get-in fx2 [:db :history :undo]))))))
  (testing "nothing to move: locked or only connections; not the right button"
    (is (nil? (ev/grip-down (-> with-objs (assoc :selection #{"a"}) (assoc-in [:objects "a" :locked?] true)) (pe 0 0))))
    (is (nil? (ev/grip-down (assoc with-objs :selection #{"c"}
                                             :objects {"c" {:id "c" :kind :connection :from "a" :to "b"}})
                            (pe 0 0))))
    (is (nil? (ev/grip-down (assoc with-objs :selection #{"a"}) (pe 0 0 :button 2))))))

(deftest remote-changes-during-a-drag
  (let [db (:db (ev/pointer-down with-objs (pe 10 10)))
        db (:db (ev/remote-delete db ["a"]))
        fx (ev/pointer-move db (pe 50 10 :t 100))
        fx2 (ev/pointer-up (:db fx) (pe 50 10 :t 100))]
    (is (nil? (get-in fx [:db :objects "a"])) "a drag does not resurrect an object deleted by someone else")
    (is (nil? (:tube/send fx)))
    (is (nil? (:tube/send fx2))))
  (let [db (:db (ev/pointer-down (assoc with-objs :selection #{"a"}) (pe 100 100)))
        db (:db (ev/remote-delete db ["a"]))
        fx (ev/pointer-move db (pe 150 150 :t 100))]
    (is (nil? (get-in fx [:db :objects "a"])) "nor does a resize"))
  (is (= 7 (get-in (ev/remote-patch with-objs [{:id "a" :x 7}]) [:db :objects "a" :x]))))

(deftest stale-answers-are-ignored
  (is (= [:session/not-found "abc"] (get-in (ev/route-changed ev/initial-db "abc") [:http :on-failure])))
  (is (nil? (ev/session-not-found (assoc board :session "good") "bad" {})) "a late 404 for a session we left")
  (is (= :landing (get-in (ev/session-not-found (assoc board :route :checking :session "bad") "bad" {}) [:db :route])))
  (is (nil? (ev/import-failed (assoc board :session "other") "s1" {})) "a late import answer from another session")
  (is (some? (ev/import-failed board "s1" {})))
  (is (nil? (ev/session-created board {:id "new"})) "already on a board"))

(deftest hand-mode
  (let [hand (assoc with-objs :hand? true :selection #{"a"})
        band (fn [db mods]
               (let [db (:db (ev/pointer-down db (merge (pe 190 -10) mods)))
                     db (:db (ev/pointer-move db (pe 350 150 :t 100)))]
                 (ev/pointer-up db (pe 350 150 :t 100))))]
    (testing "dragging empty canvas pans and keeps the selection"
      (let [fx (drag hand [500 500] [550 520])]
        (is (= {:x -50 :y -20 :zoom 1} (get-in fx [:db :camera])))
        (is (= #{"a"} (get-in fx [:db :selection])))))
    (is (= #{} (get-in (drag hand [500 500] [501 501]) [:db :selection])) "a click on empty canvas clears")
    (let [fx (drag hand [210 10] [260 10])]
      (is (= [#{"b"} 250] [(get-in fx [:db :selection]) (get-in fx [:db :objects "b" :x])]) "objects still select and move"))
    (is (= #{"a" "b"} (get-in (band hand {:shift? true}) [:db :selection])) "shift-drag bands, adding")
    (is (= #{"b"} (get-in (band hand {:ctrl? true}) [:db :selection])) "ctrl-drag bands, replacing")
    (is (= 3 (count (objects-of (drag (assoc hand :tool :sticky) [500 500] [500 500])))) "creating tools still create"))
  (let [fx (ev/hand-toggle board)]
    (is (true? (get-in fx [:db :hand?])))
    (is (true? (:hand/store fx)) "remembered in this browser"))
  (is (false? (get-in (ev/hand-toggle (assoc board :hand? true)) [:db :hand?])))
  (is (true? (get-in (ev/route-changed (assoc board :hand? true) "") [:db :hand?])) "survives leaving a session"))

(deftest navigation
  (let [db (:db (ev/pointer-down board (pe 100 100 :button 1)))
        db (:db (ev/pointer-move db (pe 150 120)))]
    (is (= {:x -50 :y -20 :zoom 1} (:camera db)) "middle-drag pans"))
  (is (= :pan (get-in (ev/pointer-down (assoc board :space? true) (pe 0 0)) [:db :drag :type])) "space+drag pans")
  (is (nil? (ev/pointer-down board (pe 0 0 :button 2))) "right button ignored")
  (is (= {:x 10 :y 20 :zoom 1} (get-in (ev/camera-pan board 10 20) [:db :camera])) "wheel scrolls")
  (is (= 2 (get-in (ev/camera-zoom board 2 0 0) [:db :camera :zoom])))
  (let [cam (get-in (ev/zoom-step board 2) [:db :camera])]
    (is (= 2 (:zoom cam)) "+ and - zoom")
    (is (= [256 192] [(:x cam) (:y cam)]) "around the middle of the viewport")))

(deftest text-editing
  (let [fx (ev/dblclick with-objs (pe 10 10))
        db (:db (ev/edit-input (:db fx) "hello"))
        fx2 (ev/edit-commit db)]
    (is (= {:id "a" :draft "hi"} (get-in fx [:db :editing])))
    (is (= "hello" (get-in fx2 [:db :objects "a" :text])))
    (is (nil? (get-in fx2 [:db :editing])))
    (is (= [[:op/upsert [(assoc sticky :text "hello")]]] (:tube/send fx2)))
    (is (= "hi" (get-in (ev/edit-cancel db) [:db :objects "a" :text])) "Esc aborts"))
  (is (nil? (ev/dblclick (assoc-in with-objs [:objects "a" :locked?] true) (pe 10 10))) "locked: no edit")
  (is (nil? (ev/dblclick with-objs (pe 210 10))) "shapes have no text")
  (is (= {:id "a" :draft "hi"} (get-in (ev/edit-start (assoc with-objs :selection #{"a"})) [:db :editing])) "F2")
  (testing "clicking the canvas commits the edit"
    (let [fx (ev/pointer-down (assoc with-objs :editing {:id "a" :draft "new"}) (pe 900 900))]
      (is (= "new" (get-in fx [:db :objects "a" :text])))
      (is (= [[:op/upsert [(assoc sticky :text "new")]]] (:tube/send fx)))))
  (testing "an aborted new text object is removed"
    (is (empty? (objects-of (ev/edit-cancel (:db (drag (assoc board :tool :text) [0 0] [0 0]))))))))

(deftest selection-menus
  (let [db (assoc with-objs :selection #{"a"})
        opened (:db (ev/menu-toggle db :fill))
        open (fn [db] (ev/open-menu (:sel-menu db) (:selection db)))]
    (is (= :fill (open opened)))
    (is (nil? (open (:db (ev/menu-toggle opened :fill)))) "the same button closes it")
    (is (= :layer (open (:db (ev/menu-toggle opened :layer)))) "the other button switches")
    (is (nil? (open (assoc opened :selection #{"b"}))) "another selection: closed")
    (is (= :fill (open (:db (ev/selection-fill opened "#e57373")))) "stays open for more picks")
    (is (nil? (open (:db (ev/pointer-down opened (pe 500 500))))) "pressing the board closes it")))

(deftest selection-actions
  (let [db (assoc with-objs :selection #{"a" "b"})]
    (let [os (objects-of (ev/selection-group db))]
      (is (= 1 (count (set (map :group os)))))
      (is (every? :group os)))
    (let [fx (ev/selection-delete db)]
      (is (empty? (get-in fx [:db :objects])))
      (is (= #{} (get-in fx [:db :selection])))
      (is (= #{"a" "b"} (set (second (first (:tube/send fx)))))))
    (is (every? :locked? (objects-of (ev/selection-lock db true))))
    (is (= "#e57373" (get-in (ev/selection-fill db "#e57373") [:db :objects "b" :fill])))
    (is (= "#64b5f6" (get-in (ev/selection-text-color db "#64b5f6") [:db :objects "a" :color])))
    (let [fx (ev/selection-layer (assoc db :selection #{"a"}) :front)]
      (is (= ["b" "a"] (mapv :id (geom/draw-order (get-in fx [:db :objects])))))
      (is (= [[:op/upsert [(assoc sticky :z 3)]]] (:tube/send fx)))
      (is (= 1 (count (get-in fx [:db :history :undo])))))
    (is (= {:selection #{} :tool :select} (select-keys (:db (ev/selection-clear (assoc db :tool :text))) [:selection :tool])))))

(deftest undo-redo-events
  (let [fx (ev/selection-delete (assoc with-objs :selection #{"a"}))
        fx2 (ev/history-undo (:db fx))
        fx3 (ev/history-redo (:db fx2))]
    (is (= sticky (get-in fx2 [:db :objects "a"])))
    (is (= [[:op/upsert [sticky]]] (:tube/send fx2)))
    (is (= [[:op/delete ["a"]]] (:tube/send fx3)))
    (is (= #{} (get-in fx3 [:db :selection])))))

(deftest remote-events
  (let [db (assoc with-objs :selection #{"a" "b"} :history {:undo [{:before {} :after {}}] :redo []})
        db2 (:db (ev/snapshot db {"b" shape}))]
    (is (= {"b" shape} (:objects db2)))
    (is (= #{"b"} (:selection db2)))
    (is (= {:undo [] :redo []} (:history db2)))
    (is (false? (:loading? (:db (ev/snapshot (assoc db :loading? true) {})))) "the snapshot ends loading")
    (is (= #{"b"} (:selection (:db (ev/remote-delete db ["a"])))))
    (is (= 5 (get-in (ev/remote-upsert db [(assoc sticky :x 5)]) [:db :objects "a" :x])))
    (is (nil? (get-in (ev/remote-delete (assoc db :editing {:id "a" :draft "x"}) ["a"]) [:db :editing]))
        "an edit of a remotely deleted object is dropped")))

(deftest pasting
  (let [o (first (objects-of (ev/paste-text (assoc board :viewport [800 600]) "hello")))]
    (is (= {:kind :text :text "hello" :x 250 :y 250 :w 300 :h 100} (select-keys o [:kind :text :x :y :w :h]))))
  (let [o (first (objects-of (ev/paste-image (assoc board :viewport [800 600]) "data:image/png;base64,AA" 1200 300)))]
    (is (= [600 150] [(:w o) (:h o)]) "scaled to fit 600×600"))
  (is (= "Image too large (max 15 MB)"
         (get-in (ev/paste-image board (apply str (repeat 15000001 "a")) 10 10) [:db :message])))
  (is (nil? (ev/paste-text ev/initial-db "x")) "ignored on the landing screen")
  (is (nil? (ev/paste-text (assoc board :loading? true) "x")) "ignored until the snapshot, which would drop it")
  (is (nil? (ev/paste-image (assoc board :loading? true) "data:image/png;base64,AA" 10 10))))

(def line {:id "c" :kind :connection :from "a" :to "b" :z 3})
(def picture {:id "d" :kind :image :x 0 :y 300 :w 60 :h 40 :z 4 :src "data:image/png;base64,AA"})
(def copyable (assoc board :viewport [800 600] :objects {"a" sticky "b" shape "c" line "d" picture}))

(defn- round-trip
  "The objects a copy of sel in db yields when read back from the clipboard."
  [db sel]
  (ev/read-copied (:data (ev/copy-payload (assoc db :selection sel)))))

(deftest copying
  (is (nil? (ev/copy-payload copyable)) "nothing selected: the browser copies")
  (is (= "hi" (:text (ev/copy-payload (assoc copyable :selection #{"a" "d"})))) "plain text for other apps")
  (is (= #{"a" "b" "c"} (set (map :id (round-trip copyable #{"a" "b"})))) "the connection between them comes along")
  (is (= #{"a"} (set (map :id (round-trip copyable #{"a" "c"})))) "a connection with an end left behind does not")
  (is (= [picture] (round-trip copyable #{"d"})) "an image, with its source")
  (is (nil? (ev/read-copied "{:dingsbums/objects [{:id \"x\" :kind :evil}]}")) "invalid objects are dropped")
  (is (nil? (ev/read-copied "(not a map")) "garbage")
  (is (nil? (ev/read-copied nil))))

(deftest copying-images
  (is (= (:src picture) (:image (ev/copy-payload (assoc copyable :selection #{"d"})))) "a single image goes out as a picture too")
  (is (nil? (:image (ev/copy-payload (assoc copyable :selection #{"a" "d"})))) "not mixed with other objects")
  (let [{:keys [data]} (ev/copy-payload (assoc copyable :selection #{"d"}))
        html (ev/copy-html data (:src picture))]
    (is (= [picture] (ev/read-copied (ev/html-copied html))) "the copy rides along in the HTML")
    (is (re-find #"<img src=\"data:image/png" html) "apps that take HTML see the picture"))
  (is (nil? (ev/html-copied "<p>hello <img src=\"x.png\"></p>")) "HTML from elsewhere")
  (is (nil? (ev/html-copied nil))))

(deftest pasting-objects
  (let [copied (round-trip copyable #{"a" "b"})
        fx (ev/paste-objects copyable copied)
        objects (get-in fx [:db :objects])
        new-ids (get-in fx [:db :selection])
        news (map objects new-ids)
        by-kind (into {} (map (juxt :kind identity)) news)]
    (is (= 7 (count objects)) "originals stay, three copies")
    (is (= 3 (count new-ids)) "the copies are selected")
    (is (not-any? #{"a" "b" "c"} new-ids) "with fresh ids")
    (is (= [(:id (:sticky by-kind)) (:id (:shape by-kind))] ((juxt :from :to) (:connection by-kind)))
        "the connection joins the copies")
    (is (= [20 20] ((juxt :x :y) (:sticky by-kind))) "offset from visible originals")
    (is (= [220 20] ((juxt :x :y) (:shape by-kind))) "keeping the layout")
    (is (= [5 6 7] (sort (map :z news))) "on top, in their order")
    (is (= 3 (count (:tube/send fx))) "one upsert each")
    (is (nil? (ev/paste-objects (:db fx) [])) "nothing to paste")
    (is (= (:objects copyable) (get-in (ev/history-undo (:db fx)) [:db :objects])) "one undo removes them")
    (let [fx2 (ev/paste-objects (:db fx) copied)
          sticky2 (some #(when (= :sticky (:kind %)) %) (map (get-in fx2 [:db :objects]) (get-in fx2 [:db :selection])))]
      (is (= [40 40] ((juxt :x :y) sticky2)) "pasting again cascades")))
  (let [fx (ev/paste-objects (assoc copyable :camera {:x 5000 :y 5000 :zoom 1}) [sticky])
        o (get-in fx [:db :objects (first (get-in fx [:db :selection]))])]
    (is (= [5350 5250] ((juxt :x :y) o)) "originals out of view: the middle of the view"))
  (let [grouped (map #(assoc % :group "g" :locked? true) [sticky shape])
        news (vals (apply dissoc (get-in (ev/paste-objects copyable grouped) [:db :objects]) (keys (:objects copyable))))]
    (is (apply = (map :group news)) "copies form a group")
    (is (not= "g" (:group (first news))) "of their own")
    (is (not-any? :locked? news) "unlocked"))
  (is (= "Image too large (max 15 MB)"
         (get-in (ev/paste-objects copyable [(assoc picture :src (apply str (repeat 15000001 "a")))]) [:db :message])))
  (is (nil? (ev/paste-objects copyable [(assoc sticky :x ##Inf)])) "geometry must be finite")
  (let [fx (ev/paste-objects copyable [sticky shape (assoc line :group "h")])]
    (is (every? #(or (not (contains? % :group)) (string? (:group %))) (vals (get-in fx [:db :objects])))
        "a grouped connection keeps a valid group"))
  (is (nil? (ev/paste-objects ev/initial-db [sticky])) "ignored on the landing screen"))

;; tube wiring, through hammer with a fake WebSocket

(def sockets (atom []))

(defn- fake-ws [url]
  (let [ws #js {:url url :sent #js [] :readyState 0}]
    (set! (.-send ws) (fn [s] (.push (.-sent ws) s)))
    (set! (.-close ws) (fn [] (set! (.-readyState ws) 3)))
    (swap! sockets conj ws)
    ws))

(defn- push! [^js ws ev] ((.-onmessage ws) #js {:data (pr-str ev)}))

(use-fixtures :each {:before #(do (t/reset-app!) (reset! sockets []) (tubes/set-websocket! fake-ws))
                     :after #(do (t/reset-app!) (tubes/set-websocket! nil))})

(deftest tube-sync
  (mount! [:div] (js/document.createElement "div") (assoc ev/initial-db :route :checking :session "s1"))
  (dispatch-sync [:session/exists "s1" nil])
  (let [^js ws (first @sockets)
        sent #(vec (.-sent ws))]
    (is (= "ws://localhost/ws?session=s1" (.-url ws)))
    (set! (.-readyState ws) 1)
    ((.-onopen ws) #js {})
    (t/flush!)
    (is (= ["[:session/hello]"] (sent)) "hello goes out after any queued ops")
    (is (:online? @state/app-db))
    (push! ws [:session/snapshot {"a" sticky}])
    (push! ws [:evil/event 1])
    (t/flush!)
    (is (= {"a" sticky} (:objects @state/app-db)) "snapshot applied; the unknown event was dropped")
    (dispatch-sync [:pointer/down (pe 10 10)])
    (dispatch-sync [:pointer/up (pe 10 10)])
    (dispatch-sync [:selection/delete])
    (is (= "[:op/delete [\"a\"]]" (last (sent))))
    (dispatch-sync [:history/undo])
    (is (= (pr-str [:op/upsert [sticky]]) (last (sent))))
    (t/check-errors!)))
