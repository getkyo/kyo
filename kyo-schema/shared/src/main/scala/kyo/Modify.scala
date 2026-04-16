package kyo

/** Accumulates per-field changes and applies them to a value as a single unit.
  *
  * Use Modify when you want to build up multiple field mutations in a reusable, composable way and then apply them together. Navigation
  * uses focus lambdas for zero field-name conflicts.
  *
  * {{{
  * val patch = Modify[Person].set(_.name)("Bob").update(_.age)(_ + 1)
  * val updated = patch(person)
  * }}}
  *
  * @tparam Root
  *   The type being modified
  *
  * @see
  *   [[Compare]] for read-only field-by-field comparison
  * @see
  *   [[Changeset]] for a serializable, transmittable form of the changes
  */
final class Modify[Root] private[kyo] (
    private[kyo] val changes: Seq[Root => Root]
):

    /** Applies all accumulated changes to the root value, in order. */
    def applyTo(root: Root): Root = if changes.isEmpty then root else changes.foldLeft(root)((r, f) => f(r))

    /** Sets the focused field to a fixed value.
      *
      * Use Modify when you want to batch multiple field mutations into a reusable value and apply them together. For immediate,
      * single-field updates, use [[Focus.set]] instead.
      *
      * Uses a focus lambda to navigate to the field, same as Schema.focus/Compare. For sum-type focus paths, if the current variant doesn't
      * match the focused path, the operation silently returns the root unchanged. This enables safe chaining of multiple set/update calls
      * without needing to check the active variant.
      *
      * @see
      *   [[Focus.set]] for immediate, single-field updates
      */
    inline def set[V](inline f: Focus.Select[Root, Root] => Focus.Select[Root, V])(value: V): Modify[Root] =
        val root      = Focus.Select[Root]
        val navigated = f(root)
        Modify.addSet(this, navigated.getter, navigated.setter, value)
    end set

    /** Updates the focused field with a function.
      *
      * Use Modify when you want to batch multiple field mutations into a reusable value and apply them together. For immediate,
      * single-field transforms, use [[Focus.modify]] instead.
      *
      * Uses a focus lambda to navigate to the field, same as Schema.focus/Compare. For sum-type focus paths, if the current variant doesn't
      * match the focused path, the operation silently returns the root unchanged. This enables safe chaining of multiple set/update calls
      * without needing to check the active variant.
      *
      * @see
      *   [[Focus.modify]] for immediate, single-field transforms
      */
    inline def update[V](inline f: Focus.Select[Root, Root] => Focus.Select[Root, V])(fn: V => V): Modify[Root] =
        val root      = Focus.Select[Root]
        val navigated = f(root)
        Modify.addUpdate(this, navigated.getter, navigated.setter, fn)
    end update

end Modify

object Modify:

    /** Creates an empty Modify instance for type Root.
      *
      * @tparam Root
      *   The type to be modified
      * @return
      *   An empty Modify with no accumulated changes
      */
    def apply[Root]: Modify[Root] = new Modify[Root](Seq.empty)

    /** Internal helper: adds a set change to a Modify. Avoids constructing Modify in inline context. */
    private[kyo] def addSet[Root, V](
        modify: Modify[Root],
        getter: Root => Maybe[V],
        setter: (Root, V) => Root,
        value: V
    ): Modify[Root] =
        new Modify[Root](modify.changes :+ ((r: Root) =>
            getter(r) match
                case Maybe.Present(_) => setter(r, value)
                case _                => r
        ))

    /** Internal helper: adds an update change to a Modify. Avoids constructing Modify in inline context. */
    private[kyo] def addUpdate[Root, V](
        modify: Modify[Root],
        getter: Root => Maybe[V],
        setter: (Root, V) => Root,
        fn: V => V
    ): Modify[Root] =
        new Modify[Root](modify.changes :+ ((r: Root) =>
            getter(r) match
                case Maybe.Present(v) => setter(r, fn(v))
                case _                => r
        ))

end Modify
