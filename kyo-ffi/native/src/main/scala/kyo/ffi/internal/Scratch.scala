package kyo.ffi.internal

/** On Scala Native, transient allocations use `Zone { ... }` directly in generated code rather than a centralized scratch. This object
  * exists only to match the cross-platform API surface; it has no members.
  */
private[ffi] object Scratch
