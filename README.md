# clj-yatf
Yet another testing framework for Clojure. This one focuses on reusability.

## Stop rinsing and repeating

Most if not all testing involves three distinct steps
- Setup
- Test
- Teardown (or Restore)

Sounds very easy, but as we get into more and more complex tests the setup and teardown sections start getting more and more elaborate with respect to the test itself which might be relatively simple. Even when testing pure functions at some point the data structures that are passed in start takig more effort to create than the test itself.

Next logical step is to refactor the setup code into reusable test harnesses. Harnesses then start depending on each other and very soon more effort is spent in creating and maintaining the test harness and their optimizations and API design than on testing the product.

In fact the effort of cleaning up is so often relegated to the back burner that most tests just assume the worse, they blow away and set up the whole environment from scratch rather than build upon a previous test, which might already have produced data or state that can simply be piped to the next test or tests.

The problem is worse for integration or system tests which assert not just the cause and effect of actions but also the state changes. Bringing the entire system to a particular state, testing a function and then dismantling the whole thing and repeating it 10,000 times is often where CPUs spend a good portion of their productive lives. Its no wonder system tests are the gunk in continuous integration pipelines.

## State assertion

This approach to testing is to raise the Setup and Teardown phases to the same level of expectation that one would have on a passing Test method. If Setup completes, the environment is in a well-known state ready to test. If Test completes, the system moves to a higher state. Now everything that depends on that state, namely other tests, can run with confidence that the stack below it is working well, so they only do the delta needed from that point.

The only contract is that Teardown (or more appropriately named, Restore) will set back the state to how the test found it before it exits.

## Nested dependencies

The entire test framework thus acts like a hierarchical state machine, moving from a base state to higher and more complex nested states, each transition taken only when setup and tests successfully complete for that level. When reaching a particular level, it can execute all tests that depend on that level, before exiting or retracing. Each setup is therefore executed only once, each test only executed once and each restore only done once. An environment once created is reused for running as many tests as possible before tearing it down or modifying it. Harnesses become reusable parts of the test module itself. Tests can have multiple dependencies, each dependency establishing a particular state.


## Documentation

### Basic Usage

A test is defined with *defyat*, (for now, till I think of something clever) the name is necessary, everything else is optional with sensible defaults.

```
(defyat define-user)

(defyat create-tables
   :version "1.2")

(defyat add-user
   :dependencies [["create-tables" "1.2"]
                 ["define-user"]])
 
(run-yats)

```

A test without any code isnt very useful. Lets add some ...


```
(defyat define-user
   :setup (fn [this context] 
            (assoc context :user "john.doe"))
   :test (fn [this context] 
            (assert (= "john.doe" (:user context))))
            
(defyat create-tables
   :version "1.2"
   :setup (fn [_ context]
            (assoc context :table-name "users"))
   :test (fn [_ context]
            ;; mock table creation
            (assoc context (:table-name context)))
   :restore (fn[_ context]
            ;; drop table
            (dissoc context (:table-name context))
            
(defyat add-user
   :dependencies [["create-tables" "1.2"]
                  ["define-user"]]
   :test (fn[_ context]
           (let [table-name (:table-name context)
                 user (:user context)]
                 
             ;; simulate adding user to table
             (conj (context table-name) user)
             
             (assoc context :old context))
   :restore (fn [_ context] (:old context)))
     
```

A test *context* (hash-map) is passed to setup, test and restore methods and can be used to pass information to downstream tests. A test gets the merged context of all its dependencies.


Now lets say we want to test modifying a user. It can be built on the add-user test.

```

(defyat modify-user
    :dependencies [["add-user"]]
    :test (fn[_ context]
           (let [user (:user context)
                 table-name (:table-name context)]
                 
                 ;; test modification
                 ;;
                 
                 context))
    :restore (fn[_ context]
                ;; reset modifications
                
                context)))
                
```

   


## License

    Copyright (c) Roy Varghese. All rights reserved. 
    
    Licensed under the Eclipse Public License 1.0 (Same as Clojure)
