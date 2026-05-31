# scope-nested

The nested scope: prior bindings are visible inside, but names introduced
inside the nested block do NOT leak forward to subsequent blocks.

The first inherited block defines `outerValue`:

```scala doctest:scope=inherited
val outerValue = 50
```

The nested block sees `outerValue` but its own `nestedOnly` does not leak:

```scala doctest:scope=nested
val seen       = outerValue + 1
val nestedOnly = seen * 2
```

A subsequent isolated block cannot see `nestedOnly` (it was in a nested block).
This block declares its own value without referencing `nestedOnly`:

```scala
val independent = 42
```
