package kyo

/** A bind value paired with its [[SqlSchema]] for backend encoding.
  *
  * The construction site is type-checked: `BoundValue[A](v: A, s: SqlSchema[A])` enforces that the value and schema refer to the same type
  * `A`. At storage positions the type parameter is hidden via `BoundValue[?]`; the backend recovers the encoder by pattern-matching the
  * schema's concrete type.
  *
  * Promoted to top-level so that the static-SQL macro can emit `BoundValue(...)` constructor calls from spliced user code.
  *
  * @tparam A
  *   the type of the bound value; must have a [[SqlSchema]] instance
  */
final case class BoundValue[A](value: A, schema: SqlSchema[A])
