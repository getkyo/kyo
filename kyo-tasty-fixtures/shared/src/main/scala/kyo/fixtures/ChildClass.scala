package kyo.fixtures

/** Child class for cross-file inheritance fixtures.
  *
  * Extends BaseClass from a separate source file. When compiled, ChildClass.tasty contains a cross-file type reference to
  * kyo.fixtures.BaseClass, which TypeUnpickler decodes as an UnresolvedRef and the classpath orchestrator's merge pass resolves.
  */
class ChildClass extends BaseClass
