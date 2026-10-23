(ns dingsbums.events
  "Event handlers. Each is a plain fn (db & args) → effect map (nil: nothing),
  registered at the bottom, so tests call them directly. Local mutations go
  through model/commit and send their ops with :tube/send."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [hammer.core :refer [reg-event reg-fx dispatch]]
            [hammer.http]
            [hammer.tubes :as tubes]
            [dingsbums.geom :as geom]
            [dingsbums.log :as log]
            [dingsbums.model :as model]
            [dingsbums.ops :as ops]
            [dingsbums.themes :as themes]))

(def initial-db
  {:route :landing :session nil :join-input "" :error nil :message nil :online? false
   :objects {} :history {:undo [] :redo []} :selection #{} :tool :select :shape-kind :rect
   :camera {:x 0 :y 0 :zoom 1} :drag nil :editing nil :space? false :img-tick 0
   :viewport [1024 768] :theme nil :os-dark? false :hand? false :loading? false :sel-menu nil
   :name nil :naming nil :you nil :peers {} :cursor-sent ##-Inf :cursor-pending nil
   :log {}})

(def ^:private board-reset
  (select-keys initial-db [:objects :history :selection :tool :camera :drag :editing :message :online? :loading?
                           :naming :you :peers :log]))

(def send-interval 33)
(def hit-px 6)
(def max-image-chars 15000000)
(def remote-events #{:op/upsert :op/patch :op/delete :session/snapshot :session/missing
                     :presence/roster :presence/update :presence/left :log/all :log/add})
