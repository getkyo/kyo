![kyo](https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png)
-------------------

![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)
![Chat](https://img.shields.io/discord/1087005439859904574)
<!---![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)--->

### This readme is a working in progress. If you'd like to try Kyo, please build from source.

Kyo is a complete toolkit for Scala development, spanning from browser-based apps in ScalaJS to high-performance backends on the JVM. It introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm. Unlike similar solutions, Kyo achieves this without inundating developers with esoteric concepts from Category Theory or using cryptic symbolic operators, making for a development experience that's as intuitive as it is robust.

Drawing inspiration from ZIO's effect rotation, Kyo takes a more generalized approach. While ZIO restricts effects to two channels, dependency injection and short-circuiting, Kyo allows for an arbitrary number of effectful channels. This enhancement affords developers greater flexibility in effect management, while also simplifying Kyo's internal codebase through more principled design patterns.

## The `>` type

In Kyo, computations are expressed via the infix type `>`, which takes two parameters:

1. The first parameter specifies the type of the expected output.
2. The second parameter lists the pending effects that must be handled, represented as an **unordered** type-level set via a type interssection.

```scala 
import kyo._

// Expect an 'Int' after handling 
// the 'Options' effect
Int > Options

// Expect a 'String' after handling 
// both 'Options' and 'IOs' effects
String > (Options with IOs)
```

> Note: effect types follow a naming convention, which is the plural form of the functionalities they manage.

Kyo is designed so that any type `T` is automatically a `T > Any`, where `Any` signifies an empty set of pending effects.

```scala
import kyo._

// An 'Int' is also an 'Int > Any'
val a: Int > Any = 1

// Since there are no pending effects, 
// the computation can produce a pure value
val b: Int = a.pure
```

It's possible to directly extract the pure value from a computation marked as `T > Any`. The given example essentially signifies a computation that yields an `Int` without any pending effects. Therefore, it's possible to safely extract the value.

This property removes the need to distinguish between `map` and `flatMap`, as values are automatically lifted to a Kyo computation with no pending effects.

```scala
import kyo.options._
import kyo.tries._

// Kyo still supports both `map` and `flatMap`
def example1(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.flatMap(v => b.map(_ + v))

// but using only `map` is recommended 
def example2(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.map(v => b.map(_ + v))
```

The `map` method in Kyo has the ability to automatically update the set of pending effects. When you apply `map` to computations that have different sets of pending effects, Kyo reconciles these into a new computation type that combines all the unique pending effects from both operands.

## Effect widening

Kyo's set of pending effects is a contravariant type parameter. This encoding permits computations to be widened to encompass a larger set of effects.

```scala
// An 'Int' with an empty effect set (`Any`)
val a: Int > Any = 
  1

// Widening the effect set from empty (`Any`) 
// to include `Options`
val b: Int > Options = 
  a

// Further widening the effect set to include 
// both `Options` and `Tries`
val c: Int > (Options with Tries) = 
  b

// Directly widening a pure value to have 
// `Options` and `Tries`
val d: Int > (Options with Tries) = 
  42
```

This contravariant encoding enables a fluent API for effectful code. Methods can accept parameters with a specific set of pending effects while also permitting those with fewer or no effects.

```scala
// The function expects a parameter with both 
// 'Options' and 'Tries' effects pending
def example1(v: Int > (Options with Tries)) = 
  v.map(_ + 1)

// A value with only the 'Tries' effect can be 
// automatically widened to include 'Options'
def example2(v: Int > Tries) = 
  example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

Here, `example1` is designed to accept an `Int > (Options with Tries)`. However, thanks to the contravariant encoding of the type-level set of effects, `example2` and `example3` demonstrate that you can also pass in computations with a smaller set of effects—or even a pure value—and they will be automatically widened to fit the expected type.

## Using effects

Kyo offers a modular approach to effect management, accommodating both built-in and user-defined effects organized in `object` modules. This organization ensures a consistent API and allows developers to focus on building complex applications without worrying about effect management intricacies.

Importing the corresponding module into scope brings in the effect and any additional implicits it may need. The naming convention uses lowercase module names for each effect type.

```scala
// for 'Options' effect
import kyo.options._
// for 'Tries' effect
import kyo.tries._ 
```

For effects that support it, a `get` method is provided, which permits the "extraction" of the underlying value from a container type.

```scala
// Retrieve an 'Int' tagged with 'Options'
val a: Int > Options = 
  Options.get(Some(1))
```

Effect handling is done using the `run` method. Though it's named `run`, the operation doesn't necessarily execute the computation immediately, as the effect handling can also be suspended if another effect is pending.

```scala
// Handle the 'Options' effect
val b: Option[Int] > Any = 
  Options.run(a)

// Retrieve pure value as there are no more pending effects
val c: Option[Int] = 
  b.pure
```

The order in which you handle effects in Kyo can significantly influence both the type and value of the result. Since effects are unordered at the type level, the runtime behavior depends on the sequence in which effects are processed.

```scala
import scala.util._

def optionsFirst(a: Int > (Options with Tries)): Try[Option[Int]] = {
  val b: Option[Int] > Tries = 
    Options.run(a)
  val c: Try[Option[Int]] > Any = 
    Tries.run(b)
  c.pure
}
def triesFirst(a: Int > (Options with Tries)): Option[Try[Int]] = {
  val b: Try[Int] > Options =
    Tries.run(a)
  val c: Option[Try[Int]] > Any = 
    Options.run(b)
  c.pure
}
```

In this example, the order in which effects are handled significantly influences the outcome, particularly when the effects have the ability to short-circuit the computation:

```scala
val ex = new Exception

// If the effects don't short-circuit, only the 
// order of nested types in the result changes
assert(optionsFirst(Options.get(Some(1))) == Success(Some(1)))
assert(optionsFirst(Tries.get(Success(1))) == Success(Some(1)))

// Note how the result type changes from 
// 'Try[Option[T]]' to 'Option[Try[T]]'
assert(triesFirst(Options.get(Some(1))) == Some(Success(1)))
assert(triesFirst(Tries.get(Success(1))) == Some(Success(1)))

// If there's short-circuiting, the 
// resulting value can be different
assert(optionsFirst(Options.get(None)) == Success(None))
assert(optionsFirst(Tries.get(Failure(ex))) == Failure(ex))

assert(triesFirst(Options.get(None)) == None)
assert(triesFirst(Tries.get(Failure(ex))) == Some(Failure(ex)))
```

## Core Effects

### Aborts: Short Circuiting

The `Aborts` effect is a generic implementation for short-circuiting effects. It's equivalent to ZIO's failure channel.

```scala
import kyo.aborts._

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int > Aborts[String] = 
  Aborts[String].get(Right(1))

// short-circuiting via 'Left'
val b: Int > Aborts[String] = 
  Aborts[String].get(Left("failed!"))

// short-circuiting via 'Fail'
val c: Int > Aborts[String] = 
  Aborts[String].fail("failed!")

// 'catching' automatically catches exceptions
val d: Int > Aborts[Exception] = 
  Aborts[Exception].catching(throw new Exception)
```

> Note that `Aborts` effect has a type parameter and its methods can only be accessed if the type parameter is provided.

### IOs: Side Effects

Kyo is unlike traditional effect systems since its base type `>` **does not** assume that the computation can perform side effects. The `IOs` effect is introduced whenever a side effect needs to be performed.

```scala
import kyo.ios._

// 'apply' is used to suspend side effects
val a: Int > IOs = 
  IOs(Random.nextInt)

// 'value' is a shorthand to widen 
// a pure value to IOs
val b: Int > IOs = 
  IOs.value(42)

// 'fail' is returns a computation that 
// will fail once IOs is handled
val c: Int > IOs = 
  IOs.fail(new Exception)
```

> Note: Kyo's effects and public APIs are designed so any side effect is properly suspended via `IOs`, providing safe building blocks for pure computations.

Users shouldn't typically handle the `IOs` effect directly since it triggers the execution of side effects, which breaks referential transparency. Prefer `kyo.App` instead.

In some specific cases where Kyo isn't used as the main effect system of an application, it might make sense for the user to handle the `IOs` effect directly. The `run` method can only be used if `IOs` is the only pending effect.

```scala
val a: Int > IOs = 
  IOs(42)

// ** Avoid 'IOs.run' this, use 'kyo.App' instead) **
val b: Int = 
  IOs.run(a).pure
// ** Avoid 'IOs.run' this, use 'kyo.App' instead) **
```

The `runLazy` method accepts computations with other effects but it doesn't guarantee that all side effects are performed before the method returns. If other effects still have to be handled, the side effects can be executed later once the other effects are handled. This a low-level API that must be used with caution.

```scala
// Computation with 'Options' and then 
// 'IOs' suspensions
val a: Int > (Options with IOs) = 
  Options.get(Some(42)).map { v => 
    IOs { 
      println(v)
      v
    }
  }

// ** Avoid 'IOs.runLazy' this, use 'kyo.App' instead) **
// Handle the 'IOs' effect lazily
val b: Int > Options = 
  IOs.runLazy(a)
// ** Avoid 'IOs.runLazy' this, use 'kyo.App' instead) **

// Since the computation is suspended withe 
// 'Options' effect first, the lazy IOs execution 
// will be triggered once 'Options' is handled
val c: Option[Int] = 
  Options.run(b).pure
```

> IMPORTANT: Avoid handling the `IOs` effect directly since it breaks referential transparency. Use `kyo.App` instead.

### Envs: Dependency Injection

The `Envs` effect is similar to ZIO's environment mechanism but with a more flexible scoping since values can be provided individually. `Envs` doesn't provide a solution like ZIO's layers, though. The user is responsible for initializing environment values, like services, in parallel, for example.

```scala
import kyo.envs._

// Given an interface
trait Database {
  def count: Int > IOs 
}

// The 'Envs' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database > Envs[Database] = 
  Envs[Database].get

// Use the 'Database' to obtain the count
val b: Int > (Envs[Database] with IOs) = 
  a.map(_.count)

// A 'Database' mock implementation
val db = new Database { 
  def count = 1
}

// Handle the 'Envs' effect with the mock database
val c: Int > IOs = 
  Envs[Database].run(db)(b)
```

A computation can also require multiple values from its environment.

```scala
// A second interface to be injected
trait Cache {
  def clear: Unit > IOs
}

// A computation that requires two values
val a: Unit > (Envs[Database] with Envs[Cache] with IOs) = 
  Envs[Database].get.map { db =>
    db.count.map {
      case 0 => 
        Envs[Cache].get.map(_.clear)
      case _ => 
        ()
    }
  }
```

### Locals: Scoped Values

The `Locals` effect operates on top of `IOs` and enables the definition of scoped values. This mechanism is typically used to store contextual information of a computation. For example, in request processing, locals can be used to store the user who performed the request. In a library for database access, locals can be used to propagate transactions.

```scala
import kyo.locals._

// Locals need to be initialized with a default value
val myLocal: Local[Int] = 
  Locals.init(42)

// The 'get' method returns the current value of the local
val a: Int > IOs = 
  myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This code produces 43 (42 + 1)
val b: Int > IOs =
  myLocal.let(42)(a.map(_ + 1))
```

> Note: Kyo's effects are designed so locals are properly propagated. For example, they're automatically inherited by forked computations in `Fibers`.

### Resources: Resource Safety

The `Resources` effect handles the safe use of external resources like network connections, files, and any other resource that needs to be freed once the computation finalizes. It's a mechanism similar to ZIO's `Scope`.

```scala
import kyo.resources._
import java.io.Closeable

class Database extends Closeable {
  def count: Int > IOs = 42
  def close() = {}
}

// The `acquire` method accepts any object that implements Java's 
// `Closeable` interface
val db: Database > (Resources with IOs) = 
  Resources.acquire(new Database)

// Use `run` to handle the effect and close the resources 
// utilized by a computation
val b: Int > IOs = 
  Resources.run(db.map(_.count))
```

The `ensure` method is a more low-level API to allow users to handle the finalization of resources directly. The `acquire` method is implemented in terms of `ensure`.

```scala
// Example method to execute a function on a database
def withDb[T](f: Database => T > IOs): T > (IOs with Resources) =
  // Initializes the database ('new Database' is a placeholder)
  IOs(new Database).map { db =>
    // Registers `db.close` to be finalized
    Resources.ensure(db.close).map { _ =>
      // Invokes the function
      f(db)
    }
  }

// Execute a function
val a: Int > (IOs with Resources) =
  withDb(_.count)

// Close resources
val b: Int > IOs = 
  Resources.run(a)
```

### Choices: Decision Making and Exploration

The Choices effect is designed to aid in handling and exploring multiple options, pathways, or outcomes in a computation. This effect is particularly useful in scenarios where you're dealing with decision trees, backtracking algorithms, or any situation that involves dynamically exploring multiple options.

```scala
import kyo.choices._

// Evaluate each of the provided choices.
// Note how 'foreach' takes a 'List[T]'
// returns a 'T > Choices'
val a: Int > Choices =
  Choices.foreach(List(1, 2, 3, 4))

// 'dropIf' discards the current choice if 
// a condition is not met. Produces a 'List(1, 2)'
val b: Int > Choices =
  a.map(v => Choices.dropIf(v > 2).map(_ => v))

// 'drop' unconditionally discards the 
// current choice. Produces a 'List(42)'
val c: Int > Choices = 
  b.map {
    case 1 => 42
    case _ => Choices.drop
  }

// Handle the effect to evaluate all choices 
// and return a 'List' with the results
val d: List[Int] > Any =
  Choices.run(c)
```

The Choices effect becomes exceptionally powerful when combined with other effects. This allows you not just to make decisions or explore options in isolation but also to do so in contexts that might involve asynchronicity, resource management, or even user interaction.

### Aspects: Aspect-Oriented Programming (AOP)

The `Aspects` effect provides a mechanism for users to customize the behavior of a computation from an indirect scope. Aspects in Kyo are expressed as first-class values, which enables flexible scoping. For example, users may instantiate aspects and reduce their visibility via regular field modifiers.

To instantate an aspect, use the `Aspects.init` method. It takes three type parameters:

1. `T`: The input type of the aspect
2. `U`: The output type of the aspect
3. `S`: The effects the aspect may perform

```scala
import kyo.aspects._

// Initialize an aspect that takes a 'Database' and returns
// an 'Int', potentially performing 'IOs' effects
val countAspect: Aspect[Database, Int, IOs] = 
  Aspects.init[Database, Int, IOs]

// The method 'apply' activates the aspect for a computation
def count(db: Database): Int > (Aspects with IOs) =
  countAspect(db)(_.count)

// To bind an aspect to an implementation, first create a new 'Cut'
val countPlusOne =
  new Cut[Database, Int, IOs] {
    // The first param is the input of the computation and the second is
    // the computation being handled
    def apply[S](v: Database > S)(f: Database => Int > (Aspects with IOs)) =
      v.map(db => f(db).map(_ + 1))
  }

// Bind the 'Cut' to a computation with the 'let' method.
// The first param is the 'Cut' and the second is the computation
// that will run with the custom binding of the aspect
def example(db: Database): Int > (Aspects with IOs) =
  countAspect.let(countPlusOne) {
    count(db)
  }
```

If an aspect is bind to multiple `Cut` implementations, the order in which they're executed follows the order they're scoped in the computation.

```scala
// Another 'Cut' implementation
val countTimesTen =
  new Cut[Database, Int, IOs] {
    def apply[S](v: Database > S)(f: Database => Int > (Aspects with IOs)) =
      v.map(db => f(db).map(_ * 10))
  }

// First bind 'countPlusOne' then 'countTimesTen'
// the result will be (db.count + 1) * 10
def example1(db: Database) =
  countAspect.let(countPlusOne) {
    countAspect.let(countTimesTen) {
      count(db)
    }
  }

// First bind 'countTimesTen' then 'countPlusOne'
// the result will be (db.count * 10) + 1
def example2(db: Database) =
  countAspect.let(countTimesTen) {
    countAspect.let(countPlusOne) {
      count(db)
    }
  }

// Cuts can also be composed via `andThen`
def example3(db: Database) =
  countAspect.let(countTimesTen.andThen(countPlusOne)) {
    count(db)
  }
```

### Options: Optional Values

```scala
import kyo.options._

// 'get' to "extract" the value of an 'Option'
val a: Int > Options = 
  Options.get(Some(1))

// 'apply' is the effectful version of 'Option.apply'
val b: Int > Options = 
  Options(1)

// If 'apply' receives a 'null', it's equivalent to 'Options.get(None)'
assert(Options.run(Options(null)) == Options.run(Options.get(None)))

// Effectful version of `Option.getOrElse`
val c: Int > Options = 
  Options.getOrElse(None, 42)

// Effectful verion of 'Option.orElse'
val d: Int > Options = 
  Options.getOrElse(Some(1), c)
```

### Tries: Exception Handling

```scala
import kyo.tries._

// 'get' to "extract" the value of an 'Try'
val a: Int > Tries = 
  Tries.get(Try(1))

// 'fail' to short-circuit the computation
val b: Int > Tries = 
  Tries.fail(new Exception)

// 'fail' has an overload that takes an error message
val c: Int > Tries = 
  Tries.fail("failed")

// 'apply' is the effectful version of 'Try.apply'
val d: Int > Tries = 
  Tries(1)

// 'apply' automatically catches exceptions.
val e: Int > Tries = 
  Tries(throw new Exception)
```

### Consoles: Console Interaction

```scala
import kyo.consoles._

// Read a line from the console
val a: String > Consoles = 
  Consoles.readln

// Print to the stdout
val b: Unit > Consoles = 
  Consoles.print("ok")

// Print to the stdout with a new line
val c: Unit > Consoles = 
  Consoles.println("ok")

// Print to the stderr
val d: Unit > Consoles = 
  Consoles.printErr("fail")

// Print to the stderr with a new line
val e: Unit > Consoles = 
  Consoles.printlnErr("fail")

// Run with the default implicit 'Console' implementation
val f: Unit > IOs = 
  Consoles.run(e)

// Explicitly specifying the 'Console' implementation
val g: Unit > IOs = 
  Consoles.run(Console.default)(e)
```

> Note how `Consoles.run` returns a computation with the `IOs` effect pending, which ensures the implementation of `Consoles` is pure.

### Clocks: Time Management

```scala
import kyo.clocks._
import java.time.Instant

// Obtain the current time
val a: Instant > Clocks = 
  Clocks.now

// Run with default 'Clock'
val b: Instant > IOs = 
  Clocks.run(a)

// Run with an explicit 'Clock'
val c: Instant > IOs = 
  Clocks.run(Clock.default)(a)
```

### Randoms: Random Values

```scala
import kyo.randoms._

// Generate a random 'Int'
val a: Int > Randoms =  Randoms.nextInt

// Generate a random 'Int' within a bound
val b: Int > Randoms = Randoms.nextInt(42)

// A few method variants
val c: Long > Randoms = Randoms.nextLong
val d: Double > Randoms = Randoms.nextDouble
val e: Boolean > Randoms = Randoms.nextBoolean
val f: Float > Randoms = Randoms.nextFloat
val g: Double > Randoms = Randoms.nextGaussian

// Obtain a random value from a sequence
val h: Int > Randoms = 
  Randoms.nextValue(List(1, 2, 3))
```

### Loggers: Logging

```scala
import kyo.loggers._

// Initialize a 'Logger' instance
val a: Logger = 
  Loggers.init("exampleLog")

// It's also possible to specify a class
val b: Logger =
  Loggers.init(this.getClass)

// A 'Logger' provides trace, debug, info, 
// warn, and error method variants. Example:
val c: Unit > IOs = 
  b.error("example")

// Each variant also has a method overload
// that takes a 'Throwable' as a second param
val d: Unit > IOs = 
  b.error("example", new Exception)
```

> Important: The `Loggers` effect chooses to consider the initialization of a `Logger` instance as being pure even though it may perform side effects. `Logger` instances need to be stored in constant fields for good performance, something not trivial to achieve if `Loggers.init` required an `IOs` suspension.

### Lists: 

License
-------

See the [LICENSE](https://github.com/getkyo/kyo/blob/master/LICENSE.txt) file for details.
 
