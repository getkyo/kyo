# visible-with-modifiers

This file exercises every modifier combination on bare blocks.

A plain block with the default modifiers (isolated, compiles, all platforms):

```scala
val plain = 42
```

A block expected to fail compilation (the type error below is intentional):

```scala doctest:expect=fails-compile
val wrong: Int = "not an int"
```

A block with scope=inherited (sees prior `plain` definition via inherited chain,
but since default is isolated this one re-declares its own value):

```scala doctest:scope=inherited
val alsoInherited = 10
```

A block with scope=nested (prior names visible but does not leak forward):

```scala doctest:scope=nested
val inNested = 99
```

A block skipped entirely (invalid syntax is not a problem):

```scala doctest:expect=skipped
this is not valid scala !!! @@@
```

A block restricted to JVM platform:

```scala doctest:platform=jvm
val jvmOnly = System.currentTimeMillis()
```
