package kyo

import scala.annotation.implicitNotFound
import scala.language.dynamics

/** Type-safe incremental construction of case classes.
  *
  * `Builder[Root, Remaining]` tracks which required fields have not yet been set. The `Remaining` type parameter is an intersection of
  * `"fieldName" ~ FieldType` pairs for every required field that still needs a value. When all required fields are set, `Remaining` reduces
  * to `Any`, and the `.result` terminal method becomes available.
  *
  * Fields may be set in any order using dot notation. Fields with default values in the case class are optional - they are filled from
  * defaults when `.result` is called if not explicitly set. Attempting to call `.result` before all required fields are provided is a
  * compile error.
  *
  * Example:
  * {{{
  * case class Config(host: String, port: Int = 8080, debug: Boolean = false)
  *
  * // host is required (no default); port and debug are optional
  * Builder[Config].host("localhost").result
  * // Config("localhost", 8080, false)
  *
  * // Setting all fields explicitly
  * Builder[Config].host("prod").port(443).debug(true).result
  * // Config("prod", 443, true)
  *
  * // Compile error: host is still required
  * Builder[Config].port(9000).result
  * }}}
  *
  * `.result` is the terminal method that finalizes construction. It is only available when `Remaining =:= Any`, meaning all required fields
  * have been set.
  *
  * @tparam Root
  *   The case class being constructed
  * @tparam Remaining
  *   An intersection type of `"fieldName" ~ FieldType` entries for each required field not yet set. Reduces to `Any` when all required
  *   fields have been provided.
  */
final class Builder[Root, Remaining](
    private[kyo] val values: Map[String, Any],
    private[kyo] val construct: Map[String, Any] => Root
) extends Dynamic:

    /** Navigates to a named field, returning a `BuilderAt` for setting that field's value. */
    transparent inline def selectDynamic[Name <: String & Singleton](name: Name): Any =
        ${ internal.BuilderMacro.selectImpl[Root, Remaining, Name]('this, 'name) }

    /** Sets a field value directly: `builder.name("Alice")` is equivalent to `builder.name.apply("Alice")`. */
    transparent inline def applyDynamic[Name <: String & Singleton](name: Name)(value: Any): Any =
        ${ internal.BuilderMacro.applyDynamicImpl[Root, Remaining, Name]('this, 'name, 'value) }

    /** Constructs and returns the finished `Root` value.
      *
      * Only available when all required fields have been set. Fields with defaults that were not explicitly set are filled in from the case
      * class defaults. Calling this method when required fields are still missing is a compile error: the `Remaining` type in the error
      * message lists which fields still need values.
      */
    def result(using Builder.AllSet[Remaining]): Root = construct(values)

end Builder

object Builder:

    /** Evidence that all required fields have been set. Available only when `Remaining =:= Any`.
      *
      * The `@implicitNotFound` message is shown at the call site when `.result` is invoked before setting all required fields.
      */
    @implicitNotFound(
        "Cannot call .result — not all required fields have been set. Set every field on the builder before calling .result."
    )
    sealed abstract class AllSet[Remaining]

    object AllSet:
        given allSet: AllSet[Any] = new AllSet[Any] {}

    /** Creates a new empty builder for the case class `A`.
      *
      * `Remaining` is initialized to the intersection type of all required fields (fields without default values). If all fields have
      * defaults, `Remaining` is `Any` and `.result` is immediately available.
      */
    transparent inline def apply[A]: Any =
        ${ internal.BuilderMacro.applyImpl[A] }
end Builder
