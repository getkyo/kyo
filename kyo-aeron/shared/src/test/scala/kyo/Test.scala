package kyo

/** kyo-aeron test base on the kyo-test V3 framework.
  *
  * Leaves use the V3 DSL (`"name" in { ... }`); platform gating uses `.onlyJvm`/`.notJvm`/`.onlyJs`/`.onlyNative`.
  */
abstract class Test extends kyo.test.Test[Any]
