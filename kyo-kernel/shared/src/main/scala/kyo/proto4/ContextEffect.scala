package kyo.proto4

/** An effect that declares the need for a value provided by a handler's scope.
  *
  * Where [[ArrowEffect]] declares operations awaiting implementation, a `ContextEffect[V]` declares a value awaiting provision. Reads
  * observe the innermost binding installed by a handler; handlers install or transform bindings for a scope. Because the state is a plain
  * value with no write-back, context effects fork by copying, which is what makes them cheap to carry across execution boundaries.
  *
  * @tparam V
  *   The type of value provided by handlers of this effect
  */
abstract class ContextEffect[+V] extends Effect
