# clj-yatf
Yet another testing framework for Clojure. This one focuses on reusability.

## Wash once, Rinse once, Test once, No repeat

Most if not all testing involves three distinct steps
- Setup
- Test
- Teardown (or Restore)

Sounds very easy, but as we get into more and more complex tests the setup and teardown sections start getting more and more elaborate with respect to the test itself which might be relatively simple. Even when testing pure functions at some point the data structures that are passed in start takig more effort to create than the test itself.

Next logical step is to refactor the setup code into reusable test harnesses. Harnesses then start depending on each other and very soon more effort is spent in creating and maintaining the test harness and their optimizations and API design than on testing the product.

In fact the effort of cleaning up is so often relegated to the back burner that most tests just assume the worse, they blow away and set up the whole environment from scratch rather than build upon a previous test, which might already have produced data or state that can simply be piped to the next test or tests.

The problem is worse for integration or system tests which assert not just the cause and effect of actions but also the state changes. Bringing the entire system to a particular state, testing a function and then dismantling the whole thing and repeating it 10,000 times is often where CPUs spend a good chunk of their teenage lives. Its no wonder continuous integration pipelines are really long when they contain system tests.

## State assertion

This approach to testing is to raise the Setup and Teardown phases to the same level of expectation that one would have on a passing Test method. If Setup completes, the environment is in a well-known state ready to test. If Test completes, the system moves to a higher state. Now everything that depends on that state, namely other tests, can run with confidence that the stack below it is working well, so they only do the delta needed from that point.

The only contract is that Teardown (or more appropriately named, Restore) will set back the state to how the test found it before it exits.

## State machine

The entire test framework thus acts like a hierarchical state machine, moving from a base state to higher and more complex nested states, each transition taken only when setup and tests successfully complete for that level. When reaching a particular level, it can execute all tests that depend on that level, before exiting or retracing. Each setup is therefore executed only once, each test only executed once and each restore only done once. An environment once created is reused for running as many tests as possible before tearing it down or modifying it. Harnesses become reusable parts of the test module itself.


## License

    Copyright (c) Roy Varghese. All rights reserved. 
    
    Licensed under the Eclipse Public License 1.0 (Same as Clojure)
