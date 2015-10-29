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
  (println (clojure.core/name step) " defaulted for " (fullname current))
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

#_(def ^:dynamic *results* nil)

#_(defn record [results test result]
  (swap! results assoc test result))


(defn fail
  ([] (fail "Without reason" nil))
  ([text] (fail text nil))
  ([text data]
     (throw+ {:type ::test-fail
              :fail-text text
              :fail-data data})))

#_(defn run-test [current-test results]

  (println (str "Test:" (name current-test) ":" description))
  (let [test-context {:current current-test}]
    (try
      (let [test-context (setup current-test test-context)]
        (if (not (map? test-context))
          (throw
           (ex-info (str "setup for test '" name "' should return test-context") {})))

        (if (:error test-context)
          (record results current-test [:skipped {:reason (:error test-context)}])
          (try
            (try+
              (test current-test test-context)
              (record results current-test [:pass])
              (catch [:type ::test-fail] ex
                (record results current-test [:fail (:fail-text ex) (:fail-data ex)])))


            (finally
              (restore current-test test-context))))))))



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

     (println "  " caller "->" test-name " state:"(state (tests test-name)) "->" goto-state )

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
                 tests (reduce (partial attain opts :test) tests (remove #{caller} parents))
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

             (-> current-test
                 (set-state :test-children)
                 ((partial assoc tests test-name)))))

         :restore
         (condp = current-state
           nil
           ;; Take it to restore state
           (reduce (fn [t s] (attain opts s t test-name)) tests [:setup :test :test-children :restore])

           :setup
           ;; Take it to restore state
           (reduce (fn [t s] (attain opts s t test-name)) tests [:test :test-children :restore])

           :test
           (let [tests (-> current-test
                           (set-child-state caller :restore)
                           ((partial assoc tests test-name)))]

             (reduce (fn [t s] (attain opts s t test-name)) tests [:test-children :restore]))

           :test-children
           ;; Only move to restore if all children are in restore state
           (let [tests
                 (-> current-test
                     (set-child-state caller :restore)
                     ((partial assoc tests test-name)))

                 current-test (tests test-name)

                 restored-children
                 (reduce (fn [m [c s]] (if (= s :restore) (conj m c) m))
                         #{} (child-states current-test))

                 tests
                 (if (apply not= (map count [restored-children children]))
                   ;; no-op
                   tests
                   ;; restore it

                   (let [test-context (context current-test)
                         test-context (restore current-test test-context)]

                     (-> current-test
                         (set-state :restore)
                         (set-context test-context)
                         ((partial assoc tests test-name)))))
                 opts
                 (assoc opts ::caller test-name)]

             (reduce (partial attain opts :restore) tests (remove #{caller} parents)))

           :restore
           (throw (ex-info "Shoud not happen" {}))
           #_(-> current-test
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


(defmacro deftest [test-defn]
  (if (not (name test-defn))
    (throw (ex-info "Test name missing" test-defn)))

  `(swap! tests resolve ~test-defn))



(deftest {:name "create-tables"})

(deftest {:name "define-user"})

(deftest {:name "modify-user"
          :dependencies [["define-user"]]})

(deftest {:name "create-user"
          :version "1.1"
          :dependencies [["define-user" "1.0"]
                         ["create-tables" "1.0" ]
                         ["modify-user"]]
          :description "Create a user"
          :setup
          (fn [current test-context]
            (println "Setup for " (description current) " called")
            test-context)
          :test (fn [current test-context]
                 ;; (fail)
                  (println "Testing " (fullname current)))
          :restore (fn [current test-context]
                     (println "Restoring after " (fullname current)))})



(deftest {:name "delete-user"
          :dependencies [ ["define-user"]]})

#_(println "ALL TESTS---")
#_(clojure.pprint/pprint @tests)

#_(defn SETUP [& arg]
  (println "SETUP on " arg))

#_(defn T [m v]
  (println "Called for" (first v))
  (println "Walking up" (first v))
  ((::walk-up m) m SETUP))



#_(walk-tests T)

(reset! tests {})

(deftest {:name "A"})
(deftest {:name "D"})

(deftest {:name "B"
          :dependencies [["A"]]})

(deftest {:name "C"
          :dependencies [["B"]
                         ["A"]
                         ["D"]]})

(deftest {:name "DC"
          :dependencies [["D"]]})

(try

  #_(clojure.pprint/pprint
     (attain :restore @tests "create-tables_1.0"))

  (attain :restore @tests "B_1.0")
  (println (apply str (repeat 20 "-")))

  (catch Exception e
    (.printStackTrace e)))
