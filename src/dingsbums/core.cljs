(ns dingsbums.core
  "Entry point: mounts the app and wires window listeners to events."
  (:require [clojure.string :as str]
            [hammer.core :refer [mount! dispatch]]
            [dingsbums.events :as events]
            [dingsbums.themes :as themes]
            [dingsbums.views :as views]))

(defn- root [] (js/document.getElementById "app"))

(defn- hash-id []
  (let [h (subs (.-hash js/location) 1)]
    (try (js/decodeURIComponent h) (catch :default _ h))))

(defn- typing? [^js e] (contains? #{"INPUT" "TEXTAREA" "SELECT"} (.. e -target -tagName)))

(defn- on-keydown [^js e]
  (when-not (typing? e)
    (when-let [action (events/key-action {:key (.-key e) :ctrl? (or (.-ctrlKey e) (.-metaKey e))
                                          :shift? (.-shiftKey e)})]
      (.preventDefault e)
      (dispatch action))))

(defn- on-keyup [^js e] (when (= " " (.-key e)) (dispatch [:space false])))

(defn- paste-image! [^js file]
  (let [r (js/FileReader.)]
    (set! (.-onload r)
          (fn [_]
            (let [src (.-result r)
                  img (js/Image.)]
              (set! (.-onload img) (fn [_] (dispatch [:paste/image src (.-naturalWidth img) (.-naturalHeight img)])))
              (set! (.-src img) src))))
    (.readAsDataURL r file)))

(defn- on-paste [^js e]
  (when-not (typing? e)
    (let [^js data (.-clipboardData e)
          file (some (fn [^js item]
                       (when (and (= "file" (.-kind item)) (str/starts-with? (.-type item) "image/"))
                         (.getAsFile item)))
                     (js/Array.from (.-items data)))
          text (.getData data "text/plain")]
      (cond
        file (do (.preventDefault e) (paste-image! file))
        (not (str/blank? text)) (do (.preventDefault e) (dispatch [:paste/text text]))))))

(defn- stored-theme []
  (try (let [v (js/localStorage.getItem events/theme-storage-key)]
         (some #(when (= v (name %)) %) themes/names))
       (catch :default _ nil)))

(defn- on-resize [] (dispatch [:viewport (.-innerWidth js/window) (.-innerHeight js/window)]))

(defn init []
  (mount! [views/app] (root) (assoc events/initial-db :theme (stored-theme)))
  (let [dark (js/window.matchMedia "(prefers-color-scheme: dark)")]
    (.addEventListener dark "change" #(dispatch [:theme/os (.-matches %)]))
    (dispatch [:theme/os (.-matches dark)]))
  (.addEventListener js/window "hashchange" #(dispatch [:route/changed (hash-id)]))
  (.addEventListener js/window "keydown" on-keydown)
  (.addEventListener js/window "keyup" on-keyup)
  (.addEventListener js/window "paste" on-paste)
  (.addEventListener js/window "resize" on-resize)
  (on-resize)
  (dispatch [:route/changed (hash-id)]))

(defn ^:dev/after-load reload [] (mount! [views/app] (root)))
