(ns dingsbums.themes
  "The color themes: parchment, the default, and the same twelve as simpleviz
  (server/themes.cljc there), trimmed to the keys dingsbums paints with. A
  theme is colours only; type, radii and borders are the stylesheet's.
  Palette credits: THIRD-PARTY-NOTICES.md.")

(def names
  "The themes, in menu order."
  [:parchment :light :dark :print :high-contrast :blueprint :paper
   :solarized-light :solarized-dark :nord :dracula :carbonfox :one-dark])

(def css-keys
  "The keys mirrored into CSS custom properties, as --<key>."
  [:bg :panel :panel-border :panel-divider :text :text-strong :text-muted :accent :on-accent])

(def themes
  "Name -> theme. Canvas: :text-strong (lines and text objects), :sub
  (frames), :accent and :panel (selection), :text-dim (locked selection),
  :hover-plain (loading image)."
  {:parchment
   {:bg "#f6f1e6" :panel "#ffffff" :panel-border "#cdd5c8" :panel-divider "#e4e8df"
    :text "#23241f" :text-strong "#23241f" :text-muted "#63675e" :text-dim "#8a8d84"
    :hover-plain "#eee8da" :accent "#41a1cf" :on-accent "#ffffff"
    :sub "#b8c1b2"}

   :light
   {:bg "#fafafa" :panel "#fff" :panel-border "#ddd" :panel-divider "#eee"
    :text "#374151" :text-strong "#000" :text-muted "#6b7280" :text-dim "#9ca3af"
    :hover-plain "#f0f0f0" :accent "#2563eb" :on-accent "#fff"
    :sub "#888"}

   :dark
   {:bg "#111827" :panel "#1f2937" :panel-border "#374151" :panel-divider "#374151"
    :text "#d1d5db" :text-strong "#fff" :text-muted "#9ca3af" :text-dim "#6b7280"
    :hover-plain "#374151" :accent "#60a5fa" :on-accent "#fff"
    :sub "#9ca3af"}

   :print
   {:bg "#ffffff" :panel "#ffffff" :panel-border "#cccccc" :panel-divider "#e5e5e5"
    :text "#222222" :text-strong "#000000" :text-muted "#555555" :text-dim "#888888"
    :hover-plain "#eeeeee" :accent "#000000" :on-accent "#ffffff"
    :sub "#666666"}

   :high-contrast
   {:bg "#ffffff" :panel "#ffffff" :panel-border "#000000" :panel-divider "#000000"
    :text "#000000" :text-strong "#000000" :text-muted "#333333" :text-dim "#555555"
    :hover-plain "#e6e6e6" :accent "#0040ff" :on-accent "#ffffff"
    :sub "#333333"}

   :blueprint
   {:bg "#0d3b66" :panel "#11487a" :panel-border "#3d6f9e" :panel-divider "#2a5d8c"
    :text "#d7ecff" :text-strong "#ffffff" :text-muted "#a9cbe8" :text-dim "#7fa7cc"
    :hover-plain "#1a5a91" :accent "#7fdbff" :on-accent "#0d3b66"
    :sub "#a9cbe8"}

   :paper
   {:bg "#f7f1e3" :panel "#fbf7ee" :panel-border "#d9ccb0" :panel-divider "#e8dfca"
    :text "#4a3b2a" :text-strong "#2b1f12" :text-muted "#7a6650" :text-dim "#a39075"
    :hover-plain "#ede3cf" :accent "#8b4513" :on-accent "#ffffff"
    :sub "#8a7558"}

   ;; Solarized, © 2011 Ethan Schoonover (MIT)
   :solarized-light
   {:bg "#fdf6e3" :panel "#fdf6e3" :panel-border "#93a1a1" :panel-divider "#eee8d5"
    :text "#586e75" :text-strong "#073642" :text-muted "#657b83" :text-dim "#93a1a1"
    :hover-plain "#eee8d5" :accent "#268bd2" :on-accent "#ffffff"
    :sub "#657b83"}

   :solarized-dark
   {:bg "#002b36" :panel "#073642" :panel-border "#586e75" :panel-divider "#0d4452"
    :text "#93a1a1" :text-strong "#eee8d5" :text-muted "#839496" :text-dim "#586e75"
    :hover-plain "#0d4452" :accent "#268bd2" :on-accent "#002b36"
    :sub "#839496"}

   ;; Nord, © 2016-present Sven Greb (MIT)
   :nord
   {:bg "#2e3440" :panel "#3b4252" :panel-border "#4c566a" :panel-divider "#434c5e"
    :text "#d8dee9" :text-strong "#eceff4" :text-muted "#a6b0c3" :text-dim "#7b88a1"
    :hover-plain "#434c5e" :accent "#88c0d0" :on-accent "#2e3440"
    :sub "#a6b0c3"}

   ;; Dracula, © 2023 Dracula Theme (MIT)
   :dracula
   {:bg "#282a36" :panel "#343746" :panel-border "#44475a" :panel-divider "#44475a"
    :text "#f8f8f2" :text-strong "#ffffff" :text-muted "#b6b9cc" :text-dim "#6272a4"
    :hover-plain "#44475a" :accent "#ff79c6" :on-accent "#282a36"
    :sub "#b6b9cc"}

   ;; carbonfox from nightfox.nvim, © 2021 James Simpson (MIT)
   :carbonfox
   {:bg "#161616" :panel "#202020" :panel-border "#353535" :panel-divider "#2a2a2a"
    :text "#f2f4f8" :text-strong "#ffffff" :text-muted "#b6b8bb" :text-dim "#7b7c7e"
    :hover-plain "#2a2a2a" :accent "#78a9ff" :on-accent "#161616"
    :sub "#b6b8bb"}

   ;; Atom One Dark, © GitHub Inc. (MIT)
   :one-dark
   {:bg "#282c34" :panel "#21252b" :panel-border "#3e4451" :panel-divider "#333842"
    :text "#abb2bf" :text-strong "#d7dae0" :text-muted "#828997" :text-dim "#5c6370"
    :hover-plain "#333842" :accent "#61afef" :on-accent "#282c34"
    :sub "#828997"}})

(defn pref?
  "A theme name, or :os (light or dark as the OS has it)."
  [pref]
  (or (= :os pref) (contains? themes pref)))

(defn effective
  "The theme to paint for pref: :os light or dark as the OS has it, a name
  that theme, nil (or anything else) parchment."
  [pref os-dark?]
  (if (= :os pref)
    (themes (if os-dark? :dark :light))
    (themes pref (:parchment themes))))
