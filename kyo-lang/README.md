# kyo-lang

`kyo-lang` compiles `.kyo` files: a Scala subset in which ordinary control-flow syntax composes typed Kyo effects. Inside a `.kyo` body an effectful expression exposes its result directly, so `val n = Var.get[Int]` binds an `Int`, while the compiled definition keeps Kyo's contract: the method returns `Int < Var[Int]`, handlers stay explicit, and an ordinary Scala caller composes the result like any other computation. The design is a language front end over Kyo, not a new effect runtime, and code mode for agents is one application of it rather than its definition.

A `.kyo` file compiles as part of a regular Scala 3 build: a scalac plugin (`kyo-lang-plugin`) prepares the unit between the parser and the typer, and a typed macro in this library validates each body and lowers it into Kyo composition during normal typing. The typer is never replaced or invoked by hand. Only admitted safe APIs are reachable from a `.kyo` body, and every unsupported construct is a compile error at its source position.

```kyo
// Counter.kyo
object Counter:
    def twice(value: Int): Int = value + value

    def increment =
        val next = Var.update[Int](x => x + 1)
        twice(next)
```

`twice` is pure and keeps the type `Int`. `increment` reads as straight-line code, and its compiled signature is `Int < Var[Int]`, inferred from the body. The equivalent handwritten Scala is what the compiler produces:

```scala
def twice(value: Int): Int = value + value

def increment: Int < Var[Int] =
    Var.update[Int](x => x + 1).map { next =>
        twice(next)
    }
```

## Writing a `.kyo` definition

A `.kyo` file holds reusable program definitions: objects, pure and effectful methods, and pure immutable values. There is no `@pure` marker and no annotation to opt in: the file extension selects the language, and a definition's purity follows from its body and the contracts of the operations it uses. Kyo's API is in scope in every `.kyo` file without an import, as if the file began with `import kyo.*`; writing the import anyway is harmless.

### Bindings bind results

Each effectful initializer is evaluated where it is written, once per execution of that binding, and every later reference reuses the bound result. A discarded result still executes its effect, and an unused failure still aborts the continuation.

```kyo
object Bindings:
    def repeated =
        val a = Var.update[Int](x => x + 1)
        a + a

    def discarded =
        Var.update[Int](x => x + 1)
        42

    def unusedFailure =
        val a = Abort.fail("failure")
        42
```

From ordinary Scala, `repeated` is `Int < Var[Int]`, `discarded` is `Int < Var[Int]`, and `unusedFailure` is `Int < Abort[String]`.

```scala
object Bindings:
    def repeated: Int < Var[Int] =
        Var.update[Int](x => x + 1).map(a => a + a)

val repeated: (Int, Int) = Var.runTuple(0)(Bindings.repeated).eval
// (1, 2): one update ran and both references saw its result
```

### Control flow selects a branch

A conditional evaluates its condition and only the selected branch. Both branches contribute to the definition's effect row, which is the intersection of the effects of every operation the body may perform.

```kyo
object Branches:
    def branch =
        if Var.get[Int] == 0 then Var.update[Int](x => x + 1)
        else Var.update[Int](x => x + 10)

    def mixed =
        val current = Var.get[Int]
        if current > 0 then Abort.fail("failure") else current + 1
```

`mixed` has no annotation and compiles to `Int < (Var[Int] & Abort[String])`. Ordinary Scala handles the effects in either order and cannot assign the result to a narrower row.

### Arguments evaluate in source order

A strict application evaluates its receiver and then its arguments in order, binding each effectful one before the call.

```kyo
object Calls:
    def pair(a: Int, b: Int) = a * 10 + b

    def ordered = pair(Var.update[Int](x => x + 1), Var.update[Int](x => x + 1))

    def length = Var.get[String].length
```

`ordered` yields `12` from an initial state of `0`, and `length` shows that methods on the exposed result remain available: the direct view of `Var.get[String]` is a `String`.

### Annotations constrain, never erase

A result annotation participates in typing as usual. An effectful annotation constrains the inferred row; a pure annotation on an effectful body, or an annotation naming a different effect, is a type error rather than a silent change of meaning.

```kyo
object Annotated:
    def annotated: Int < Var[Int] = Var.get[Int] + 1

    def annotatedMixed: Int < (Var[Int] & Abort[String]) = mixed

    def mixed =
        val current = Var.get[Int]
        if current > 0 then Abort.fail("failure") else current + 1
```

### Handlers and closures are boundaries

A handler receives the computation its argument describes, lowered on its own, and the handler's own signature says which effects remain. A lambda given to an effect-aware API such as `Kyo.foreach` returns a computation, and so does a stored or returned closure with an effectful body; the same callback given to `Chunk.map` runs through the Kyo counterpart instead.

```kyo
object Boundaries:
    def handled =
        Abort.run[String] {
            val x = Var.get[Int]
            if x < 0 then Abort.fail("negative")
            x + 1
        }

    def storedEffectful =
        val f = (x: Int) => x + Var.get[Int]
        f(1) + f(2)

    def effectAware =
        Kyo.foreach(Chunk(1, 2, 3)) { x =>
            val n = Var.get[Int]
            x + n
        }
```

