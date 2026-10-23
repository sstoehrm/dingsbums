(ns dingsbums.ops
  "Object ops shared by server and client. objects is a map {id obj}.")

(def ^:private field-ok
  "Every key an object may have, with the check its value must pass."
  {:id #(and (string? %) (re-matches #"[A-Za-z0-9_-]{1,64}" %))
   :kind #{:text :frame :shape :sticky :image :connection :dot :counter :scale-x :scale-xy}
   :shape #{:circle :rect :star :arrow}
   :side #{:top :bottom :left :right}
   :x number? :y number? :w number? :h number? :z number?
   :fill #(or (nil? %) (string? %))
   :text string? :color string? :src string? :from string? :to string? :group string?
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

(def max-name-length 32)
(def default-name "The name of someone who gave none." "Guest")
(def name-control-chars
  "What a name must not contain: control characters and half a surrogate pair."
  #"[\x00-\x1f\x7f]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]")
(def max-selection 10000)

(defn- finite? [x] (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/isFinite x))))

(defn valid-name?
  "s is a user's name: 1–32 UTF-16 code units (an emoji is two), no control
  characters, no half emoji."
  [s]
  (and (string? s) (<= 1 (count s) max-name-length) (not (re-find name-control-chars s))))

(def ^:private presence-ok
  "What a client may say about itself, with the check each value must pass. Its
  id and colour come from the server."
  {:name valid-name?
   :cursor #(or (nil? %) (and (vector? %) (= 2 (count %)) (every? finite? %)))
   :selection #(and (vector? %) (<= (count %) max-selection) (every? (:id field-ok) %))})

(defn presence?
  "p is a well-formed presence update: some of :name (1–32 characters, no
  control characters), :cursor ([x y] in board coordinates, nil: off the board)
  and :selection (the ids the user has selected, at most 10000)."
  [p]
  (and (map? p) (seq p) (every? (fn [[k v]] (when-let [ok (presence-ok k)] (ok v))) p)))
