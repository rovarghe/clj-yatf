(ns clj-yatf.core
  (:gen-class)
  (:require [slingshot.slingshot :refer [try+ throw+]]))

(defprotocol IName
  (name [this]))

(defprotocol IVersion
  (version [this]))

(defprotocol ITest
  (description [this])
  (dependencies [this])
  (setup [this context])
  (test [this context])
  (restore [this context]))

(defprotocol IState
  (level [this])
  (state [this])
  (set-state [this new-state])
  (context [this])
  (set-context [this new-context])
  (child-states [this])
  (child-state [this child-name])
  (set-child-state [this child-name new-state]))

(defprotocol ICycleState
  (next [this]))

(defn fullname [this]
  (str (name this) "_" (version this)))

(defn default-stub [step current test-context]
  (println "Running " (clojure.core/name step) " for " (fullname current))
  test-context)

(extend-type clojure.lang.IPersistentMap
  IName
  (name [this] (:name this))

  IVersion
  (version [this]
    (or (:version this) "1.0"))

  ITest
  (description [this]
    (or (:description this)
        (str "--"(name this) "--")))
  (dependencies [this]
    (or (:dependencies this) []))
  (setup [this context]
    (let [f (or (:setup this)
                (partial default-stub :setup))]
      (f this context)))
  (test [this context]
    (let [f (or (:test this)
                (partial default-stub :test))]
      (f this context)))
  (restore [this context]
    (let [f (or (:restore this)
                (partial default-stub :restore))]
      (f this context)))

  IState

  (state [this]
    (::state this))
  (set-state [this new-state]
    (assoc this ::state new-state))
  (level [this]
    (or (::level this) 0))
  (set-level [this new-level]
    (assoc this ::level new-level))
  (context [this]
    (::context this))
  (set-context [this new-context]
    (assoc this ::context new-context))
  (child-states [this]
    (or (::child-states this) {}))
  (set-child-state [this child-name new-state]
    (if (get (::children this) child-name)
      (assoc this ::child-states
             (-> (child-states this)
                 (assoc child-name new-state)))
      this))
  (child-state [this child-name]
    ((child-states this) child-name)))

(extend-type clojure.lang.IPersistentVector
  IName
  (name [this] (first this))
  IVersion
  (version [this] (or (second this) "1.0")))


(def tests (atom {}))


(defn fail
  ([] (fail "Without reason" nil))
  ([text] (fail text nil))
  ([text data]
     (throw+ {:type ::test-fail
              :fail-text text
              :fail-data data})))


