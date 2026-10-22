(ns dingsbums.model
  "Pure board model: constructors, groups, locks and the undo history. A change
  set is {id obj-or-nil}; nil means the object is removed. A history entry is
  {:before changes :after changes} over the same ids."
  (:require [clojure.string :as str]
            [dingsbums.geom :as geom]
            [dingsbums.ops :as ops]))

(def default-size {:text [200 50] :frame [400 300] :shape [120 120] :sticky [200 200]})
(def sticky-fill "#fff176")
(def shape-kinds [:circle :rect :star :arrow])
(def swatches [nil "#fff176" "#ffb74d" "#e57373" "#81c784" "#64b5f6" "#ba68c8" "#ffffff" "#000000"])
(def text-colors [nil "#000000" "#ffffff" "#e57373" "#ffb74d" "#81c784" "#64b5f6" "#ba68c8"])
(defn ink
  "Text color for a sticky with this #rrggbb fill: dark, or white on dark fills."
  [fill]
  (let [c (js/parseInt (subs (or fill sticky-fill) 1) 16)
        brightness (/ (+ (* 299 (bit-shift-right c 16)) (* 587 (bit-and (bit-shift-right c 8) 255)) (* 114 (bit-and c 255)))
                      1000)]
    (if (< brightness 128) "#fff" "#222")))

(defn- rgb [hex]
  (let [c (js/parseInt (subs (str hex) 1) 16)]
    (when-not (js/isNaN c) [(bit-shift-right c 16) (bit-and (bit-shift-right c 8) 255) (bit-and c 255)])))

(defn blends?
  "The #rrggbb colours a and b are too close to tell apart (a sticky on the board)."
  [a b]
  (let [ca (rgb a) cb (rgb b)]
    (boolean (and ca cb (< (js/Math.hypot (- (ca 0) (cb 0)) (- (ca 1) (cb 1)) (- (ca 2) (cb 2))) 48)))))
