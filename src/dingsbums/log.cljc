(ns dingsbums.log
  "Who changed which object and when: {id [{:at ms :by name :what action}]},
  oldest first. The server writes it as it applies ops, so names and times are
  its own; clients only show it. Kept for deleted objects too, so an undo of a
  delete reads as restored."
  (:refer-clojure :exclude [read])
  (:require [dingsbums.ops :as ops]))

(def cap "Entries kept per object." 50)
(def repeat-ms "A repeat of an object's last entry within this long is left out." 60000)

(def actions #{:created :restored :edited :filled :coloured :locked :unlocked :grouped :ungrouped
               :reordered :moved :resized :changed :deleted})

(defn- changed? [old new ks] (some #(not= (get old %) (get new %)) ks))

(defn- geometry [old new]
  (cond (changed? old new [:w :h]) [:resized]
        (changed? old new [:x :y]) [:moved]))

(defn- upserted
  "What an upsert of new did to old (nil: it wasn't there)."
  [old new logged?]
  (if (nil? old)
    [(if logged? :restored :created)]
    (cond-> []
      (changed? old new [:text]) (conj :edited)
      (changed? old new [:fill]) (conj :filled)
      (changed? old new [:color]) (conj :coloured)
      (changed? old new [:locked?]) (conj (if (:locked? new) :locked :unlocked))
      (changed? old new [:group]) (conj (if (:group new) :grouped :ungrouped))
      (changed? old new [:z]) (conj :reordered)
      true (into (geometry old new))
      (changed? old new [:kind :shape :side :from :to :src]) (conj :changed))))

(defn add
  "log with entries [[id entry] ...] appended, the newest cap per object kept;
  an entry equal to its object's last one is there already."
  [log entries]
  (reduce (fn [lg [id e]]
            (update lg id (fn [es]
                            (cond
                              (= e (peek es)) es
                              (< (count es) cap) (conj (or es []) e)
                              ;; a fresh vector: a subvec keeps everything it was cut from
                              :else (conj (into [] (subvec es (- (count es) (dec cap)))) e)))))
          log entries))

(defn- repeat? [log id {:keys [at by what]}]
  (let [l (peek (get log id))]
    (and l (= by (:by l)) (= what (:what l)) (< (- at (:at l)) repeat-ms))))

(defn entries
  "The entries [[id {:at :by :what}] ...] that op [op arg], sent by `by` at
  now, makes on objects with log: one per thing it did to an object, repeats
  left out (a drag's patches log one move)."
  [objects log [op arg] by now]
  (let [done (case op
               :op/upsert (for [o arg w (upserted (get objects (:id o)) o (seq (get log (:id o))))] [(:id o) w])
               :op/patch (for [p arg :let [o (get objects (:id p))] :when o w (geometry o (merge o p))] [(:id p) w])
               :op/delete (for [id (ops/cascade-ids objects arg) :when (contains? objects id)] [id :deleted]))]
    (first (reduce (fn [[es lg] [id w]]
                     (let [e {:at now :by by :what w}]
                       (if (repeat? lg id e) [es lg] [(conj es [id e]) (add lg [[id e]])])))
                   [[] log] done))))

(defn valid-entry? [e]
  (and (map? e) (= #{:at :by :what} (set (keys e)))
       (nat-int? (:at e)) (ops/valid-name? (:by e)) (contains? actions (:what e))))

(defn read
  "x (untrusted, e.g. from an import) as the log of objects: valid entries of
  objects that are there, none after now; {} for anything else."
  [x objects now]
  (if (map? x)
    (into {} (keep (fn [[id es]]
                     (when (and (contains? objects id) (sequential? es))
                       (when-let [es (not-empty (filterv #(and (valid-entry? %) (<= (:at %) now)) es))]
                         (first (add {} (map #(vector id %) es)))))))
          x)
    {}))
