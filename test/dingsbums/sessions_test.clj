(ns dingsbums.sessions-test
  (:require [clojure.test :refer [deftest is]]
            [dingsbums.sessions :as s]))

(def a {:id "a" :kind :sticky})
(def ab {:id "ab" :kind :connection :from "a" :to "b"})

(deftest create-and-object-ops
  (let [m (s/create {} "s1" 100)]
    (is (= {:objects {} :clients #{} :empty-since 100} (get m "s1")))
    (is (= {"a" a "ab" ab} (get-in (s/upsert m "s1" [a ab]) ["s1" :objects])))
    (is (= {} (get-in (-> m (s/upsert "s1" [a ab]) (s/delete "s1" ["a"])) ["s1" :objects])))
    (is (= {"x" a} (get-in (s/replace-objects m "s1" {"x" a}) ["s1" :objects])))
    (is (= m (s/upsert m "nope" [a])) "unknown session: no-op, nothing created")
    (is (= m (s/join m "nope" :ch)))))

(deftest join-and-leave-track-idle-time
  (let [m (-> {} (s/create "s1" 100) (s/join "s1" :c1) (s/join "s1" :c2))]
    (is (= #{:c1 :c2} (get-in m ["s1" :clients])))
    (is (nil? (get-in m ["s1" :empty-since])))
    (is (nil? (get-in (s/leave m "s1" :c1 500) ["s1" :empty-since])) "still one client")
    (is (= 600 (get-in (-> m (s/leave "s1" :c1 500) (s/leave "s1" :c2 600)) ["s1" :empty-since])))))

(deftest expire-after-15-idle-minutes
  (let [m (-> {} (s/create "idle" 0) (s/create "busy" 0) (s/join "busy" :c))]
    (is (= #{"idle" "busy"} (set (keys (s/expire m s/idle-ms)))) "exactly 15 min: kept")
    (is (= #{"busy"} (set (keys (s/expire m (inc s/idle-ms))))))))

(deftest ids-are-random-uuids
  (is (re-matches #"[0-9a-f-]{36}" (s/new-id)))
  (is (not= (s/new-id) (s/new-id))))
