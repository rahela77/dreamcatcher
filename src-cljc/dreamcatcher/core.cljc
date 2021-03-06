(ns dreamcatcher.core
  (:require #?(:clj [dreamcatcher.macros :as m])
            [dreamcatcher.util :refer (get-state-mapping get-transitions get-validators has-transition? get-transition get-states)])
  #?(:cljs
      (:require-macros [dreamcatcher.macros :as m])))

(defprotocol STM
  (state? [this] "Returns current state of instance")
  (data? [this] "Returns data state of instance")
  (stm? [this] "Returns STM reference"))

(defprotocol STMMovement
  (choices? [this]
 "Returns reachable states from current state
  of state machine instance in form of vector.

  First directly reachable states are calculated
  and set as first choices. If there are states in
  STM of this instance that are reachable from
  :any state other than choices than that states
  are also valid and are inserted after direct
  transitions.")
  (candidates? [this]
 "Returns candidate states that are valid to exit from
  current state and transit to next-state. It is not
  a guarante that it will succeed since transition can
  change state of instance so it will not pass :any to
  :next-state validation.")
  (move [this next-state]
  "Makes attempt to move machine instance to next state. Input
  argument is machine instance and 3 functions are applied.

  Order of operations is :

  1* from current state -> any state - If there is general outgoing function in current state
  2* transition fn from state -> next-state - Direct transtion between states
  3* from any state -> next-state fn - If there is general incoming function in next state

  Return value is map, that is STM instance." ))

(defprotocol STMLife
  (give-life! [this] [this life-choices]
  "Give STM instance life... If no choices
  are given, than instance is free to live on
  its own. If there are choices that restrict
  machine behaviour than that rules are applied.

  Choices are supposed to be map in form:

  {:state1 [:state2 :state3 :state1 :state1]}

  This means that if machine is in :state1 first
  choice is :state2, second choice is :state3 etc.")
  (alive? [this])
  (kill! [this])
  (act! [this]
   "Moves state machine to next state. If transition
  priority is defined with give-life! function than
  that rules are applied. Otherwise state machine
  acts free of will..."))


(defprotocol STMPath
  (to-> [this target]
  "Calculates all paths from current state
  to end state")
  (reach-state [this target]
   "Function strives to reach input state from
  current state of STM instance. Functions that are
  defined as transitions or indirect :any functions
  SHOULD NOT operate on other constructs.

  Reason is: While trying to reach certain
  state with reach-state function many transitions
  are activated and end result is first possible
  path from current state to target state with all
  transitions applied inbetween.

  Somewhat -> macro with validators."))

;; State Machine is a simple map...
;; That contains transitons from one state to another.
;; It can also contain validators that evaluate if
;; transition is valid to happen or not.

;; StateMachine generation

(defn add-state [stm state]
  (assert (not (or (= :state state) (= :state state) (= :stm state))) "[:state :data :stm] are special keys")
  (swap! stm assoc state nil))

(defn remove-state [stm state]
  (swap! stm #(dissoc % state)))

(m/add-statemachine-mapping validator ::validators)
(m/add-statemachine-mapping transition ::transitions)

(defn make-state-machine
  "Input values transitions and validators are ment to
  be sequences with recuring pattern

  [from-state to-state function from-state to-state... to-state function]

  \"function\" takes one argument and that is state
  machine instance data. Result is map."
  ([transitions] (make-state-machine transitions nil))
  ([transitions validators]
   (let [stm (atom nil)
         t (partition 3 transitions)
         v (partition 3 validators)
         states (-> (map #(take 2 %) t) flatten set)]
     (doseq [x states] (add-state stm x))
     (doseq [x t] (apply add-transition (conj x stm)))
     (doseq [x v] (apply add-validator (conj x stm)))
     @stm)))

#?(:clj
    (defmacro defstm
      "Macro defines stm with make-stat-machine function.
      STM is persistent hash-map."
      [stm-name & [transitions validators]]
      (if-not validators
        `(def ~stm-name (make-state-machine ~transitions))
        `(def ~stm-name (make-state-machine ~transitions ~validators)))))

#?(:clj
    (defmacro safe
      "Simple wrapping macro for easier
      defjob definition. Wraps body in a
      function THAT returns same argument
      that was argument. Body parts are
      evaluated thorougly."
      [& body]
      `(fn  [x#]
         (do ~@body) x#)))

#?(:clj
    (defmacro with-stm
      "Macro defines function of one argument
      with given name.

      (fn [stm-name] &body)

      Make sure that return result is transformed
      state machine instance"
      [stm-name & body]
      `(fn [~stm-name]
         (do ~@body))))

;; Machine instance is map that represents
;; "real-machine" that has real state and real data
;; and has transitions defined in stm input argument
;;
;; (atom {::state nil ::data nil ::stm nil})
(defrecord STMInstance [stm state data options]
  STM
  (stm? [_] stm)
  (state? [_] state)
  (data? [_] data))


(defn make-stm-instance
  "Constructor for makin STMInstance"
  ([stm] (->STMInstance stm nil nil nil))
  ([stm state] (->STMInstance stm state nil nil))
  ([stm state data] (->STMInstance stm state data nil))
  ([stm state data options] (->STMInstance stm state data options)))

(defn- valid-transition?
  "Computes if transition from-state to to-state is valid.
  If there is any type of validator function than function
  result decides if transition is valid. If no validator
  is provided than transition is valid.

  If validator is not a function, transition is not valid."
  [^STMInstance instance from-state to-state]
  (if-let [vf (get (::validators (get-state-mapping (stm? instance) from-state)) to-state)]
    (when (fn? vf)
      (vf instance))
    true))

(defn invalid
  "Indended to use as validator stoper."
  [_] false)

(defn make-machine-instance
  "Return STM instance with initial state. Return value is map
  with reference to ::stm and with parameters ::data and ::state
  that represents current data and current state."
  ([stm initial-state] (make-machine-instance stm initial-state nil))
  ([stm initial-state data]
   (STMInstance. stm initial-state data nil)))

;; Instance operators
;;
;; Instance has a state that can be changed with transition
;; if transition is valid.

(defn- get-reachable-states
  "Returns reachable states from positon of instance
  within state machine."
  ([^STMInstance instance]
   (get-reachable-states instance (state? instance)))
  ([^STMInstance instance state]
   (->> (keys (get-transitions (stm? instance) state))
        vec
        (remove (partial = :any)))))

(defn reset-state! [^STMInstance instance new-state]
  "Resets STM state. STM data is not changed"
  (assoc instance :state new-state))

(defn- step [^STMInstance instance from-state to-state fun]
  (when-not (nil? instance)
    (when (and
            (valid-transition? instance from-state to-state)
            (valid-transition? instance :any to-state)
            (valid-transition? instance from-state :any))
      (let [new-state (fun instance)]
        (assert (instance? STMInstance new-state) (str "Input fun takes old STMInstance and produces new STMInstance. Please asure that fn: " fun " returns STMInstance."))
        new-state))))

(defn- move-stm
  [^STMInstance instance to-state]
  (if-let [stm (stm? instance)]
    (do
      (assert (contains? stm to-state) (str "STM doesn't contain state " to-state))
      (assert
        (has-transition? stm (state? instance) to-state)
        (str "There is no transition function from state " (state? instance) " to state " to-state))
      (let [from-state (state? instance)
            tfunction (or (get-transition stm from-state to-state) identity)
            in-fun (or
                     (get-transition stm :any to-state)
                     identity)
            out-fun (or
                      (get-transition stm from-state :any)
                      identity)]
        (if-let [new-instance (-> instance
                                  (step from-state :any out-fun)
                                  (step from-state to-state tfunction)
                                  (step :any to-state in-fun))]
          (assoc new-instance :state to-state)
          instance)))
    (assert false (str "There is no state machine configured for this instance: " instance))))


(defn- get-choices
  ([^STMInstance x] (get-choices x (state? x)))
  ([^STMInstance x state]
   (if-let [fix-choices (-> x :options ::life (get state))]
     fix-choices
     (-> x stm? (get-transitions state) keys vec))))

(defn- get-valid-candidates
  ([^STMInstance x] (get-valid-candidates x (state? x)))
  ([^STMInstance x state]
   (when-let [choices (seq (get-choices x state))]
     (when (valid-transition? x state :any)
       (seq (filter #(and (valid-transition? x state %)
                          (valid-transition? x :any %)) choices))))))


;; State machine life and behaviour
(defn- move-to-next-choice [^STMInstance x]
  (let [choices (-> x :options ::life (get (state? x)))
        next-choice (first choices)]
    (if next-choice
      (let [new-state (move-stm x next-choice)
            changed-state (update-in new-state
                                     [:options ::life (state? new-state)]
                                     #(cond
                                        (vector? %) (vec (rest %))
                                        :else (concat (rest %) (take 1 %))))]
        changed-state)
      x)))



;; Graph tranversing
(defn- from->to
  [stm start end direct?]
  (assert (contains? stm end) (str "STM doesn't contain state " end))
  (if (= start end)
    nil
    (let [stm (if direct? (dissoc stm :any) stm)
          visited? (fn [path state]
                     (not= -1 (.indexOf #?(:clj path :cljs (clj->js path)) state)))
          generate-paths (fn [current-path]
                           ;(println "Current path " current-path)
                           (let [c (last current-path)]
                             #_(println "Is c=end? " (= c end))
                             (if-not (= c end)
                               (if-let [targets (seq
                                                  (concat
                                                    (-> stm (get-transitions (or c start)) keys)
                                                    (keys (get-transitions stm :any))))]
                                 (let [expanded-paths (seq (map #(if-not (visited? current-path %)
                                                                   (vec (conj current-path %))
                                                                   current-path) targets))]
                                   expanded-paths)
                                 [current-path])
                               [current-path])))]
      (loop [paths [[]]]
        (let [new-paths (reduce into #{} (map generate-paths paths))]
          (if (= new-paths paths) (->> paths
                                       (remove #(= -1 (.indexOf % #?(:clj end :cljs (clj->js end))) ))
                                       (sort-by count))
            (recur new-paths)))))))


(defn state-changed? [^STMInstance state1 ^STMInstance state2]
  (not= (select-keys state1 [:state :data :stm]) (select-keys state2 [:state :data :stm])))

(extend-type STMInstance
  STMMovement
  (choices? [this] (get-choices this))
  (candidates? [this] (get-valid-candidates this))
  (move [this next-state] (move-stm this next-state))
  STMLife
  (give-life!
    ([this choices] (let [proposed-life (reduce conj (array-map)
                                                (for [x (keys (dissoc (stm? this) :any))]
                                                  [x (get-reachable-states this x)]))]
                      (-> this
                          (assoc-in [:options ::alive?] true)
                          (assoc-in [:options ::life] proposed-life)
                          (update-in [:options ::life] merge choices))))
    ([this] (give-life! this nil)))
  (alive? [this] (get-in this [:options ::alive?]))
  (kill! [this] (assoc-in this [:options ::alive?] false))
  (act!
    ([this]
       (assert ((comp ::alive? :options) this) "Instance is not alive! First give it life...")
       (loop [new-state (move-to-next-choice this)]
         (if (state-changed? this new-state)
           new-state
           (recur (move-to-next-choice new-state))))))
  STMPath
  (to->
    ([this target]
     (from->to (stm? this) (state? this) target true)))
  (reach-state
    ([instance state]
     (let [stm (stm? instance)
           paths (to-> instance state)
           move (memoize move) ;; Do not apply transitions more than once per try
           tranverse (fn [path]
                       (loop [x instance
                              c (state? instance)
                              path-left path]
                         (if (= c state) x
                           (if-not (seq path-left) nil
                             (let [next-x (move x (first path-left))]
                               (if (= (state? next-x) c) nil
                                 (recur next-x (state? next-x) (rest path-left))))))))]
       (some tranverse paths)))))

#?(:clj
    (defmacro multimove 
      "Move STMInstance through series of states."
      [instance & states]
      (let [movements# (map #(list 'move %) states)]
        `(-> ~instance ~@movements#))))
