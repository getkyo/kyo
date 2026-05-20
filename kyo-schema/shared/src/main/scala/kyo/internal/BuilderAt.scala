package kyo.internal

import kyo.Builder

/** A builder navigation point focused on a specific field.
  *
  * Calling `apply(value)` records the field value and returns the builder with that field removed from `Remaining`.
  *
  * @tparam Root
  *   The case class being constructed
  * @tparam Remaining
  *   The current remaining fields
  * @tparam Focus
  *   The type of the field being set
  * @tparam Name
  *   The singleton string type of the field name
  */
opaque type BuilderAt[Root, Remaining, Focus, Name <: String] = Builder[Root, Remaining]

object BuilderAt:
    private[kyo] inline def wrap[Root, Remaining, Focus, Name <: String](
        b: Builder[Root, Remaining]
    ): BuilderAt[Root, Remaining, Focus, Name] = b

    extension [Root, Remaining, Focus, Name <: String](
        self: BuilderAt[Root, Remaining, Focus, Name]
    )
        /** Sets the field value. The macro generates the return type with this field removed from Remaining. */
        transparent inline def apply(value: Focus): Any =
            ${ BuilderMacro.setImpl[Root, Remaining, Focus, Name]('self, 'value) }

        private[kyo] inline def builder: Builder[Root, Remaining] = self
    end extension

end BuilderAt
