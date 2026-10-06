(ns dingsbums.events-test
  (:require [dingsbums.test-env]
            [cljs.test :refer [deftest is testing use-fixtures]]
            [hammer.core :refer [mount! dispatch-sync]]
            [hammer.state :as state]
            [hammer.testing :as t]
            [hammer.tubes :as tubes]
            [dingsbums.events :as ev]))

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
  (is (nil? (ev/key-action {:key "c" :ctrl? true})) "browser copy untouched")
  (is (nil? (ev/key-action {:key "a"}))))

(deftest routing
  (let [fx (ev/route-changed with-objs "")]
    (is (= :landing (get-in fx [:db :route])))
    (is (= {} (get-in fx [:db :objects])))
    (is (contains? fx :hammer.tubes/destroy)))
  (let [fx (ev/route-changed ev/initial-db " abc ")]
    (is (= [:checking "abc"] ((juxt :route :session) (:db fx))))
    (is (= "/api/sessions/abc" (get-in fx [:http :uri])))
    (is (= [:session/exists "abc"] (get-in fx [:http :on-success]))))
  (is (nil? (ev/route-changed board "s1")) "already on that board")
  (is (nil? (ev/session-exists (assoc board :session "other") "s1")) "stale answer for a session we left")
  (is (= {:url "ws://localhost/ws" :params {:session "s1"}}
         (select-keys (:hammer.tubes/create (ev/session-exists (assoc board :route :checking) "s1")) [:url :params])))
  (let [fx (ev/session-not-found (assoc board :route :checking))]
    (is (= [:landing "Session not found"] ((juxt :route :error) (:db fx))))
    (is (= "" (:set-hash fx))))
  (let [fx (ev/session-missing with-objs)]
    (is (= [:landing "Session expired or not found" {}] ((juxt :route :error :objects) (:db fx))))
    (is (contains? fx :hammer.tubes/destroy)))
  (is (= "x" (:set-hash (ev/landing-join (assoc ev/initial-db :join-input " x ")))))
  (is (nil? (ev/landing-join (assoc ev/initial-db :join-input "  "))))
  (is (= "new" (:set-hash (ev/session-created ev/initial-db {:id "new"})))))

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
      (is (= [[:op/upsert [(assoc sticky :x 20)]]] (:tube/send fx2)))
      (is (= [[:op/upsert [(assoc sticky :x 20)]]] (:tube/send fx3)) "final position on pointer-up")
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

(deftest navigation
  (let [db (:db (ev/pointer-down board (pe 100 100 :button 1)))
        db (:db (ev/pointer-move db (pe 150 120)))]
    (is (= {:x -50 :y -20 :zoom 1} (:camera db)) "middle-drag pans"))
  (is (= :pan (get-in (ev/pointer-down (assoc board :space? true) (pe 0 0)) [:db :drag :type])) "space+drag pans")
  (is (nil? (ev/pointer-down board (pe 0 0 :button 2))) "right button ignored")
  (is (= {:x 10 :y 20 :zoom 1} (get-in (ev/camera-pan board 10 20) [:db :camera])) "wheel scrolls")
  (is (= 2 (get-in (ev/camera-zoom board 2 0 0) [:db :camera :zoom]))))

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
  (is (nil? (ev/paste-text ev/initial-db "x")) "ignored on the landing screen"))

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
