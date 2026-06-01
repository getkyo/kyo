# scope-env-named

Named environment scopes. Blocks in `env:tutorial` share one scope.
Blocks in `env:other` form a separate scope.

Tutorial scope, first block:

```scala doctest:scope=env:tutorial
val tutorialStep1 = "step one"
```

Tutorial scope, second block (sees `tutorialStep1`):

```scala doctest:scope=env:tutorial
val tutorialStep2 = tutorialStep1 + " done"
```

Other scope, isolated from the tutorial:

```scala doctest:scope=env:other
val otherValue = 999
```