(def creating-tools #{:text :frame :shape :sticky :dot :counter :scale-x :scale-xy})

(reg-fx :tube/send (fn [evs] (run! tubes/send! evs)))
(reg-fx :set-hash (fn [h] (set! (.-hash js/location) h)))
;; the time on the clock pointer events' timeStamp uses
(reg-fx :cursor/flush-later (fn [ms] (js/setTimeout #(dispatch [:cursor/flush (js/performance.now)]) ms)))
(reg-fx :clipboard/write (fn [s] (some-> js/navigator .-clipboard (.writeText s) (.catch (fn [_] nil)))))

(def theme-storage-key "dingsbums-theme")
(def hand-storage-key "dingsbums-hand")
(def name-storage-key "dingsbums-name")

(reg-fx :name/store (fn [n] (try (js/localStorage.setItem name-storage-key n) (catch :default _ nil))))

(reg-fx :hand/store
        (fn [on?]
          (try (if on?
                 (js/localStorage.setItem hand-storage-key "1")
                 (js/localStorage.removeItem hand-storage-key))
               (catch :default _ nil))))

(reg-fx :theme/apply
        (fn [[pref theme]]
          (try (if pref
                 (js/localStorage.setItem theme-storage-key (name pref))
                 (js/localStorage.removeItem theme-storage-key))
               (catch :default _ nil))
          (let [style (.. js/document -documentElement -style)]
            (doseq [k themes/css-keys] (.setProperty style (str "--" (name k)) (get theme k))))))

(defn- with-ops [db ops] (cond-> {:db db} (seq ops) (assoc :tube/send ops)))

(defn- presence-send
  "fx also telling the others presence update p, when online."
  [fx p]
  (cond-> fx (get-in fx [:db :online?]) (update :tube/send (fnil conj []) [:presence/update p])))

(defn- commit [db changes] (let [[db ops] (model/commit db changes)] (with-ops db ops)))

(defn- prune
  "db with selection and edit limited to objects that still exist."
  [db]
  (let [objects (:objects db)]
    (-> db
        (update :selection #(into #{} (filter (partial contains? objects)) %))
        (update :editing #(when (and % (contains? objects (:id %))) %)))))

(defn ws-url
  "The op socket's URL: ws beside the page (href, default the current one),
  so it keeps a reverse proxy's path prefix."
  ([] (ws-url (.-href js/location)))
  ([href]
   (let [u (js/URL. "ws" href)]
     (set! (.-protocol u) (if (= "https:" (.-protocol u)) "wss:" "ws:"))
     (.toString u))))

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
      {:db (merge db board-reset {:route :checking :session sid :error nil :loading? true})
       :hammer.tubes/destroy {}
       :http {:uri (str "api/sessions/" (js/encodeURIComponent sid)) :response-format :text
              :on-success [:session/exists sid] :on-failure [:session/not-found sid]}})))

(defn session-exists [db sid & _]
  (when (= sid (:session db))
    {:db (assoc db :route :board)
     :hammer.tubes/create {:url (ws-url) :params {:session sid} :on-receive receive
                           :on-connect [:tube/connected] :on-disconnect [:tube/disconnected]}}))

(defn session-not-found [db sid & _]
  (when (= sid (:session db))
    {:db (assoc db :route :landing :session nil :error "Session not found" :loading? false) :set-hash ""}))

(defn session-missing [db]
  {:db (merge db board-reset {:route :landing :session nil :error "Session expired or not found"})
   :hammer.tubes/destroy {}
   :set-hash ""})

(defn landing-input [db s] {:db (assoc db :join-input s)})

(defn landing-create [_]
  {:http {:method :post :uri "api/sessions" :on-success [:session/created] :on-failure [:landing/failed]}})

(defn session-created [db {:keys [id]}]
  (when-not (= :board (:route db)) {:db (assoc db :error nil) :set-hash id}))

(defn landing-failed [db & _] {:db (assoc db :error "Could not create a session")})

(defn landing-join [db]
  (let [sid (str/trim (:join-input db))]
    (when-not (str/blank? sid) {:db (assoc db :error nil) :set-hash sid})))

(defn theme-select
  "pref: a theme name, :os to follow the OS, or nil for the default. Saved in
  this browser."
  [db pref]
  (let [pref (when (and (themes/pref? pref) (not= :parchment pref)) pref)]
    {:db (assoc db :theme pref) :theme/apply [pref (themes/effective pref (:os-dark? db))]}))

(defn theme-os [db dark?] (theme-select (assoc db :os-dark? dark?) (:theme db)))

(defn copy-id [db] {:db (assoc db :message "Copied") :clipboard/write (:session db)})

;; sync

(defn- selection-ids [db] (vec (sort (:selection db))))

(defn tube-connected
  "Who I am and what I have selected (on a reconnect), then hello: the roster
  and the others then have my name from the start."
  [db]
  (let [p (cond-> {}
            (:name db) (assoc :name (:name db))
            (seq (:selection db)) (assoc :selection (selection-ids db)))]
    {:db (assoc db :online? true) :tube/send (conj (if (seq p) [[:presence/update p]] []) [:session/hello])}))

(defn tube-disconnected [db] {:db (assoc db :online? false :peers {})})

(defn snapshot [db objects]
  {:db (prune (assoc db :objects objects :history {:undo [] :redo []} :drag nil :loading? false))})

(defn remote-upsert [db objs] {:db (update db :objects ops/upsert objs)})
(defn remote-patch [db patches] {:db (update db :objects ops/patch patches)})
(defn remote-delete [db ids] {:db (prune (update db :objects ops/delete ids))})

;; who is here: the server tells me my id and colour and everyone's presence

(defn presence-roster [db you users]
  (let [{me true others false} (group-by #(= you (:id %)) users)]
    {:db (assoc db :you (first me) :peers (into {} (map (juxt :id identity)) others))}))

(defn presence-update
  "A peer's presence (with :color), or what changed of it. A change for a
  peer I don't know yet waits for its hello, which brings all of it."
  [db user]
  (let [id (:id user)]
    (when (and (not= id (get-in db [:you :id])) (or (:color user) (get-in db [:peers id])))
      {:db (update-in db [:peers id] merge user)})))

(defn presence-left [db id] {:db (update db :peers dissoc id)})

(defn log-all [db lg] {:db (assoc db :log lg)})
(defn log-add [db entries] {:db (update db :log log/add entries)})

(def ^:private log-labels
  {:created "created" :restored "restored" :edited "changed the text" :filled "changed the fill"
   :coloured "changed the text colour" :locked "locked" :unlocked "unlocked" :grouped "grouped"
   :ungrouped "ungrouped" :reordered "changed the order" :moved "moved" :resized "resized"
   :changed "changed" :deleted "deleted"})

(defn log-label [what] (log-labels what (name what)))

(def ^:private together-ms
  "Entries alike and this close are one change to several objects: a group is
  sent as one op per object, each logged at its own time."
  2000)

(defn log-lines
  "The log of the objects ids, newest first, as [{:at :by :what :n}]: a change
  made to several of them at once (a group moved) is one line, :n of them.
  Alike entries of one ms sort together, so no two lines share :at :by :what."
  [lg ids]
  (->> (mapcat #(get lg %) (set ids))
       (sort-by (juxt (comp - :at) :by :what))
       (reduce (fn [lines {:keys [at by what]}]
                 (let [l (peek lines)]
                   (if (and l (= by (:by l)) (= what (:what l)) (< (- (:last l) at) together-ms))
                     (conj (pop lines) (assoc l :n (inc (:n l)) :last at))
                     (conj lines {:at at :by by :what what :n 1 :last at}))))
               [])
       (mapv #(dissoc % :last))))

(def ^:private months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn format-time
  "ms at in local time: HH:MM on the day of now, else with the day (and the
  year, if not now's)."
  [at now]
  (let [d (js/Date. at)
        n (js/Date. now)
        two #(.padStart (str %) 2 "0")
        hm (str (two (.getHours d)) ":" (two (.getMinutes d)))]
    (if (= (.toDateString d) (.toDateString n))
      hm
      (str (.getDate d) " " (months (.getMonth d))
           (when (not= (.getFullYear d) (.getFullYear n)) (str " " (.getFullYear d)))
           " " hm))))

(defn- with-presence
  "Handler f, telling the others when it changed my selection."
  [f]
  (fn [db & args]
    (let [fx (apply f db args)]
      (if (and (:db fx) (not= (:selection db) (get-in fx [:db :selection])))
        (presence-send fx {:selection (selection-ids (:db fx))})
        fx))))

(defn name-dialog?
  "Whether to ask for my name: on a board until I gave one, and while renaming."
  [route name naming]
  (and (= :board route) (or (nil? name) (some? naming))))

(defn clean-name
  "s as a name: control characters as spaces, trimmed, at most 32 characters;
  nil when blank."
  [s]
  (let [n (str/trim (str/replace (str s) ops/name-control-chars " "))]
    (when-not (str/blank? n) (subs n 0 (min (count n) ops/max-name-length)))))

(defn name-edit [db] {:db (assoc db :naming (or (:name db) ""))})
(defn name-input [db s] {:db (assoc db :naming s)})

(defn name-save
  "Takes the typed name; saved in this browser and shown to the others."
  [db]
  (when-let [n (clean-name (:naming db))]
    (presence-send {:db (assoc db :name n :naming nil) :name/store n} {:name n})))

(defn name-cancel [db] (when (:name db) {:db (assoc db :naming nil)}))

;; tools, camera, misc

(defn select-tool
  "Picks a tool; picking the active one again goes back to Select."
  [db tool]
  {:db (assoc db :tool (if (= tool (:tool db)) :select tool))})

(def tool-keys
  "The key that picks each tool, as the toolbar shows it."
  {:select "V" :text "T" :frame "F" :shape "S" :sticky "N" :connection "L"
   :dot "D" :counter "C" :scale-x "X" :scale-xy "Y"})

(defn tool-hint
  "What to do with the active tool, nil for Select."
  [tool shape-kind]
  (let [k (tool-keys tool)
        place (fn [what] (str "Click or drag on the board to place " what " · " k " or Esc to cancel"))]
    (case tool
      :text (place "a text")
      :frame (place "a frame")
      :sticky (place "a sticky note")
      :dot (place "a dot")
      :counter (place "a box that counts the dots in it")
      :scale-x (place "a scale that averages its dots' x")
      :scale-xy (place "a scale that averages its dots' x and y")
      :shape (str "Click or drag on the board to place a " ({:rect "rectangle"} shape-kind (name shape-kind))
                  " · " k " for the next shape, Esc to cancel")
      :connection (str "Drag from one object to another to connect them · " k " or Esc to cancel")
      nil)))
(defn select-shape [db shape] {:db (assoc db :tool :shape :shape-kind shape)})
(defn shapes-key
  "S: the shape tool, and pressed again the next shape."
  [db]
  (if (= :shape (:tool db))
    (let [ks model/shape-kinds
          i (.indexOf ks (:shape-kind db))]
      (select-shape db (nth ks (mod (inc i) (count ks)))))
    (select-shape db (:shape-kind db))))
(defn hand-toggle
  "Hand mode: dragging empty canvas pans and the wheel zooms."
  [db]
  (when (= :board (:route db))
    (let [on? (not (:hand? db))] {:db (assoc db :hand? on?) :hand/store on?})))

(defn camera-pan [db dx dy] {:db (update db :camera geom/pan (- dx) (- dy))})
(defn camera-zoom [db factor sx sy] {:db (update db :camera geom/zoom-at factor [sx sy])})

(def max-wheel-zoom 1.15)

(defn wheel-px
  "A wheel delta in pixels: deltaMode 1 counts lines, 2 pages."
  [d mode page]
  (case mode 1 (* 16 d) 2 (* page d) d))

(defn wheel
  "A wheel turn (deltas in pixels): ctrl zooms at the pointer, at most 15% per
  event (a mouse notch) and in proportion below that (a trackpad pinch); in
  hand mode the plain wheel zooms too. Shift scrolls sideways (some browsers
  already swap the axes for shift, so both deltas count)."
  [db {:keys [dx dy ctrl? shift? sx sy]}]
  (cond
    (and (:hand? db) (not ctrl?) (not shift?) (zero? dy)) (camera-pan db dx 0)
    (or ctrl? (and (:hand? db) (not shift?)))
    (camera-zoom db (-> (js/Math.exp (* -0.01 dy)) (max (/ 1 max-wheel-zoom)) (min max-wheel-zoom)) sx sy)
    shift? (camera-pan db (+ dx dy) 0)
    :else (camera-pan db dx dy)))
(defn zoom-step
  "Zoom by factor around the middle of the viewport (the + and - buttons)."
  [db factor]
  (let [[w h] (:viewport db)] (camera-zoom db factor (/ w 2) (/ h 2))))
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
  "[obj corner] when p is on a handle of the single selected object, if resizable."
  [db p]
  (let [sel (:selection db)
        o (when (= 1 (count sel)) (get-in db [:objects (first sel)]))]
    (when (model/resizable? o)
      (when-let [k (geom/handle-at o p (tol db geom/handle-px))] [o k]))))

(defn- press [db {:keys [sx sy button shift? ctrl? t]} p]
  (let [tool (:tool db) hit (hit-at db p) sel (:selection db)]
    (cond
      (or (= button 1) (:space? db)) (assoc db :drag {:type :pan :last [sx sy]})
      (creating-tools tool) (assoc db :drag {:type :create :kind tool :start p :current p})
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
          (if (and (:hand? db) (not shift?) (not ctrl?))
            (assoc db :drag {:type :pan :last [sx sy] :start [sx sy] :clear? true})
            (assoc db :selection (if shift? sel #{})
                      :drag {:type :band :start p :current p :shift? shift?})))))))

(defn pointer-down [db {:keys [button] :as e}]
  (when (<= button 1)
    (let [[db ops] (finish-edit (assoc db :message nil :sel-menu nil))]
      (with-ops (press db e (world db e)) ops))))

(defn grip-down
  "A press on the selection toolbar's grip: moves the selection like a drag on it,
  so small or far-zoomed objects move without hitting their handles."
  [db {:keys [button t] :as e}]
  (let [start (model/movable (:objects db) (:selection db))]
    (when (and (= button 0) (seq start))
      {:db (assoc db :message nil :sel-menu nil :drag {:type :move :origin (world db e) :start start :last-sent t})})))

(defn- live-update
  "Applies a gesture's changes locally; sends their geometry when the last send is
  send-interval ago."
  [db changes t]
  (let [db (update db :objects model/apply-changes changes)]
    (if (>= (- t (get-in db [:drag :last-sent])) send-interval)
      (with-ops (assoc-in db [:drag :last-sent] t) (model/patch-ops (vals changes)))
      {:db db})))

(defn- drag-move [db {:keys [sx sy t]} p]
  (let [drag (:drag db)]
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

(defn- send-cursor
  "fx (nil: nothing to do) also telling the others my pointer's board position
  p, at most every send-interval; a held back one goes out a moment later."
  [fx db p t]
  (let [db (:db fx db)]
    (cond
      (not (:online? db)) fx
      (>= (- t (:cursor-sent db)) send-interval)
      (presence-send (assoc fx :db (assoc db :cursor-sent t :cursor-pending nil)) {:cursor p})
      :else (cond-> (assoc fx :db (assoc db :cursor-pending p))
              (nil? (:cursor-pending db)) (assoc :cursor/flush-later send-interval)))))

(defn cursor-flush
  "Sends the pointer position the throttle held back, if any, at t."
  [db t]
  (when-let [p (:cursor-pending db)]
    (presence-send {:db (assoc db :cursor-sent t :cursor-pending nil)} {:cursor p})))

(defn pointer-move [db {:keys [t] :as e}]
  (let [p (world db e)] (send-cursor (drag-move db e p) db p t)))

(defn pointer-leave [db] (when (:online? db) (presence-send {:db (assoc db :cursor-pending nil)} {:cursor nil})))

(defn- create-object [db {:keys [kind start]} end]
  (let [r (geom/rect-from-points start end)
        [dw dh] (model/default-size kind)
        ;; a fixed-size kind (a dot) is always placed like a click
        rect (if (or (model/fixed-size-kinds kind) (and (< (:w r) 5) (< (:h r) 5)))
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
        :create (create-object db drag p)
        :connect (connect db (:from drag) p)
        :band (band-select db drag p)
        (:move :resize) (let [[db ops] (model/finish-gesture db (:start drag))] (with-ops db ops))
        :pan {:db (cond-> db
                    (and (:clear? drag) (let [[x y] (:start drag)] (< (max (abs (- (:sx e) x)) (abs (- (:sy e) y))) 3))) (assoc :selection #{}))}
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
(defn selection-text-color [db color] (change-selection db model/text-color-changes color))
(defn selection-lock [db locked?] (change-selection db model/lock-changes locked?))
(defn selection-group [db] (change-selection db model/group-changes))
(defn selection-ungroup [db] (change-selection db model/ungroup-changes))
(defn selection-layer [db move] (change-selection db model/layer-changes move))
(defn selection-side [db side] (change-selection db model/side-changes side))

(defn open-menu
  "The selection toolbar's open menu (:fill or :layer), or nil. It belongs to the
  selection it was opened for, so another selection closes it."
  [sel-menu sel]
  (when (= sel (:for sel-menu)) (:menu sel-menu)))

(defn menu-toggle [db menu]
  {:db (assoc db :sel-menu (when-not (= menu (open-menu (:sel-menu db) (:selection db)))
                             {:menu menu :for (:selection db)}))})
(defn selection-delete [db] (change-selection db model/delete-changes))

(defn selection-nudge
  "Moves the selection by dx dy; repeat? (a held key) keeps it one undo step."
  [db dx dy repeat?]
  (when-not (:drag db)
    (let [[db' ops] (model/nudge db (:selection db) dx dy repeat?)]
      (when (seq ops) (with-ops db' ops)))))
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

(defn- pasteable?
  "On a loaded board: the snapshot would drop what is pasted before it."
  [db]
  (and (= :board (:route db)) (not (:loading? db))))

(defn paste-text [db text]
  (when (pasteable? db) (place db :text 300 100 {:text text})))

(defn paste-image [db src w h]
  (when (pasteable? db)
    (if (> (count src) max-image-chars)
      {:db (assoc db :message "Image too large (max 15 MB)")}
      (let [w (max 1 w) h (max 1 h) s (min 1 (/ 600 w) (/ 600 h))]
        (place db :image (* w s) (* h s) {:src src})))))

;; copy and paste of board objects

(def clipboard-type
  "The clipboard type copied objects travel in; text/plain gets their text."
  "application/x-dingsbums")
(def paste-offset-px 20)

(defn copy-payload
  "{:data :text} for copying the selection, nil when nothing is selected: the
  selected objects and the connections between them. A lone image adds :image,
  its src."
  [db]
  (let [objects (:objects db)
        ids (into #{} (remove #(= :connection (:kind (get objects %)))) (:selection db))
        os (->> (ops/cascade-ids objects ids)
                (keep objects)
                (filter #(or (not= :connection (:kind %)) (and (ids (:from %)) (ids (:to %)))))
                (sort-by :z))]
    (when (and (= :board (:route db)) (seq os))
      (cond-> {:data (pr-str {:dingsbums/objects (vec os)})
               :text (str/join "\n\n" (keep #(not-empty (:text %)) os))}
        (and (= 1 (count os)) (= :image (:kind (first os)))) (assoc :image (:src (first os)))))))

(defn- inert-template
  "A <template>: HTML put into it loads no images and runs nothing."
  [] (js/document.createElement "template"))

(defn copy-html
  "HTML for the system clipboard: the picture src, with the copied data for dingsbums."
  [data src]
  (let [img (.createElement (.. (inert-template) -content -ownerDocument) "img")]
    (.setAttribute img "src" src)
    (.setAttribute img "data-dingsbums" data)
    (.-outerHTML img)))

(defn html-copied
  "The copied data in clipboard HTML from copy-html, else nil."
  [html]
  (when (string? html)
    (let [t (inert-template)]
      (set! (.-innerHTML t) html)
      (some-> (.. t -content (querySelector "[data-dingsbums]")) (.getAttribute "data-dingsbums")))))

(defn read-copied
  "The valid objects in copied clipboard data, nil for none or anything else."
  [s]
  (let [v (try (reader/read-string s) (catch :default _ nil))
        os (when (map? v) (:dingsbums/objects v))]
    (when (sequential? os) (not-empty (filterv ops/valid-object? os)))))

(defn- overlaps? [a b]
  (and (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
       (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a)))))

(defn- cascade-offset
  "The first of step, 2 step, … that puts no copy of os on an object of its kind."
  [objects os step]
  (let [on? (fn [d o] (some #(and (= (:kind o) (:kind %))
                                  (< (abs (- (+ (:x o) d) (:x %))) 1) (< (abs (- (+ (:y o) d) (:y %))) 1))
                            (vals objects)))]
    (first (remove (fn [d] (some #(on? d %) os)) (iterate #(+ % step) step)))))

(defn paste-objects
  "Pastes copied objects as new ones: fresh ids and groups, unlocked, on top,
  connections only between pasted objects. Beside the originals while they are
  in view (pasting again steps further), else in the middle of the view."
  [db os]
  (when (= :board (:route db))
    (let [shapes (filter #(and (not= :connection (:kind %)) (every? (fn [k] (js/isFinite (k %))) [:x :y :w :h])) os)
          ids (set (map :id shapes))
          lines (filter #(and (= :connection (:kind %)) (ids (:from %)) (ids (:to %))) os)]
      (cond
        (empty? shapes) nil
        (some #(> (count (:src %)) max-image-chars) shapes) {:db (assoc db :message "Image too large (max 15 MB)")}
        :else
        (let [{:keys [camera viewport objects]} db
              [x0 y0] (geom/screen->world camera [0 0])
              [x1 y1] (geom/screen->world camera viewport)
              b (geom/bounds shapes)
              [dx dy] (if (overlaps? b {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)})
                        (let [d (cascade-offset objects shapes (/ paste-offset-px (:zoom camera)))] [d d])
                        (let [[cx cy] (geom/center b)] [(- (/ (+ x0 x1) 2) cx) (- (/ (+ y0 y1) 2) cy)]))
              fresh (into {} (map (fn [k] [k (model/new-id)])) (concat ids (keep :group (concat shapes lines))))
              z (model/next-z objects)
              copies (map-indexed
                      (fn [i o]
                        (cond-> (-> o (dissoc :locked?) (assoc :id (or (fresh (:id o)) (model/new-id)) :z (+ z i)))
                          (:x o) (assoc :x (+ (:x o) dx) :y (+ (:y o) dy))
                          (:group o) (update :group fresh)
                          (:from o) (assoc :from (fresh (:from o)) :to (fresh (:to o)))))
                      (sort-by :z (concat shapes lines)))]
          (commit (assoc db :tool :select :selection (set (map :id copies)))
                  (into {} (map (juxt :id identity)) copies)))))))

;; import

(defn import-file [db file]
  (let [sid (:session db)]
    {:db (assoc db :message "Importing…")
     :http {:method :post :uri (str "api/sessions/" (js/encodeURIComponent sid) "/import")
            :body file :headers {"Content-Type" "application/x-tar"} :response-format :text
            :on-success [:import/done sid] :on-failure [:import/failed sid]}}))

(defn import-done [db sid & _] (when (= sid (:session db)) {:db (assoc db :message nil)}))
(defn import-failed [db sid & _]
  (when (= sid (:session db)) {:db (assoc db :message "Import failed: not a dingsbums export")}))

;; keys

(def ^:private arrows {"ArrowLeft" [-1 0] "ArrowRight" [1 0] "ArrowUp" [0 -1] "ArrowDown" [0 1]})

(def ^:private key-tools (into {} (map (fn [[t k]] [(str/lower-case k) t])) (dissoc tool-keys :shape)))

(defn key-action
  "The event for a key press outside text inputs, or nil to leave it to the browser."
  [{:keys [key ctrl? shift? alt? repeat?]}]
  (let [k (str/lower-case key)]
    (cond
      (and ctrl? (= k "z")) (if shift? [:history/redo] [:history/undo])
      (and ctrl? (= k "y")) [:history/redo]
      (and ctrl? (= k "g")) (if shift? [:selection/ungroup] [:selection/group])
      ctrl? nil
      (= key "F2") [:edit/start]
      (#{"Delete" "Backspace"} key) [:selection/delete]
      (= key "Escape") [:selection/clear]
      (= key " ") [:space true]
      (and (not alt?) (arrows key)) (let [step (if shift? 10 1)
                                          [dx dy] (arrows key)]
                                      [:selection/nudge (* step dx) (* step dy) (boolean repeat?)])
      (or alt? repeat?) nil
      (= key "Home") [:selection/layer :front]
      (= key "End") [:selection/layer :back]
      (= k (str/lower-case (tool-keys :shape))) [:tool/shapes]
      (= k "h") [:hand/toggle]
      :else (when-let [tool (key-tools k)] [:tool/select tool]))))

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
                :presence/roster presence-roster
                :presence/update presence-update
                :presence/left presence-left
                :log/all log-all
                :log/add log-add
                :name/edit name-edit
                :name/input name-input
                :name/save name-save
                :name/cancel name-cancel
                :landing/input landing-input
                :landing/create landing-create
                :landing/join landing-join
                :landing/failed landing-failed
                :tool/select select-tool
                :tool/shape select-shape
                :camera/pan camera-pan
                :camera/zoom camera-zoom
                :camera/zoom-step zoom-step
                :tool/shapes shapes-key
                :hand/toggle hand-toggle
                :wheel wheel
                :viewport set-viewport
                :space set-space
                :image/loaded image-loaded
                :pointer/down pointer-down
                :pointer/move pointer-move
                :pointer/up pointer-up
                :pointer/leave pointer-leave
                :cursor/flush cursor-flush
                :pointer/dblclick dblclick
                :pointer/grip grip-down
                :edit/start edit-start
                :edit/input edit-input
                :edit/commit edit-commit
                :edit/cancel edit-cancel
                :selection/fill selection-fill
                :selection/text-color selection-text-color
                :selection/lock selection-lock
                :selection/group selection-group
                :selection/ungroup selection-ungroup
                :selection/layer selection-layer
                :selection/side selection-side
                :selection/menu menu-toggle
                :selection/delete selection-delete
                :selection/nudge selection-nudge
                :selection/clear selection-clear
                :history/undo history-undo
                :history/redo history-redo
                :paste/text paste-text
                :paste/image paste-image
                :paste/objects paste-objects
                :import/file import-file
                :import/done import-done
                :import/failed import-failed}]
  (reg-event id (with-presence f)))