`handled` is a `Result[String, Int] < Var[Int]`: the abort is handled, the state read remains. `f` is an `Int => Int < Var[Int]`, invoked twice. `effectAware` keeps `Kyo.foreach`'s API and is a `Chunk[Int] < Var[Int]`. A computation in a position with no expected type is sequenced, so a collection of computations names its element type: `Kyo.collectAll(Chunk[Int < Var[Int]](a, b))`.

## Calling `.kyo` definitions from Scala

Compiled `.kyo` definitions are ordinary Scala members with Kyo signatures. A `.scala` file in the same build, or in a dependent artifact, calls them, composes the computations, and supplies handlers. Nothing about the caller changes: it does not load the plugin, and it does not need the callee's source. The effect contracts persist in the compiled signatures across separate compilation.

```scala
def increment: Int < Var[Int] =
    Var.update[Int](x => x + 1).map(next => next + next)

val program: Int < Var[Int] = increment
val result: Int             = Var.run(0)(program).eval
// result == 2
```

The other direction holds as well: a `.kyo` definition calls admitted Scala APIs (pure methods directly, effectful methods through result binding), and a `.kyo` file compiles against the output of another `.kyo` file.

## What a body can contain

Bindings, blocks, conditionals, boolean operators, `match` with literal, binder, alternative, constructor, tuple, and typed patterns, pure custom extractors and guards, pure or effectful, `while`, `for` comprehensions over admitted collections with guards and `case` generators (an effectful body runs through the Kyo counterpart), tuples and named tuples, string interpolation, type ascription, prefix operators, applications with multiple clauses, `using` clauses, named and repeated arguments, pure by-name arguments, local methods and closures, pure or effectful, context functions, case lambdas as total functions, constructor applications, recursion, extension methods, `@tailrec`, pattern bindings as fields, pure default arguments, constant `inline val`s, `export` clauses, final case classes, sealed hierarchies with abstract members, enums, givens, conversions, derived instances, handlers and other methods taking computations, and effect-aware APIs taking effectful callbacks. Each has a test in `BodyInventoryTest` over the `Inventory.kyo` fixture, in `BodyBoundaryTest` over `Boundaries.kyo`, or in `BodyShapeTest` over `Shapes.kyo`. A trait or abstract class is sealed and a concrete class is final, so a hierarchy is closed and every implementation is checked; a field initializer may use constructor parameters and earlier fields, and nothing else of the object under construction.

## What the language rejects

Three kinds of rejection exist, and each diagnostic names the construct so the reader knows which rule applies.

| Reason | Meaning | Example |
|---|---|---|
| Language restriction | The construct conflicts with the semantic contract. | `null`, `var`, mutable field reads, an annotation erasing an effect, an effectful field or default argument. |
| Closed data policy | A class-like definition is closed and safely initialized. | An open class, an unsealed trait, a mutable constructor parameter, a parent that is not admitted, an initializer that calls a method of the object, reads a later field, or lets `this` escape. |
| Receiver without a rule | The method receiving an effectful lambda or by-name argument decides when it runs it, and it has no Kyo counterpart. | An effectful callback to a method without a counterpart, an effectful argument to an ordinary by-name parameter, a function type annotation that would hide a latent effect. |
| API admission | The resolved operation is outside the admitted surface: Kyo's API without `AllowUnsafe`, compiled `.kyo` definitions, and what the Scala compiler's safe mode considers safe in the standard library and the JDK. | `println`, `java.lang.System.currentTimeMillis`, `scala.util.Random`, a mutable collection, a method on an ordinary Scala object. |

Pending-value composition methods are deliberately unavailable in a `.kyo` body: `Var.get[Int].map(...)` is a type error because the direct view of `Var.get[Int]` is an `Int`. Generated composition uses those methods; source code does not.

## Build integration

A build loads the plugin and keeps the library on the compile classpath, then includes `.kyo` files among its sources. In sbt:

```
libraryDependencies += "io.getkyo" %% "kyo-lang" % "<version>"
addCompilerPlugin("io.getkyo" %% "kyo-lang-plugin" % "<version>")
scalacOptions += "-Xplugin-require:kyo-lang"
Compile / unmanagedSources / includeFilter := (Compile / unmanagedSources / includeFilter).value || "*.kyo"
```

`-Xplugin-require` turns a missing plugin into a compile error, so a `.kyo` file is never compiled as if it were plain Scala. The presentation compiler accepts the same plugin option, so an editor typing a `.kyo` buffer sees the direct view: completions on `val text = Var.get[String]` are `String` members.

## Status

This module carries the working subset and the complete design. [DESIGN.md](DESIGN.md) records the language objective, the semantic contract, the construct inventory with an allow/deny policy per row, the CPS lowering rules including the ones not yet implemented, the API admission rule, and the implementation sequence. The lowering is kyo-direct's: a `.kyo` body is the body of a `direct` block whose `.now` markers the plugin planted, so an effectful callback to `map`, `foldLeft`, or any other method with a Kyo counterpart already runs through `Kyo.foreach` and its relatives. Handlers inside a body, effectful closures, and effect-aware receivers are typed through the computation boundaries DESIGN.md describes. The next steps in that sequence are the remaining construct rows and default formatting with sbt and Metals integration.
