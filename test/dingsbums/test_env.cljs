(ns dingsbums.test-env
  "Browser globals for node tests; require it first in every test ns."
  (:require ["jsdom" :refer [JSDOM]]))

(defonce env
  (let [d (JSDOM. "<!DOCTYPE html><html><body></body></html>" #js {:url "http://localhost/"})
        w (.-window d)]
    (set! js/globalThis.window w)
    (set! js/globalThis.document (.-document w))
    (set! js/globalThis.location (.-location w))
    (set! js/globalThis.requestAnimationFrame (fn [f] (js/setTimeout f 16)))
    d))
