package kyo.fixtures

/** Child class for cross-file inheritance fixtures used by kyo-reflect Phase 2 (UnresolvedRef resolution) tests.
  *
  * Extends BaseClass from a separate source file. When compiled, ChildClass.tasty will contain a cross-file type reference to
  * kyo.fixtures.BaseClass, which TypeUnpickler decodes as an UnresolvedRef. Phase C mergeResults resolves this placeholder.
  */
class ChildClass extends BaseClass
