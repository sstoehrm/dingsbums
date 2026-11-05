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

(defn- modal?
  "The name dialog is open: the board behind it takes no keys or pastes."
  []
  (let [{:keys [route name naming]} @state/app-db] (events/name-dialog? route name naming)))

(defn- on-keydown [^js e]
  ;; off the board (the landing page scrolls) every key is the browser's
  (when (and (= :board (:route @state/app-db)) (not (typing? e)) (not (modal?)))
    (when-let [action (events/key-action {:key (.-key e) :ctrl? (or (.-ctrlKey e) (.-metaKey e))
                                          :shift? (.-shiftKey e) :alt? (.-altKey e) :repeat? (.-repeat e)})]
      (.preventDefault e)
      (dispatch action))))

(defn- on-keyup [^js e] (when (= " " (.-key e)) (dispatch [:space false])))

(defn- paste-svg! [{:keys [src w h]}] (dispatch [:paste/image src w h]))

(defn- paste-image! [^js file]
  (let [r (js/FileReader.)
        svg? (= "image/svg+xml" (.-type file))]
    (set! (.-onload r)
          (fn [_]
            (let [src (.-result r)]
              (if svg?
                (some-> (events/svg-image src) paste-svg!)
                (let [img (js/Image.)]
                  (set! (.-onload img) (fn [_] (dispatch [:paste/image src (.-naturalWidth img) (.-naturalHeight img)])))
                  (set! (.-src img) src))))))
    ;; an SVG as markup, to give it a size it may not have
    (if svg? (.readAsText r file) (.readAsDataURL r file))))

(defonce ^:private copies (atom 0))

(defn- png-blob
  "A promise of the image at src as a PNG, the image type every clipboard takes;
  it fails once another copy came after copy n, so a slow image can't replace it."
  [src size n]
  (js/Promise.
   (fn [ok fail]
     (let [img (js/Image.)]
       (set! (.-onload img)
             (fn [_]
               (try
                 ;; an SVG without a size of its own has none: its size on the board
                 (let [c (js/document.createElement "canvas")
                       [w h] (if (pos? (.-naturalWidth img)) [(.-naturalWidth img) (.-naturalHeight img)] (map js/Math.ceil size))]
                   (set! (.-width c) w)
                   (set! (.-height c) h)
                   (.drawImage (.getContext c "2d") img 0 0 w h)
                   (.toBlob c #(if (and % (= n @copies)) (ok %) (fail (js/Error. "no PNG"))) "image/png"))
                 ;; a tainted or too large canvas
                 (catch :default err (fail err)))))
       (set! (.-onerror img) fail)
       (set! (.-src img) src)))))

(defn- write-image!
  "Puts a copied image on the system clipboard as a picture for other apps, the
  copy riding along in its HTML. An SVG also goes as its markup, and as SVG
  where the browser can. Without the async clipboard (plain http, older
  browsers) or when it refuses, the copy event's data stays: dingsbums only."
  [{:keys [image data size]} n]
  (when (and (exists? js/ClipboardItem) (some-> js/navigator .-clipboard .-write))
    (try
      (let [svg (events/svg-text image)
            blob (fn [s t] (js/Blob. #js [s] #js {:type t}))
            types (cond-> {"image/png" (png-blob image size n)
                           "text/html" (blob (events/copy-html data image) "text/html")}
                    ;; the write replaces what the copy event put there
                    svg (assoc "text/plain" (blob svg "text/plain"))
                    ;; an unsupported type fails the whole write
                    (and svg (some-> js/ClipboardItem .-supports (.call js/ClipboardItem "image/svg+xml")))
                    (assoc "image/svg+xml" (blob svg "image/svg+xml")))]
        (-> (.write (.-clipboard js/navigator) #js [(js/ClipboardItem. (clj->js types))])
            (.catch (fn [_] nil))))
      (catch :default _ nil))))

(defn- on-copy
  "Copies the selected objects unless a text input or selected page text has the copy."
  [^js e]
  (swap! copies inc)
  (when-not (or (typing? e) (not (.-isCollapsed (js/getSelection))))
    (when-let [{:keys [data text image] :as payload} (events/copy-payload @state/app-db)]
      (.preventDefault e)
      (.setData (.-clipboardData e) events/clipboard-type data)
      (.setData (.-clipboardData e) "text/plain" text)
      ;; after the event: Firefox refuses an async write inside a handled copy event
      (when image (let [n @copies] (js/setTimeout #(write-image! payload n) 0))))))

(defn- on-paste [^js e]
  (when-not (or (typing? e) (modal?))
    (let [^js data (.-clipboardData e)
          objs (or (events/read-copied (.getData data events/clipboard-type))
                   (events/read-copied (events/html-copied (.getData data "text/html"))))
          file (some (fn [^js item]
                       (when (and (= "file" (.-kind item)) (str/starts-with? (.-type item) "image/"))
                         (.getAsFile item)))
                     (js/Array.from (.-items data)))
          text (.getData data "text/plain")
          svg (some events/svg-image [(.getData data "image/svg+xml") text])]
      (cond
        objs (do (.preventDefault e) (dispatch [:paste/objects objs]))
        ;; the vector before a picture of it
        svg (do (.preventDefault e) (paste-svg! svg))
        file (do (.preventDefault e) (paste-image! file))
        (not (str/blank? text)) (do (.preventDefault e) (dispatch [:paste/text text]))))))

(defn- stored-theme []
  (try (let [v (js/localStorage.getItem events/theme-storage-key)]
         (some #(when (= v (name %)) %) (cons :os themes/names)))
       (catch :default _ nil)))

(defn- stored-hand? []
  (try (= "1" (js/localStorage.getItem events/hand-storage-key)) (catch :default _ false)))

(defn- stored-name []
  (try (events/clean-name (js/localStorage.getItem events/name-storage-key)) (catch :default _ nil)))

(defn- on-resize [] (dispatch [:viewport (.-innerWidth js/window) (.-innerHeight js/window)]))

(defn init []
  (mount! [views/app] (root) (assoc events/initial-db :theme (stored-theme) :hand? (stored-hand?) :name (stored-name)))
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
