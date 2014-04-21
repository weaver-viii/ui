(ns dar.ui.frp.core)

(def ^:private counter (atom 0))

(defn new-uid []
  (swap! counter inc))

(defprotocol ISignal
  (-touch [this app])
  (-update [this app])
  (-kill [this app gen]))

(defrecord App [signals listeners events outdate])

(defrecord Signal [name uid value event?])

(defn probe [app signal]
  (-> app :signals (get (:uid signal)) :value))

(defn new-signal
  ([name value] (->Signal name (new-uid) value false))
  ([value] (new-signal nil value))
  ([] (new-signal nil)))

(defn as-event [s]
  (assoc s :event? true))

(defn new-event [& args]
  (as-event (apply new-signal args)))

(defn get-signal [app uid]
  (-> app :signals (get uid)))

(defn touch [{outdate :outdate :as app} signal]
  (let [new-outdate (conj outdate (:uid signal))]
    (if (identical? new-outdate outdate)
      app
      (-touch signal (assoc app :outdate new-outdate)))))

(defn touch-listeners [app signal]
  (reduce (fn [app uid]
            (touch app (get-signal app uid)))
          app
          (-> app :listeners (get (:uid signal)))))

(defn- register-event [app s]
  (if (:event? s)
    (update-in app [:events] conj (:uid s))
    app))

(defn- clear-events [app]
  (assoc app
    :signals (reduce (fn [m uid]
                       (if-let [s (get m uid)]
                         (assoc m uid (assoc s :value nil))
                         m))
                     (:signals app)
                     (:events app))
    :events nil))

(defn- assoc-signal [app s]
  (-> app
      (assoc-in [:signals (:uid s)] s)
      (register-event s)))

(defn- update [{outdate :outdate :as app}]
  (if-let [uid (first outdate)]
    (let [app (assoc app :outdate (disj outdate uid))]
      (recur (if-let [s (get-signal app uid)]
               (let [[s app] (-update s app)]
                 (assoc-signal app s))
               app)))
    app))

(defn push* [app signal val]
  (let [s (-> app :signals (get (:uid signal) signal) (assoc :value val))]
    (-> app (assoc-signal s) (touch-listeners s))))

(defn push [app signal val]
  (-> (push* app signal val)
      (update)
      (clear-events)))

