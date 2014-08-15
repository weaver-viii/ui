(ns dar.ui.frp
  (:require [clojure.string :as string]))

(defmacro js-arguments [i]
  `(js/Array.prototype.slice.call js/arguments ~i))

(defmacro bind [& args]
  (let [name (if (symbol? (first args))
               [(first args)])
        [bindings & body] (if (symbol? (first args))
                            (next args)
                            args)
        bindings (partition 2 bindings)
        params (map first bindings)
        signals (map second bindings)
        array-literal (str "[" (string/join ", " (repeat (count signals) "~{}")) "]")]
    `(dar.ui.frp.core/Transform. (fn ~@name [prev# [~@params]] ~@body)
       (~'js* ~array-literal ~@signals))))

(defmacro bind* [& args]
  `(as-event (bind ~@args)))
