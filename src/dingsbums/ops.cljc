(ns dingsbums.ops
  "Object ops shared by server and client. objects is a map {id obj}.")

(def ^:private field-ok
  "Every key an object may have, with the check its value must pass."
  {:id #(and (string? %) (re-matches #"[A-Za-z0-9_-]{1,64}" %))
   :kind #{:text :frame :shape :sticky :image :connection}
   :shape #{:circle :rect :star :arrow}
   :x number? :y number? :w number? :h number? :z number?
   :fill #(or (nil? %) (string? %))
   :text string? :src string? :from string? :to string? :group string?
   :locked? boolean?})

(defn valid-object?
  "o is a board object: an :id (1–64 of [A-Za-z0-9_-]) and a known :kind, only
  known keys, each value of the right type."
  [o]
  (and (map? o) (contains? o :id) (contains? o :kind)
       (every? (fn [[k v]] (when-let [ok (field-ok k)] (ok v))) o)))

(def ^:private geometry-keys #{:x :y :w :h})

(defn- valid-patch? [p]
  (and (map? p) (string? (:id p))
       (every? (fn [[k v]] (or (= k :id) (and (geometry-keys k) (number? v)))) p)))

(defn upsert
  "objects with each valid obj of objs put under its :id; invalid ones are skipped."
  [objects objs]
  (into objects (comp (filter valid-object?) (map (juxt :id identity))) objs))

(defn cascade-ids
  "The set of ids plus the ids of connections whose :from or :to is one of them."
  [objects ids]
  (let [ids (set ids)]
    (into ids (keep (fn [[id o]] (when (or (ids (:from o)) (ids (:to o))) id))) objects)))

(defn delete
  "objects without ids and without the connections touching them."
  [objects ids]
  (apply dissoc objects (cascade-ids objects ids)))

(defn patch
  "objects with each patch's geometry ({:id :x :y :w :h}, any subset) merged into
  the existing object of its :id; patches for missing ids are ignored."
  [objects patches]
  (reduce (fn [m p] (if (and (valid-patch? p) (contains? m (:id p))) (update m (:id p) merge (dissoc p :id)) m))
          objects patches))

(defn op?
  "ev is a well-formed [:op/upsert [obj ...]], [:op/patch [patch ...]] or [:op/delete [id ...]]."
  [ev]
  (and (vector? ev) (= 2 (count ev)) (sequential? (second ev))
       (case (first ev)
         :op/upsert (every? valid-object? (second ev))
         :op/patch (every? valid-patch? (second ev))
         :op/delete (every? string? (second ev))
         false)))
