package kyo.test.prop.internal

/** Runtime support for macro-derived product generators.
  *
  * Kept out of the macro body so the rose-tree zipping logic is ordinary, type-checked Scala that the macro merely calls, rather than
  * splice-generated code.
  */
object GenDeriveRuntime:

    /** Zip an array of field trees into a single product tree.
      *
      *   - The node value assembles the product from each field's current value via `assemble`.
      *   - The shrinks replace one field at a time with one of that field's own immediate shrink subtrees, keeping the other fields fixed,
      *     so each field's shrinking propagates into the product.
      */
    def productTree[A](fieldTrees: Array[Tree[Any]], assemble: Array[Any] => A): Tree[A] =
        val values = Array.tabulate(fieldTrees.length)(i => fieldTrees(i).value)
        Tree(
            assemble(values),
            () =>
                LazyList
                    .from(0 until fieldTrees.length)
                    .flatMap { i =>
                        fieldTrees(i).shrinks().map { child =>
                            val replaced = fieldTrees.clone()
                            replaced(i) = child
                            productTree(replaced, assemble)
                        }
                    }
        )
    end productTree

    /** Prepend earlier-subtype candidates before the chosen subtype's own shrinks.
      *
      * When `earlier` is empty (the chosen index is 0, the base case), returns `chosen` unchanged. Otherwise constructs a new tree with
      * the same root value as `chosen`, whose shrink children are the earlier-subtype trees (in ascending index order, so the simplest
      * index-0 subtype is first and tried immediately by the greedy shrink walk) followed by `chosen`'s own shrinks.
      *
      * Ascending order is critical: the greedy shrink walk accepts the first failing candidate and recurses into it. Placing the idx-0
      * base case first means the walk reaches it immediately and does not enter a higher-index subtype's own field-shrink tree, which
      * would trap the walk before the base case is ever tried.
      *
      * Each entry in `earlier` is a thunk so that the earlier-subtype samples are deferred until the shrink walk actually traverses them.
      * This preserves lazy evaluation and prevents unnecessary sampling of earlier subtypes that may never be inspected.
      */
    def sumTree[A](chosen: Tree[A], earlier: Array[() => Tree[A]]): Tree[A] =
        if earlier.isEmpty then chosen
        else Tree(chosen.value, () => LazyList.from(earlier.indices).map(j => earlier(j)()) #::: chosen.shrinks())

end GenDeriveRuntime
