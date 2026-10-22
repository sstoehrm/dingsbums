(ns dingsbums.core
  "Entry point: mounts the app and wires window listeners to events."
  (:require [clojure.string :as str]
            [hammer.core :refer [mount! dispatch]]
            [hammer.state :as state]
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
                                          :shift? (.-shiftKey e) :alt? (.-altKey e) :repeat? (.-repeat e)})]
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

(defonce ^:private copies (atom 0))

(defn- png-blob
  "A promise of the image at src as a PNG, the image type every clipboard takes;
  it fails once another copy came after copy n, so a slow image can't replace it."
  [src n]
  (js/Promise.
   (fn [ok fail]
     (let [img (js/Image.)]
       (set! (.-onload img)
             (fn [_]
               (try
                 (let [c (js/document.createElement "canvas")]
                   (set! (.-width c) (.-naturalWidth img))
                   (set! (.-height c) (.-naturalHeight img))
                   (.drawImage (.getContext c "2d") img 0 0)
                   (.toBlob c #(if (and % (= n @copies)) (ok %) (fail (js/Error. "no PNG"))) "image/png"))
                 ;; a tainted or too large canvas
                 (catch :default err (fail err)))))
       (set! (.-onerror img) fail)
       (set! (.-src img) src)))))

(defn- write-image!
  "Puts a copied image on the system clipboard as a picture for other apps, the
  copy riding along in its HTML. Without the async clipboard (plain http, older
  browsers) or when it refuses, the copy event's data stays: dingsbums only."
  [src data n]
  (when (and (exists? js/ClipboardItem) (some-> js/navigator .-clipboard .-write))
    (try
      (-> (.write (.-clipboard js/navigator)
                  #js [(js/ClipboardItem. #js {"image/png" (png-blob src n)
                                               "text/html" (js/Blob. #js [(events/copy-html data src)] #js {:type "text/html"})})])
          (.catch (fn [_] nil)))
      (catch :default _ nil))))

(defn- on-copy
  "Copies the selected objects unless a text input or selected page text has the copy."
  [^js e]
  (swap! copies inc)
  (when-not (or (typing? e) (not (.-isCollapsed (js/getSelection))))
    (when-let [{:keys [data text image]} (events/copy-payload @state/app-db)]
      (.preventDefault e)
      (.setData (.-clipboardData e) events/clipboard-type data)
      (.setData (.-clipboardData e) "text/plain" text)
      ;; after the event: Firefox refuses an async write inside a handled copy event
      (when image (let [n @copies] (js/setTimeout #(write-image! image data n) 0))))))

(defn- on-paste [^js e]
  (when-not (typing? e)
    (let [^js data (.-clipboardData e)
          objs (or (events/read-copied (.getData data events/clipboard-type))
                   (events/read-copied (events/html-copied (.getData data "text/html"))))
          file (some (fn [^js item]
                       (when (and (= "file" (.-kind item)) (str/starts-with? (.-type item) "image/"))
                         (.getAsFile item)))
                     (js/Array.from (.-items data)))
          text (.getData data "text/plain")]
      (cond
        objs (do (.preventDefault e) (dispatch [:paste/objects objs]))
        file (do (.preventDefault e) (paste-image! file))
        (not (str/blank? text)) (do (.preventDefault e) (dispatch [:paste/text text]))))))

(defn- stored-theme []
  (try (let [v (js/localStorage.getItem events/theme-storage-key)]
         (some #(when (= v (name %)) %) (cons :os themes/names)))
       (catch :default _ nil)))

(defn- stored-hand? []
  (try (= "1" (js/localStorage.getItem events/hand-storage-key)) (catch :default _ false)))

(defn- on-resize [] (dispatch [:viewport (.-innerWidth js/window) (.-innerHeight js/window)]))

(defn init []
  (mount! [views/app] (root) (assoc events/initial-db :theme (stored-theme) :hand? (stored-hand?)))
  (let [dark (js/window.matchMedia "(prefers-color-scheme: dark)")]
    (.addEventListener dark "change" #(dispatch [:theme/os (.-matches %)]))
    (dispatch [:theme/os (.-matches dark)]))
  (.addEventListener js/window "hashchange" #(dispatch [:route/changed (hash-id)]))
  (.addEventListener js/window "keydown" on-keydown)
  (.addEventListener js/window "keyup" on-keyup)
  (.addEventListener js/window "copy" on-copy)
  (.addEventListener js/window "paste" on-paste)
  (.addEventListener js/window "resize" on-resize)
  (on-resize)
  (dispatch [:route/changed (hash-id)]))

(defn ^:dev/after-load reload [] (mount! [views/app] (root)))
