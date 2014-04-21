(ns dar.ui.frp.app
  (:require [dar.ui.frp :as frp]))

(defn new-app []
  (atom (frp/->App {} {} nil #{})))

(defn push!
  ([app signal val]
   (swap! app frp/push signal val)
   nil))

(defn pull! [app signal]
  (let [[v state] (frp/pull @app signal)]
    (reset! app state)
    v))

(defn watch! [app cb]
  (add-watch app (gensym) (fn [_ _ old new]
                            (cb new old))))
