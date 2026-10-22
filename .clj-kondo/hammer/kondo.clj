(ns hammer.kondo
  "Lint-time stand-ins for hammer's macros: what each name binds, not what it compiles to.")

(defn- untyped-paths
  "A vector-literal init is a db path: its value is whatever the db holds there."
  [bindings]
  (vec (mapcat (fn [[sym init]] [sym (if (vector? init) `(clojure.core/get-in nil ~init) init)])
               (partition 2 bindings))))

(defmacro defc
  "(defc name [props*] [bindings*] body+): a component taking props, with let bindings."
  [cname props bindings & body]
  `(defn ~cname ~props (let ~(untyped-paths bindings) ~@body)))

(defmacro defdraw
  "(defdraw name [props*] [bindings*] opts? draw-fn), also defloop."
  [cname props bindings & more]
  `(defn ~cname ~props (let ~(untyped-paths bindings) ~@more)))
