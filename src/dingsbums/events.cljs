(ns dingsbums.events
  "Event handlers. Each is a plain fn (db & args) → effect map (nil: nothing),
  registered at the bottom, so tests call them directly. Local mutations go
  through model/commit and send their ops with :tube/send."
  (:require [clojure.string :as str]
            [hammer.core :refer [reg-event reg-fx dispatch]]
            [hammer.http]
            [hammer.tubes :as tubes]
            [dingsbums.geom :as geom]
            [dingsbums.model :as model]
            [dingsbums.ops :as ops]
            [dingsbums.themes :as themes]))

(def initial-db
  {:route :landing :session nil :join-input "" :error nil :message nil :online? false
   :objects {} :history {:undo [] :redo []} :selection #{} :tool :select :shape-kind :rect
   :camera {:x 0 :y 0 :zoom 1} :drag nil :editing nil :space? false :img-tick 0
   :viewport [1024 768] :theme nil :os-dark? false})

(def ^:private board-reset
  (select-keys initial-db [:objects :history :selection :tool :camera :drag :editing :message :online?]))

(def send-interval 33)
(def hit-px 6)
(def max-image-chars 15000000)
(def remote-events #{:op/upsert :op/patch :op/delete :session/snapshot :session/missing})
(def creating-tools #{:text :frame :shape :sticky})

(reg-fx :tube/send (fn [evs] (run! tubes/send! evs)))
(reg-fx :set-hash (fn [h] (set! (.-hash js/location) h)))
(reg-fx :clipboard/write (fn [s] (some-> js/navigator .-clipboard (.writeText s) (.catch (fn [_] nil)))))

(def theme-storage-key "dingsbums-theme")

(reg-fx :theme/apply
        (fn [[pref theme]]
          (try (if pref
                 (js/localStorage.setItem theme-storage-key (name pref))
                 (js/localStorage.removeItem theme-storage-key))
               (catch :default _ nil))
          (let [style (.. js/document -documentElement -style)]
            (doseq [k themes/css-keys] (.setProperty style (str "--" (name k)) (get theme k))))))

(defn- with-ops [db ops] (cond-> {:db db} (seq ops) (assoc :tube/send ops)))

(defn- commit [db changes] (let [[db ops] (model/commit db changes)] (with-ops db ops)))

(defn- prune
  "db with selection and edit limited to objects that still exist."
  [db]
  (let [objects (:objects db)]
    (-> db
        (update :selection #(into #{} (filter (partial contains? objects)) %))
        (update :editing #(when (and % (contains? objects (:id %))) %)))))

(defn ws-url []
  (str (if (= "https:" (.-protocol js/location)) "wss://" "ws://") (.-host js/location) "/ws"))

(defn receive
  "Tube :on-receive: dispatch only the events the server may send."
  [ev]
  (when (contains? remote-events (first ev)) (dispatch ev)))

;; routing and session

(defn route-changed [db sid]
  (let [sid (str/trim (or sid ""))]
    (cond
      (str/blank? sid)
      {:db (merge db board-reset {:route :landing :session nil}) :hammer.tubes/destroy {}}

      (and (= sid (:session db)) (= :board (:route db)))
      nil

      :else
      {:db (merge db board-reset {:route :checking :session sid :error nil})
       :hammer.tubes/destroy {}
       :http {:uri (str "/api/sessions/" (js/encodeURIComponent sid)) :response-format :text
              :on-success [:session/exists sid] :on-failure [:session/not-found sid]}})))

(defn session-exists [db sid & _]
  (when (= sid (:session db))
    {:db (assoc db :route :board)
     :hammer.tubes/create {:url (ws-url) :params {:session sid} :on-receive receive
                           :on-connect [:tube/connected] :on-disconnect [:tube/disconnected]}}))

(defn session-not-found [db sid & _]
  (when (= sid (:session db))
    {:db (assoc db :route :landing :session nil :error "Session not found") :set-hash ""}))

(defn session-missing [db]
  {:db (merge db board-reset {:route :landing :session nil :error "Session expired or not found"})
   :hammer.tubes/destroy {}
   :set-hash ""})

(defn landing-input [db s] {:db (assoc db :join-input s)})

(defn landing-create [_]
  {:http {:method :post :uri "/api/sessions" :on-success [:session/created] :on-failure [:landing/failed]}})

(defn session-created [db {:keys [id]}]
  (when-not (= :board (:route db)) {:db (assoc db :error nil) :set-hash id}))

(defn landing-failed [db & _] {:db (assoc db :error "Could not create a session")})

(defn landing-join [db]
  (let [sid (str/trim (:join-input db))]
    (when-not (str/blank? sid) {:db (assoc db :error nil) :set-hash sid})))

(defn theme-select
  "pref: a theme name, or nil to follow the OS. Saved in this browser."
  [db pref]
  (let [pref (when (contains? themes/themes pref) pref)]
    {:db (assoc db :theme pref) :theme/apply [pref (themes/effective pref (:os-dark? db))]}))

(defn theme-os [db dark?] (theme-select (assoc db :os-dark? dark?) (:theme db)))

(defn copy-id [db] {:db (assoc db :message "Copied") :clipboard/write (:session db)})

;; sync

(defn tube-connected [db] {:db (assoc db :online? true) :tube/send [[:session/hello]]})
(defn tube-disconnected [db] {:db (assoc db :online? false)})

(defn snapshot [db objects]
  {:db (prune (assoc db :objects objects :history {:undo [] :redo []} :drag nil))})

(defn remote-upsert [db objs] {:db (update db :objects ops/upsert objs)})
(defn remote-patch [db patches] {:db (update db :objects ops/patch patches)})
(defn remote-delete [db ids] {:db (prune (update db :objects ops/delete ids))})

;; tools, camera, misc

(defn select-tool [db tool] {:db (assoc db :tool tool)})
(defn select-shape [db shape] {:db (assoc db :tool :shape :shape-kind shape)})
(defn wheel-action
  "The event for a wheel turn: ctrl zooms at the pointer, shift scrolls sideways
  (some browsers already swap the axes for shift, so both deltas count)."
  [{:keys [dx dy ctrl? shift? sx sy]}]
  (cond
    ctrl? [:camera/zoom (js/Math.exp (* -0.01 dy)) sx sy]
    shift? [:camera/pan (+ dx dy) 0]
    :else [:camera/pan dx dy]))

(defn camera-pan [db dx dy] {:db (update db :camera geom/pan (- dx) (- dy))})
(defn camera-zoom [db factor sx sy] {:db (update db :camera geom/zoom-at factor [sx sy])})
(defn set-viewport [db w h] {:db (assoc db :viewport [w h])})
(defn set-space [db down?] {:db (assoc db :space? down?)})
(defn image-loaded [db] {:db (update db :img-tick inc)})

;; pointer

(defn- world [db {:keys [sx sy]}] (geom/screen->world (:camera db) [sx sy]))
(defn- tol [db px] (/ px (get-in db [:camera :zoom])))
(defn- hit-at [db p] (geom/hit (:objects db) p (tol db hit-px)))

(defn- finish-edit
  "[db ops] with an active text edit committed."
  [db]
  (if-let [{:keys [id draft]} (:editing db)]
    (model/commit (assoc db :editing nil) (model/text-changes (:objects db) id draft))
    [db []]))

(defn- resize-target
  "[obj corner] when p is on a handle of the single selected, unlocked, non-connection object."
  [db p]
  (let [sel (:selection db)
        o (when (= 1 (count sel)) (get-in db [:objects (first sel)]))]
    (when (and o (not (:locked? o)) (not= :connection (:kind o)))
      (when-let [k (geom/handle-at o p (tol db geom/handle-px))] [o k]))))

(defn- press [db {:keys [sx sy button shift? t]} p]
  (let [tool (:tool db) hit (hit-at db p) sel (:selection db)]
    (cond
      (or (= button 1) (:space? db)) (assoc db :drag {:type :pan :last [sx sy]})
      (creating-tools tool) (assoc db :drag {:type :create :start p :current p})
      (= tool :connection) (cond-> db
                             (and hit (not= :connection (:kind hit)))
                             (assoc :drag {:type :connect :from (:id hit) :current p}))
      :else
      (if-let [[o k] (resize-target db p)]
        (assoc db :drag {:type :resize :handle k :start {(:id o) o} :last-sent t})
        (if hit
          (let [ids (model/expand-groups (:objects db) [(:id hit)])]
            (if shift?
              (assoc db :selection (if (every? sel ids) (reduce disj sel ids) (into sel ids)))
              (let [sel (if (contains? sel (:id hit)) sel ids)]
                (assoc db :selection sel
                          :drag {:type :move :origin p :start (model/movable (:objects db) sel) :last-sent t}))))
          (assoc db :selection (if shift? sel #{})
                    :drag {:type :band :start p :current p :shift? shift?}))))))

(defn pointer-down [db {:keys [button] :as e}]
  (when (<= button 1)
    (let [[db ops] (finish-edit (assoc db :message nil))]
      (with-ops (press db e (world db e)) ops))))

(defn- live-update
  "Applies a gesture's changes locally; sends their geometry when the last send is
  send-interval ago."
  [db changes t]
  (let [db (update db :objects model/apply-changes changes)]
    (if (>= (- t (get-in db [:drag :last-sent])) send-interval)
      (with-ops (assoc-in db [:drag :last-sent] t) (model/patch-ops (vals changes)))
      {:db db})))

(defn pointer-move [db {:keys [sx sy t] :as e}]
  (let [drag (:drag db) p (world db e)]
    (case (:type drag)
      :pan (let [[lx ly] (:last drag)]
             {:db (-> db (update :camera geom/pan (- sx lx) (- sy ly)) (assoc-in [:drag :last] [sx sy]))})
      (:create :band :connect) {:db (assoc-in db [:drag :current] p)}
      :move (let [[ox oy] (:origin drag) [px py] p]
              (live-update db (model/moved (:objects db) (:start drag) (- px ox) (- py oy)) t))
      :resize (let [[id o] (first (:start drag))
                    cur (get-in db [:objects id])]
                (live-update db (if (and cur (not (:locked? cur)))
                                  {id (merge cur (select-keys (geom/resize o (:handle drag) p) [:x :y :w :h]))}
                                  {})
                             t))
      nil)))

(defn- create-object [db start end]
  (let [kind (:tool db)
        r (geom/rect-from-points start end)
        [dw dh] (model/default-size kind)
        rect (if (and (< (:w r) 5) (< (:h r) 5))
               {:x (- (first start) (/ dw 2)) :y (- (second start) (/ dh 2)) :w dw :h dh}
               (-> r (update :w max geom/min-size) (update :h max geom/min-size)))
        o (model/make (:objects db) kind rect (when (= kind :shape) {:shape (:shape-kind db)}))]
    (commit (assoc db :tool :select :selection #{(:id o)}
                      :editing (when (= kind :text) {:id (:id o) :draft ""}))
            {(:id o) o})))

(defn- connect [db from p]
  (let [hit (hit-at db p)]
    (if (and hit (not= :connection (:kind hit)) (not= (:id hit) from))
      (let [o (model/make (:objects db) :connection nil {:from from :to (:id hit)})]
        (commit (assoc db :tool :select :selection #{(:id o)}) {(:id o) o}))
      {:db db})))

(defn- band-select [db {:keys [start shift?]} p]
  (let [band (geom/rect-from-points start p)
        ids (when (or (> (:w band) 2) (> (:h band) 2)) (geom/ids-in-rect (:objects db) band))]
    {:db (assoc db :selection (into (if shift? (:selection db) #{}) (model/expand-groups (:objects db) ids)))}))

(defn pointer-up [db e]
  (when-let [drag (:drag db)]
    (let [db (assoc db :drag nil) p (world db e)]
      (case (:type drag)
        :create (create-object db (:start drag) p)
        :connect (connect db (:from drag) p)
        :band (band-select db drag p)
        (:move :resize) (let [[db ops] (model/finish-gesture db (:start drag))] (with-ops db ops))
        {:db db}))))

(defn dblclick [db e]
  (let [hit (hit-at db (world db e))]
    (when (and (= :select (:tool db)) hit (model/text-kinds (:kind hit)) (not (:locked? hit)))
      {:db (assoc db :selection #{(:id hit)} :editing {:id (:id hit) :draft (:text hit "")})})))

;; text editing

(defn edit-start [db]
  (let [sel (:selection db)
        o (when (= 1 (count sel)) (get-in db [:objects (first sel)]))]
    (when (and o (model/text-kinds (:kind o)) (not (:locked? o)))
      {:db (assoc db :editing {:id (:id o) :draft (:text o "")})})))

(defn edit-input [db s] (when (:editing db) {:db (assoc-in db [:editing :draft] s)}))

(defn edit-commit [db] (when (:editing db) (let [[db ops] (finish-edit db)] (with-ops db ops))))

(defn edit-cancel [db]
  (when-let [{:keys [id]} (:editing db)]
    (let [db (assoc db :editing nil)
          o (get-in db [:objects id])]
      (if (and (= :text (:kind o)) (str/blank? (:text o)))
        (commit db {id nil})
        {:db db}))))

;; selection

(defn- change-selection [db f & args]
  (update (commit db (apply f (:objects db) (:selection db) args)) :db prune))

(defn selection-fill [db color] (change-selection db model/fill-changes color))
(defn selection-lock [db locked?] (change-selection db model/lock-changes locked?))
(defn selection-group [db] (change-selection db model/group-changes))
(defn selection-ungroup [db] (change-selection db model/ungroup-changes))
(defn selection-delete [db] (change-selection db model/delete-changes))
(defn selection-clear [db] {:db (assoc db :selection #{} :tool :select)})

;; history

(defn- history [f db] (let [[db ops] (f (assoc db :drag nil))] (with-ops (prune db) ops)))
(defn history-undo [db] (history model/undo db))
(defn history-redo [db] (history model/redo db))

;; paste

(defn- place [db kind w h extra]
  (let [[vw vh] (:viewport db)
        [cx cy] (geom/screen->world (:camera db) [(/ vw 2) (/ vh 2)])
        o (model/make (:objects db) kind {:x (- cx (/ w 2)) :y (- cy (/ h 2)) :w w :h h} extra)]
    (commit (assoc db :tool :select :selection #{(:id o)}) {(:id o) o})))

(defn paste-text [db text]
  (when (= :board (:route db)) (place db :text 300 100 {:text text})))

(defn paste-image [db src w h]
  (when (= :board (:route db))
    (if (> (count src) max-image-chars)
      {:db (assoc db :message "Image too large (max 15 MB)")}
      (let [w (max 1 w) h (max 1 h) s (min 1 (/ 600 w) (/ 600 h))]
        (place db :image (* w s) (* h s) {:src src})))))

;; import

(defn import-file [db file]
  (let [sid (:session db)]
    {:db (assoc db :message "Importing…")
     :http {:method :post :uri (str "/api/sessions/" (js/encodeURIComponent sid) "/import")
            :body file :headers {"Content-Type" "application/x-tar"} :response-format :text
            :on-success [:import/done sid] :on-failure [:import/failed sid]}}))

(defn import-done [db sid & _] (when (= sid (:session db)) {:db (assoc db :message nil)}))
(defn import-failed [db sid & _]
  (when (= sid (:session db)) {:db (assoc db :message "Import failed: not a dingsbums export")}))

;; keys

(defn key-action
  "The event for a key press outside text inputs, or nil to leave it to the browser."
  [{:keys [key ctrl? shift?]}]
  (let [k (str/lower-case key)]
    (cond
      (and ctrl? (= k "z")) (if shift? [:history/redo] [:history/undo])
      (and ctrl? (= k "y")) [:history/redo]
      (and ctrl? (= k "g")) (if shift? [:selection/ungroup] [:selection/group])
      ctrl? nil
      (= key "F2") [:edit/start]
      (#{"Delete" "Backspace"} key) [:selection/delete]
      (= key "Escape") [:selection/clear]
      (= key " ") [:space true])))

(doseq [[id f] {:route/changed route-changed
                :session/exists session-exists
                :session/not-found session-not-found
                :session/missing session-missing
                :session/created session-created
                :session/copy-id copy-id
                :theme/select theme-select
                :theme/os theme-os
                :session/snapshot snapshot
                :op/upsert remote-upsert
                :op/patch remote-patch
                :op/delete remote-delete
                :tube/connected tube-connected
                :tube/disconnected tube-disconnected
                :landing/input landing-input
                :landing/create landing-create
                :landing/join landing-join
                :landing/failed landing-failed
                :tool/select select-tool
                :tool/shape select-shape
                :camera/pan camera-pan
                :camera/zoom camera-zoom
                :viewport set-viewport
                :space set-space
                :image/loaded image-loaded
                :pointer/down pointer-down
                :pointer/move pointer-move
                :pointer/up pointer-up
                :pointer/dblclick dblclick
                :edit/start edit-start
                :edit/input edit-input
                :edit/commit edit-commit
                :edit/cancel edit-cancel
                :selection/fill selection-fill
                :selection/lock selection-lock
                :selection/group selection-group
                :selection/ungroup selection-ungroup
                :selection/delete selection-delete
                :selection/clear selection-clear
                :history/undo history-undo
                :history/redo history-redo
                :paste/text paste-text
                :paste/image paste-image
                :import/file import-file
                :import/done import-done
                :import/failed import-failed}]
  (reg-event id f))
