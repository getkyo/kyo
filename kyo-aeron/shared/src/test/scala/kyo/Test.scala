package kyo

// kyo-aeron test base on the kyo-test V3 framework (kyo.test.Test[S]).
// Leaves use the V3 DSL ("name" in { effectful body yielding Unit via assert });
// platform gating uses the V3 filters ("name".onlyJvm/.notJvm/.onlyJs/.onlyNative in { ... }).
abstract class Test extends kyo.test.Test[Any]
