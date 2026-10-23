(ns dingsbums.sessions-test
  (:require [clojure.test :refer [deftest is]]
            [dingsbums.sessions :as s]))

(def a {:id "a" :kind :sticky})
(def ab {:id "ab" :kind :connection :from "a" :to "b"})

(deftest create-and-object-ops
  (let [m (s/create {} "s1" 100)]
    (is (= {:objects {} :clients {} :empty-since 100} (get m "s1")))
    (is (= {"a" a "ab" ab} (get-in (s/upsert m "s1" [a ab]) ["s1" :objects])))
    (is (= {} (get-in (-> m (s/upsert "s1" [a ab]) (s/delete "s1" ["a"])) ["s1" :objects])))
    (is (= {"x" a} (get-in (s/replace-objects m "s1" {"x" a}) ["s1" :objects])))
    (is (= m (s/upsert m "nope" [a])) "unknown session: no-op, nothing created")
    (is (= m (s/join m "nope" :ch "u")))))

(deftest join-and-leave-track-idle-time
  (let [m (-> {} (s/create "s1" 100) (s/join "s1" :c1 "u1") (s/join "s1" :c2 "u2"))]
    (is (= {:c1 {:id "u1" :color 0} :c2 {:id "u2" :color 1}} (get-in m ["s1" :clients])))
    (is (nil? (get-in m ["s1" :empty-since])))
    (is (nil? (get-in (s/leave m "s1" :c1 500) ["s1" :empty-since])) "still one client")
    (is (= 600 (get-in (-> m (s/leave "s1" :c1 500) (s/leave "s1" :c2 600)) ["s1" :empty-since])))))

(deftest expire-after-15-idle-minutes
  (let [m (-> {} (s/create "idle" 0) (s/create "busy" 0) (s/join "busy" :c "u"))]
    (is (= #{"idle" "busy"} (set (keys (s/expire m s/idle-ms)))) "exactly 15 min: kept")
    (is (= #{"busy"} (set (keys (s/expire m (inc s/idle-ms))))))))

(deftest presence-per-client
  (let [m (-> {} (s/create "s1" 0) (s/join "s1" :c1 "u1") (s/join "s1" :c2 "u2"))]
    (is (= {:id "u1" :color 0 :name "Ada" :cursor [1 2]}
           (get-in (-> m (s/set-presence "s1" :c1 {:name "Ada"}) (s/set-presence "s1" :c1 {:cursor [1 2]}))
                   ["s1" :clients :c1])))
    (is (= m (s/set-presence m "s1" :c3 {:name "x"})) "unknown client: no-op")
    (is (= m (s/set-presence m "nope" :c1 {:name "x"})))
    (is (= 0 (get-in (-> m (s/leave "s1" :c1 5) (s/join "s1" :c3 "u3")) ["s1" :clients :c3 :color]))
        "a freed colour goes to the next one to join")))

(deftest ids-are-random-uuids
  (is (re-matches #"[0-9a-f-]{36}" (s/new-id)))
  (is (not= (s/new-id) (s/new-id))))
