<!-- doctest:default scope=inherited -->

# per-readme-defaults

This file sets `scope=inherited` as the default for all blocks via the
`<!-- doctest:default ... -->` block at the top. Each block below
inherits bindings from all prior blocks unless overridden.

The first block introduces `counter`:

```scala
val counter = 0
```

The second block uses `counter` from the first (works because scope=inherited is the default):

```scala
val incremented = counter + 1
```

A block with an explicit per-block override (scope=isolated) re-introduces
its own `fresh` value without inheriting:

```scala doctest:scope=isolated
val fresh = 999
```
