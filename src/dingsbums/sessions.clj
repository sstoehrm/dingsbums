(ns dingsbums.sessions
  "All sessions in one atom: {sid {:objects {id obj} :log log :logged entries :clients {ch user} :empty-since ms-or-nil}}
  (log: see dingsbums.log; logged: the entries the last op added to it),
  a user being {:id :color} plus the presence it sent (:name :cursor :selection).
  The fns below are pure over that map; server.clj swap!s them in. Every fn is a
  no-op for an unknown sid."
  (:require [dingsbums.log :as log]
            [dingsbums.ops :as ops]))

(def idle-ms (* 15 60 1000))

(defonce sessions (atom {}))

(defn new-id [] (str (random-uuid)))

(defn create [m sid now] (assoc m sid {:objects {} :log {} :clients {} :empty-since now}))

(defn- when-exists [m sid f] (if (contains? m sid) (f m) m))

(defn apply-op
  "m with op ev ([op arg], ops/op?) applied to the session's objects and logged
  as `by` at now; :logged holds the entries it added."
  [m sid [op arg :as ev] by now]
  (when-exists m sid (fn [m]
                       (let [{:keys [objects log]} (get m sid)
                             es (log/entries objects log ev by now)]
                         (update m sid assoc
                                 :log (log/add log es)
                                 :logged es
                                 :objects ((case op :op/upsert ops/upsert :op/patch ops/patch :op/delete ops/delete)
                                           objects arg))))))

(defn replace-objects
  "m with the session's board replaced, e.g. by an import, and its log with it."
  ([m sid objects] (replace-objects m sid objects {}))
  ([m sid objects lg] (when-exists m sid #(update % sid assoc :objects objects :log lg))))

(defn- free-color
  "The lowest colour index no client of the session has."
  [clients]
  (let [used (set (map :color (vals clients)))] (first (remove used (range)))))

(defn join [m sid ch uid]
  (when-exists m sid (fn [m]
                       (-> m
                           (assoc-in [sid :clients ch] {:id uid :color (free-color (get-in m [sid :clients]))})
                           (assoc-in [sid :empty-since] nil)))))

(defn set-presence
  "m with presence update p merged into the user of ch; a no-op for an unknown ch."
  [m sid ch p]
  (if (get-in m [sid :clients ch]) (update-in m [sid :clients ch] merge p) m))

(defn leave [m sid ch now]
  (when-exists m sid (fn [m]
                       (let [m (update-in m [sid :clients] dissoc ch)]
                         (cond-> m (empty? (get-in m [sid :clients])) (assoc-in [sid :empty-since] now))))))

(defn expire
  "m without the sessions that have had no clients for more than 15 minutes at now."
  [m now]
  (into {} (remove (fn [[_ {:keys [clients empty-since]}]]
                     (and (empty? clients) empty-since (> (- now empty-since) idle-ms))))
        m))
