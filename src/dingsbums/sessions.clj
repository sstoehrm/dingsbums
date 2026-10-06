(ns dingsbums.sessions
  "All sessions in one atom: {sid {:objects {id obj} :clients #{ch} :empty-since ms-or-nil}}.
  The fns below are pure over that map; server.clj swap!s them in. Every fn is a
  no-op for an unknown sid."
  (:require [dingsbums.ops :as ops]))

(def idle-ms (* 15 60 1000))

(defonce sessions (atom {}))

(defn new-id [] (str (random-uuid)))

(defn create [m sid now] (assoc m sid {:objects {} :clients #{} :empty-since now}))

(defn- when-exists [m sid f] (if (contains? m sid) (f m) m))

(defn upsert [m sid objs] (when-exists m sid #(update-in % [sid :objects] ops/upsert objs)))

(defn patch [m sid patches] (when-exists m sid #(update-in % [sid :objects] ops/patch patches)))

(defn delete [m sid ids] (when-exists m sid #(update-in % [sid :objects] ops/delete ids)))

(defn replace-objects [m sid objects] (when-exists m sid #(assoc-in % [sid :objects] objects)))

(defn join [m sid ch]
  (when-exists m sid #(-> % (update-in [sid :clients] conj ch) (assoc-in [sid :empty-since] nil))))

(defn leave [m sid ch now]
  (when-exists m sid (fn [m]
                       (let [m (update-in m [sid :clients] disj ch)]
                         (cond-> m (empty? (get-in m [sid :clients])) (assoc-in [sid :empty-since] now))))))

(defn expire
  "m without the sessions that have had no clients for more than 15 minutes at now."
  [m now]
  (into {} (remove (fn [[_ {:keys [clients empty-since]}]]
                     (and (empty? clients) empty-since (> (- now empty-since) idle-ms))))
        m))