(def history-cap 100)
(def text-kinds #{:text :sticky})

(defn new-id [] (str (random-uuid)))

(defn next-z [objects] (inc (reduce max 0 (keep :z (vals objects)))))

(defn make
  "A new object of kind: fresh id, top z, rect (nil for connections), kind defaults, then extra."
  [objects kind rect extra]
  (merge {:id (new-id) :kind kind :z (next-z objects)}
         (select-keys rect [:x :y :w :h])
         (case kind :sticky {:fill sticky-fill :text ""} :text {:text ""} {})
         extra))

(defn expand-groups
  "ids plus every object sharing a group with one of them."
  [objects ids]
  (let [groups (into #{} (keep #(:group (get objects %))) ids)]
    (into (set ids) (keep (fn [[id o]] (when (contains? groups (:group o)) id))) objects)))

(defn- unlocked [objects ids]
  (keep #(let [o (get objects %)] (when (and o (not (:locked? o))) o)) ids))

(defn movable
  "{id obj} of the unlocked, non-connection objects among ids."
  [objects ids]
  (into {} (comp (remove #(= :connection (:kind %))) (map (juxt :id identity))) (unlocked objects ids)))

(defn moved
  "Changes moving each object of start ({id obj} at pointer-down) by dx dy, applied
  to its current state in objects; objects deleted or locked meanwhile are skipped."
  [objects start dx dy]
  (into {} (keep (fn [[id o]]
                   (let [cur (get objects id)]
                     (when (and cur (not (:locked? cur)))
                       [id (assoc cur :x (+ (:x o) dx) :y (+ (:y o) dy))]))))
        start))

(defn group-changes [objects ids]
  (let [os (remove #(= :connection (:kind %)) (keep objects ids))]
    (if (< (count os) 2)
      {}
      (let [g (new-id)] (into {} (map (fn [o] [(:id o) (assoc o :group g)])) os)))))

(defn ungroup-changes [objects ids]
  (into {} (keep (fn [id] (when-let [o (get objects id)] (when (:group o) [id (dissoc o :group)])))) ids))

(defn lock-changes [objects ids locked?]
  (into {} (keep (fn [id] (when-let [o (get objects id)]
                            [id (if locked? (assoc o :locked? true) (dissoc o :locked?))])))
        ids))

(defn delete-changes
  "Removes the unlocked objects among ids and the connections touching them."
  [objects ids]
  (into {} (comp (filter #(contains? objects %)) (map (fn [id] [id nil])))
        (ops/cascade-ids objects (map :id (unlocked objects ids)))))

(defn text-color
  "The colour o's text is drawn in: its chosen :color, else ink for its sticky's
  fill or the filled object behind it, else nil (the theme's text colour)."
  [objects o]
  (or (:color o)
      (when-let [b (if (= :sticky (:kind o)) o (geom/backdrop objects o))] (ink (:fill b)))))

(defn text-color-changes
  "Sets the text colour of unlocked texts and stickies; nil goes back to automatic."
  [objects ids color]
  (into {} (keep (fn [o] (when (text-kinds (:kind o))
                           [(:id o) (if color (assoc o :color color) (dissoc o :color))])))
        (unlocked objects ids)))

(defn fill-changes
  "Sets the fill of unlocked shapes and stickies; nil (no fill) only for shapes."
  [objects ids color]
  (into {} (keep (fn [o] (when (and (#{:shape :sticky} (:kind o)) (or color (= :shape (:kind o))))
                           [(:id o) (assoc o :fill color)])))
        (unlocked objects ids)))

(defn- step-up
  "v with each selected object one place up, past the unselected one above it."
  [v sel?]
  (reduce (fn [v i] (if (and (sel? (v i)) (not (sel? (v (inc i))))) (assoc v i (v (inc i)) (inc i) (v i)) v))
          v (range (- (count v) 2) -1 -1)))

(defn- raised
  "zs (ascending) raised just enough to be strictly increasing from index i to j,
  and past j while a raised value collides; the rest as it was."
  [zs i j]
  (loop [k i prev (get zs (dec i) ##-Inf) out (subvec zs 0 i)]
    (let [z (get zs k)]
      (if (and z (or (<= k j) (<= z prev)))
        (let [z (max z (inc prev))] (recur (inc k) z (conj out z)))
        (into out (subvec zs k))))))

(defn- layer-z
  "{obj z} for the layer l (bottom to top) rearranged by move."
  [l sel? move]
  (let [zs (mapv #(:z % 0) l)
        s (filter sel? l)
        sz (map #(:z % 0) s)
        uz (keep #(when-not (sel? %) (:z % 0)) l)]
    (case move
      ;; compared by z, not draw order: equal z may draw either way round on another client
      :front (when (and (seq s) (seq uz) (<= (apply min sz) (apply max uz)))
               (zipmap s (map #(+ (peek zs) 1 %) (range))))
      :back (when (and (seq s) (seq uz) (>= (apply max sz) (apply min uz)))
              (zipmap s (map #(+ (- (first zs) (count s)) %) (range))))
      ;; the same z values in the new order, made strictly increasing around the
      ;; step: equal z (two clients creating at once) would otherwise swap nothing
      (:forward :backward)
      (let [v (if (= move :forward) (step-up l sel?) (vec (rseq (step-up (vec (rseq l)) sel?))))
            changed (keep-indexed (fn [i o] (when (not= o (l i)) i)) v)]
        (when (seq changed)
          (zipmap v (raised zs (first changed) (last changed))))))))

(defn layer-changes
  "Moves the unlocked objects among ids to the :front or :back, or one step
  :forward or :backward, in draw order, within their layer (frames stay below
  connections, connections below the rest). Only objects whose :z changes are in it."
  [objects ids move]
  (let [sel? (comp (into #{} (map :id) (unlocked objects ids)) :id)]
    (into {}
          (comp (mapcat #(layer-z % sel? move))
                (keep (fn [[o z]] (when (not= z (:z o)) [(:id o) (assoc o :z z)]))))
          (vals (group-by geom/layer (geom/draw-order objects))))))

(defn text-changes
  "Sets the text of object id; an emptied :text object is removed."
  [objects id text]
  (let [o (get objects id)]
    (cond
      (or (nil? o) (:locked? o) (not (text-kinds (:kind o)))) {}
      (and (= :text (:kind o)) (str/blank? text)) {id nil}
      (= text (:text o)) {}
      :else {id (assoc o :text text)})))

(defn apply-changes [objects changes]
  (reduce-kv (fn [m id o] (if o (assoc m id o) (dissoc m id))) objects changes))

(defn ops-for
  "The tube events that apply changes on the server and the other clients: one
  upsert per object (an image alone can be near the frame limit), one delete."
  [changes]
  (let [dels (vec (keep (fn [[id o]] (when (nil? o) id)) changes))]
    (cond-> (into [] (keep (fn [[_ o]] (when o [:op/upsert [o]]))) changes)
      (seq dels) (conj [:op/delete dels]))))

(defn patch-ops
  "The tube events for moved/resized objects: geometry only, never an image's :src."
  [objs]
  (if (seq objs) [[:op/patch (mapv #(select-keys % [:id :x :y :w :h]) objs)]] []))

(defn- push-entry [history entry]
  {:undo (vec (take-last history-cap (conj (:undo history []) entry))) :redo []})

(defn commit
  "[db ops]: changes applied to (:objects db) and recorded as one history entry.
  Changes equal to the current state are dropped; if none remain, nothing happens."
  [db changes]
  (let [objects (:objects db)
        changes (into {} (remove (fn [[id o]] (= o (get objects id)))) changes)]
    (if (empty? changes)
      [db []]
      [(-> db
           (update :history push-entry {:before (into {} (map (fn [id] [id (get objects id)])) (keys changes))
                                        :after changes})
           (update :objects apply-changes changes))
       (ops-for changes)])))

(defn- step [db from to k]
  (if-let [e (peek (get-in db [:history from]))]
    (let [changes (k e)]
      [(-> db
           (update-in [:history from] pop)
           (update-in [:history to] (fnil conj []) e)
           (update :objects apply-changes changes))
       (ops-for changes)])
    [db []]))

(defn undo [db] (step db :undo :redo :before))
(defn redo [db] (step db :redo :undo :after))

(defn finish-gesture
  "[db ops] for a drag/resize that started from `before` ({id obj}) and has
  already been applied to (:objects db): one history entry, final upserts."
  [db before]
  (let [after (into {} (keep (fn [id] (when-let [o (get-in db [:objects id])] [id o]))) (keys before))
        before (select-keys before (keys after))]
    (if (= before after)
      [db []]
      [(update db :history push-entry {:before before :after after}) (patch-ops (vals after))])))
