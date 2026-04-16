package kyo

/** Read-only structural comparison of two values of the same type.
  *
  * Use Compare when you need to inspect individual field differences between two values without mutating either. Navigation uses focus
  * lambdas for zero field-name conflicts: the lambda navigates to the field and the comparison is reported at that path.
  *
  * {{{
  * val d = Compare(alice, bob)
  * d.changed(_.name)    // Boolean — whether the field differs
  * d.left(_.name)       // Maybe[String] — left value of the field
  * d.right(_.name)      // Maybe[String] — right value of the field
  * d.changes            // Seq[(String, Any, Any)] — all changed fields
  * }}}
  *
  * @tparam Root
  *   The original root type (stays fixed through navigation)
  * @tparam Focus
  *   The nominal type at the current focus point
  *
  * @see
  *   [[Modify]] for accumulating and applying field mutations
  * @see
  *   [[Changeset]] for a serializable, transmittable form of the diff
  */
final class Compare[Root, Focus](
    private[kyo] val leftValue: Focus,
    private[kyo] val rightValue: Focus,
    private[kyo] val getLeft: Root => Focus,
    private[kyo] val getRight: Root => Focus
):

    /** Returns true if the left and right focused values differ. */
    def changed: Boolean = !leftValue.equals(rightValue)

    /** Returns the left focused value directly.
      *
      * When unfocused (no navigation applied), this returns the root value itself. Use `left(_.field)` to extract a specific field.
      */
    def left: Focus = leftValue

    /** Returns the right focused value directly.
      *
      * When unfocused (no navigation applied), this returns the root value itself. Use `right(_.field)` to extract a specific field.
      */
    def right: Focus = rightValue

    /** Returns true if the focused field differs between left and right.
      *
      * Uses a focus lambda to navigate to the field, avoiding field-name conflicts with Compare's own methods.
      */
    inline def changed[V](inline f: Focus.Select[Focus, Focus] => Focus.Select[Focus, V]): Boolean =
        val root      = Focus.Select[Focus]
        val navigated = f(root)
        val l         = navigated.getter(leftValue)
        val r         = navigated.getter(rightValue)
        (l, r) match
            case (Maybe.Present(lv), Maybe.Present(rv)) => !lv.equals(rv)
            case (Maybe.Absent, Maybe.Absent)           => false
            case _                                      => true
        end match
    end changed

    /** Returns the left value at the focused path, wrapped in `Maybe`.
      *
      * For product fields the result is always `Present(value)`. For sum-type variant paths, the result is `Present(value)` when the active
      * variant matches, or `Absent` when it doesn't. Uses a focus lambda to navigate to the field.
      */
    inline def left[V](inline f: Focus.Select[Focus, Focus] => Focus.Select[Focus, V]): Maybe[V] =
        val root      = Focus.Select[Focus]
        val navigated = f(root)
        navigated.getter(leftValue)
    end left

    /** Returns the right value at the focused path, wrapped in `Maybe`.
      *
      * For product fields the result is always `Present(value)`. For sum-type variant paths, the result is `Present(value)` when the active
      * variant matches, or `Absent` when it doesn't. Uses a focus lambda to navigate to the field.
      */
    inline def right[V](inline f: Focus.Select[Focus, Focus] => Focus.Select[Focus, V]): Maybe[V] =
        val root      = Focus.Select[Focus]
        val navigated = f(root)
        navigated.getter(rightValue)
    end right

    /** Returns a list of all changed fields as (fieldName, leftValue, rightValue) triples.
      *
      * Requires Fields[Focus] to iterate the field descriptors at runtime. Only includes fields whose values differ.
      */
    def changes(using fields: Fields[Focus], ev: Focus <:< Product): Seq[(String, Any, Any)] =
        val lProduct = ev(leftValue)
        val rProduct = ev(rightValue)
        fields.fields.zipWithIndex.collect {
            case (field, idx) if !lProduct.productElement(idx).equals(rProduct.productElement(idx)) =>
                (field.name, lProduct.productElement(idx), rProduct.productElement(idx))
        }
    end changes

end Compare

object Compare:

    /** Creates a structural comparison of two values.
      *
      * Entry point replacing `Schema.diff(left, right)`.
      *
      * @param left
      *   The left value to compare
      * @param right
      *   The right value to compare
      * @return
      *   A Compare instance for field-by-field comparison
      */
    def apply[A](left: A, right: A): Compare[A, A] = new Compare[A, A](left, right, identity, identity)

end Compare