(defn- set-conj [s v]
  (conj (or s #{}) v))

(defn pull
  ([app signal] (pull app signal nil))
  ([app {uid :uid :as signal} l]
   (let [curr (get-signal app uid)
         s (or curr signal)
         [s* app] (if-not curr
                    (-update s (assoc-in app [:signals uid] s))
                    (let [outdate (:outdate app)]
                      (if (outdate uid)
                        (-update s (assoc app :outdate (disj outdate uid)))
                        [s app])))
         app (if l
               (update-in app [:listeners uid] set-conj (:uid l))
               app)
         app (if (identical? s* s)
               app
               (assoc-signal app s*))]
     [(:value s*) app])))

(defn pull-values [app signals l]
  (reduce (fn [[vals app] s]
            (let [[val app] (pull app s l)]
              [(conj vals val) app]))
          [[] app]
          signals))

(defn kill [app {uid :uid :as signal} gen listener]
  (if (>= uid gen)
    (let [app (-> app
                  (update-in [:signals] dissoc uid)
                  (update-in [:listeners] dissoc uid))]
      (-kill signal app gen))
    (if listener
      (if-let [listeners (-> app :listeners (get (:uid listener)))]
        (assoc-in app [:listeners uid] (disj listeners (:uid listener)))
        app)
      app)))

(defn kill-many [app signals gen listener]
  (reduce #(kill %1 %2 gen listener) app signals))

(extend-protocol ISignal
  Signal
  (-update [this app] [this app])
  (-kill [{uid :uid} app gen] (update-in app [:signals] dissoc uid)))

(defrecord Transform [name uid value event? fn inputs]
  ISignal
  (-touch [this app] (touch-listeners app this))

  (-kill [this app gen] (kill-many app inputs gen this))

  (-update [this app] (let [[input-vals app] (pull-values app inputs this)
                            new-val (apply fn input-vals)
                            this (assoc this :value new-val)]
                        [this app])))

(defn lift [function]
  (fn [& inputs]
    (->Transform nil (new-uid) nil false function inputs)))

(defrecord Switch [name uid value event? input current-signal]
  ISignal
  (-touch [this app] (touch-listeners app this))

  (-kill [this app gen] (-> app
                            (kill input gen this)
                            (kill current-signal gen this)))

  (-update [this app] (let [[new-signal app] (pull app input this)]
                        (if (= (:uid new-signal) (:uid current-signal))
                          (let [[new-val app] (pull app current-signal this)
                                this (assoc this :value new-val)]
                            [this app])
                          (let [app (kill app current-signal (::gen (meta current-signal)) this)
                                [new-val app] (pull app new-signal this)
                                this (assoc this :value new-val :current-signal new-signal)]
                            [this app])))))

(defn switch [factory & inputs]
  (let [input-sf (lift (fn [& args]
                         (let [gen (new-uid)]
                           (with-meta (apply factory args)
                             {::gen gen}))))
        input (apply input-sf inputs)]
    (->Switch nil (new-uid) nil false input nil)))

(defrecord Foldp [name uid value fn input]
  ISignal
  (-touch [this app] (touch-listeners app this))

  (-kill [this app gen] (kill app input gen this))

  (-update [this app] (let [[input-val app] (pull app input this)]
                        (if (and (nil? input-val)
                                 (:event? input))
                          [this app]
                          (let [new-val (fn value input-val)
                                this (assoc this :value new-val)]
                            [this app])))))

(defn foldp [f init signal]
  (->Foldp nil (new-uid) init f signal))

(defn join
  ([x] x)
  ([x & xs] (apply (lift (fn [& xs] xs)) x xs)))

(defrecord MapSwitch [name uid value input m sm sf reduce-fn init]
  ISignal
  (-touch [this app] (touch-listeners app))

  (-kill [this app gen] (-> app
                            (kill input gen this)
                            (kill-many (map #(nth % 2) (vals sm)) gen this)))

  (-update [this app] (let [[new-m app] (pull app input this)
                            [new-sm app] (if (identical? new-m m)
                                           [sm app]
                                           (loop [app (update-in app [:outdate] conj uid)
                                                  m (transient new-m)
                                                  sm sm
                                                  sm-seq (seq sm)]
                                             (if (seq sm-seq)
                                               (let [[k [v in out]] (first sm-seq)
                                                     new-v (get m k ::nil)]
                                                 (if (= ::nill new-v)
                                                   (recur (kill app out (:uid in) this)
                                                          m
                                                          (dissoc sm k)
                                                          (next sm-seq))
                                                   (if (identical? new-v v)
                                                     (recur app
                                                            (dissoc! m k)
                                                            sm
                                                            (next sm-seq))
                                                     (recur (push* app in new-v)
                                                            (dissoc! m k)
                                                            (assoc sm k [new-v in out])
                                                            (next sm-seq)))))
                                               [(reduce (fn [sm [k v]]
                                                          (let [in (new-signal)
                                                                out (sf in)]
                                                            (assoc sm k [v in out])))
                                                        sm
                                                        m)
                                                (update-in app [:outdate] disj uid)])))
                            [new-val app] (reduce (fn [[acc app] [k s]]
                                                    (let [[v app] (pull app s this)]
                                                      [(reduce-fn acc [k v]) app]))
                                                  [init app]
                                                  new-sm)]
                        [(assoc this
                           :value new-val
                           :sm new-sm
                           :m new-m)
                         app])))

(defn map-switch
  ([sf input] (map-switch cons nil sf input))
  ([reduce-fn init sf input]
   (->MapSwitch nil (new-uid) init input nil {} sf reduce-fn init)))