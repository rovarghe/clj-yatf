(ns clj-yatf.core-test
  (:require [clojure.pprint]
            [clojure.test :refer :all]
            [clj-yatf.core :refer :all]))

(defyat create-tables :version "0.0")
(defyat create-tables :version "1.0")

(defyat define-user)

#_(clojure.pprint/pprint @tests)

(defyat modify-user
  :dependencies [["define-user"]])

(defyat create-user :version "1.1"
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
             (println "Restoring after " (fullname current))))



(defyat delete-user
  :dependencies [ ["define-user"]])

#_(reset! tests {})

#_(defyat A)
#_(defyat P)


#_(defyat B
  :dependencies [["A"]])

#_(defyat C
  :dependencies [["B"]
                 ["A"]
                 ["P"]])


(try

  (run-yats)

  (catch Exception e
    (.printStackTrace e)))