(defn roots [tests]
  (reduce (fn [s v] (if (::parents (second v)) s (conj s (first v)))) #{} tests))


(defn merge-contexts [tests test-names]
  (reduce (fn [c t] (->> t tests context (merge c))) {} test-names))

(defn level-zero? [a-test]
  (= 0 (level a-test)))

(defn attain
  ([goto-state tests test-name]
     (attain {::caller nil} goto-state tests test-name))

  ([{caller ::caller :as opts} goto-state tests test-name]

     (println "  " caller "->" test-name
                " state:" (state (tests test-name)) "->" goto-state )

     (let [current-test (tests test-name)
           current-state (state current-test)
           parents (::parents current-test)
           children (::children current-test)]

       (condp = goto-state
         :setup
         (condp = current-state
           nil
           ;; To run setup, all parents must have run test
           (let [opts (assoc opts ::caller test-name)
                 ;; Dont call the caller
                 tests (reduce (partial attain opts :test)
                               tests (remove #{caller} parents))
                 test-context (merge-contexts tests parents)

                 ;; Run setup
                 test-context (setup current-test test-context)]

             ;; Update test-context and return
             (-> current-test
                 (set-state :setup)
                 (set-context test-context)
                 (set-child-state caller :setup)
                 ((partial assoc tests test-name))))

           :setup
           (-> current-test
               (set-child-state caller :setup)
               ((partial assoc tests test-name))))

         :test
         (condp = current-state
           nil
           ;; Take it through :setup and :test
           (reduce (fn [t s] (attain opts s t test-name)) tests [:setup :test])

           :setup
           (let [test-context (context current-test)
                 ;; Run test
                 test-context (test current-test test-context)]

             ;; Update test-context and return
             (-> current-test
                 (set-state :test)
                 (set-context test-context)
                 (set-child-state caller :test)
                 ((partial assoc tests test-name))))

           :test
           (-> current-test
               (set-child-state caller :test)
               #_((fn [x] (println x) x))
               ((partial assoc tests test-name))))

         :test-children
         (condp = current-state
           nil
           ;; Take it through :test and test-children
           (reduce (fn [t s] (attain opts s t test-name)) tests [:setup :test :test-children])

           :setup
           (reduce (fn [t s] (attain opts s t test-name)) tests [:test :test-children])

           :test
           (let [tests
                 (reduce (fn [c tname]
                           ;; Only test chilren that have not been setup or tested
                           ;; the others will get tested eventually as the rest of the
                           ;; graph is traversed
                           (let [current-test (c test-name)
                                 st (child-state current-test tname)]


                             (if st c
                               ;; child never initialized
                               (let [opts (assoc opts ::caller test-name)
                                     ;; Move child to :restore state
                                     c (attain opts :restore c tname)
                                     current-test (c test-name)]

                                 (-> current-test
                                     (set-child-state tname :restore)
                                     ((partial assoc c test-name))))))) tests children)
                 current-test (tests test-name)]

             ;; Testing children could result in a :restore state
             (if (= :restore (state current-test))
               ;; return
               tests
               ;; else update and return
               (-> current-test
                   (set-state :test-children)
                   ((partial assoc tests test-name))))))

         :restore
         (condp = current-state
           nil
           ;; Take it to restore state
           (reduce (fn [t s]
                     (attain opts s t test-name))
                   tests [:setup :test :test-children :restore])

           :setup
           ;; Take it to restore state
           (reduce (fn [t s]
                     (attain opts s t test-name))
                   tests [:test :test-children :restore])

           :test
           (let [tests (-> current-test
                           (set-child-state caller :restore)
                           ((partial assoc tests test-name)))]

             (reduce (fn [t s]
                       (attain opts s t test-name))
                     tests [:test-children :restore]))

           :test-children
           ;; Only move to restore if all children are in restore state
           (let [tests
                 (-> current-test
                     (set-child-state caller :restore)
                     ((partial assoc tests test-name)))

                 current-test (tests test-name)

                 restored-children
                 (reduce (fn [m [c s]] (if (= s :restore) (conj m c) m))
                         #{} (child-states current-test))]

             (if (apply not= (map count [restored-children children]))
               ;; no-op
               tests

               ;; restore it

               (let [test-context (context current-test)
                     test-context (restore current-test test-context)

                     tests
                     (-> current-test
                         (set-state :restore)
                         (set-context test-context)
                         ((partial assoc tests test-name)))

                     opts
                     (assoc opts ::caller test-name)]

                 (let [x  (reduce (partial attain opts :restore)
                                  tests (remove #{caller} parents))]
                   #_(println test-name "after calling parents " )
                   #_(clojure.pprint/pprint x)
                   x))))

           :restore
           (-> current-test
             (set-child-state caller :restore)
             ((partial assoc tests test-name))))))))


(defn resolve [roots test-defn]
  (loop [t test-defn
         r roots
         d (map fullname (dependencies test-defn))]

    (if-let [cd (first d)]
      ;; Has dependency

      (if (r cd)
        ;; dependant is fully resolved
        ;; update resolved list
        (let [t (update-in t [::parents]
                           (fn [v] (if v (conj v cd) #{cd})))]
          ;; next dependency
          (recur t r (rest d)))
        ;; else throw
        (throw
         (ex-info (str "Dependency not found for test " (fullname test-defn) )
                  {:needs cd} )))
      ;; No more dependencies
      ;; Update all the parents, with reference to t

      (do
        (->
         (reduce (fn [m,x]

                   (update-in m [x ::children]
                              (fn [x] (if x (conj x (fullname t)) #{(fullname t)}))))
                 r (::parents t))
         (assoc (fullname t) t))))))


(defmacro defyat [test-name & test-defn]

  (if (not= (type test-name) clojure.lang.Symbol)
    (throw (ex-info "Test name missing" test-name)))


  (let [test-name (clojure.core/name test-name)
        test-defn (apply hash-map test-defn)
        test-defn (assoc test-defn :name test-name)]

    `(swap! tests resolve ~test-defn)))

(defn run-yats [& opts]
  (let [opts (apply hash-map opts)
        all-tests @tests
        root-names (roots all-tests)]

    (println "Roots=" root-names)

    (reduce (fn [tests root-name]

              (let [root (tests root-name)]
                (println "For root=" root-name "state=" (state root))



                ;; Use condp, for built-in assert of invalid state
                (condp = (state root)
                      nil (attain opts :restore tests root-name)
                      :restore tests)))
            all-tests root-names)))
