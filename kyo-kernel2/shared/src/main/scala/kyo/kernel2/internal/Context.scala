package kyo.kernel2.internal

import Context.internal.*
import kyo.Tag
import kyo.bug
import kyo.kernel2.*

/** Storage for effect values used by ContextEffect.
  *
  * Context maintains a type-safe mapping between effect tags and their values. It is threaded as a parameter through every execution
  * application: bindings update it as they execute, reads consume it in one lookup, and drives supply it, starting from `empty` at the
  * roots. It is never stored in a node; it exists only in flight. It also handles isolation behavior for effects that extend
  * ContextEffect.Noninheritable. The type is public because it appears in [[Arrow.Transform]]'s run signature; its operations stay
  * internal.
  */
opaque type Context = Map[Tag[Any], AnyRef]

object Context:

    val empty: Context = Map.empty

    extension (self: Context)
        def isEmpty = self eq empty

        def contains[E <: (ContextEffect[?] | NoninheritableFlag)](tag: Tag[E]): Boolean =
            self.contains(tag.erased)

        /** Creates a new context for crossing computational boundaries.
          *
          * Uses the NoninheritableFlag to efficiently determine if filtering is needed without scanning the entire context. Only filters
          * out noninheritable effects if the flag is present.
          */
        def inherit: Context =
            if !contains(Tag[NoninheritableFlag]) then self
            else
                self.filterNot { (k, _) =>
                    k <:< Tag[NoninheritableFlag] || k <:< Tag[ContextEffect.Noninheritable]
                }

        inline def getOrElse[A, E <: ContextEffect[A], B >: A](tag: Tag[E], inline default: => B): B =
            if !contains(tag) then default
            else self(tag.erased).asInstanceOf[B]

        private[kyo] def get[A, E <: ContextEffect[A]](tag: Tag[E]): A =
            getOrElse(tag, bug(s"Missing value for context effect '${tag}'. Values: $self"))

        /** Sets a value, adding the NoninheritableFlag if the effect is noninheritable. */
        private[kernel2] def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context =
            val newContext = self.updated(tag.erased, value.asInstanceOf[AnyRef])
            if tag <:< Tag[ContextEffect.Noninheritable] then
                newContext.updated(Tag[NoninheritableFlag].erased, NoninheritableFlag)
            else
                newContext
            end if
        end set
    end extension

    object internal:
        class NoninheritableFlag
        object NoninheritableFlag extends NoninheritableFlag
end Context
