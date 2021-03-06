(ns dar.ui.html
  (:require [clojure.string :as string]))

(def ^:private elements
  '[a abbr address area article aside audio b base bdi bdo big blockquote body br
    button canvas caption cite code col colgroup data datalist dd del details dfn
    div dl dt em embed fieldset figcaption figure footer form h1 h2 h3 h4 h5 h6
    head header hr html i iframe img input ins kbd keygen label legend li link main
    map mark menu menuitem meta meter nav noscript object ol optgroup option output
    p param pre progress q rp rt ruby s samp script section select small source
    span strong style sub summary sup table tbody td textarea tfoot th thead time
    title tr track u ul var video wbr
    circle g line path polygon polyline rect svg text])

(defn- render-element-macro [tag [attrs & children]]
  (let [children (if (vector? (first children))
                   (ffirst children)
                   `(list ~@children))
        tag (keyword (name tag))]
    `(dar.ui/HtmlElement. ~tag ~attrs ~children)))

(defn- gen-element-macro [tag]
  `(defmacro ~(symbol (string/upper-case tag)) [& args#]
     (render-element-macro '~tag args#)))

(defmacro ^:private gen-element-macroses []
  `(do ~@(map gen-element-macro elements)))

(gen-element-macroses)
