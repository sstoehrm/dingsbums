(ns dingsbums.model
  "Pure board model: constructors, groups, locks and the undo history. A change
  set is {id obj-or-nil}; nil means the object is removed. A history entry is
  {:before changes :after changes} over the same ids."
  (:require [clojure.string :as str]
            [dingsbums.ops :as ops]))

(def default-size {:text [200 50] :frame [400 300] :shape [120 120] :sticky [200 200]})
(def sticky-fill "#fff176")
(def swatches [nil "#fff176" "#ffb74d" "#e57373" "#81c784" "#64b5f6" "#ba68c8" "#ffffff" "#000000"])
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

(defn fill-changes
  "Sets the fill of unlocked shapes and stickies; nil (no fill) only for shapes."
  [objects ids color]
  (into {} (keep (fn [o] (when (and (#{:shape :sticky} (:kind o)) (or color (= :shape (:kind o))))
                           [(:id o) (assoc o :fill color)])))
        (unlocked objects ids)))

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
