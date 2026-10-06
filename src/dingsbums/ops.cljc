(ns dingsbums.ops
  "Object ops shared by server and client. objects is a map {id obj}.")

(defn valid-object?
  "o looks like a board object: a map with a string :id (1–64 chars) and a keyword :kind."
  [o]
  (and (map? o) (string? (:id o)) (<= 1 (count (:id o)) 64) (keyword? (:kind o))))

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

(defn op?
  "ev is a well-formed [:op/upsert [obj ...]] or [:op/delete [id ...]]."
  [ev]
  (and (vector? ev) (= 2 (count ev)) (sequential? (second ev))
       (case (first ev)
         :op/upsert (every? valid-object? (second ev))
         :op/delete (every? string? (second ev))
         false)))
