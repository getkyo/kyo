package kyo

import kyo.Flat.unsafe
import kyo.core.*

// Layer[In, Out]

// Out < Layers[In]

type Layers[In]

// val lengthLayer = Layers(Envs.get[String].map(_.length)) : Int < (Layers[String] & Choices)
// val intLayer    = Layers("Hello")
// intLayer >>> lengthLayer
//
// Set -> Predefined
// Type Parameter
// -> flex alternative pending sets

// Layer(Choices.get(1, 2, 3).map(n => n.toString))

// Layer[Any, String, Choices]
// layer.run ->    String < Choices

// val = Layer(1): Layer[Any, Int, Any].run.pure

// Aborts.fail("oops").catchAll
// IOs(1Os)

sealed trait Layer[-In, +Out, -S]:
    self =>

    import Layers.*

    def >>>[In2, Out2, S1](that: Layer[Out & In2, Out2, S1]): Layer[In & In2, Out2, S & S1] = To(self, that) // In1, Out1, In2, Out2, S
    def ++[B1, C, S1](that: Layer[B1, C, S1]): Layer[In & B1, Out & C, S & S1]              = And(self, that)
    def >+>[B1, C, S1](that: Layer[Out & B1, C, S1]): Layer[In & B1, Out & C, S & S1]       = self ++ (self >>> that)

    //  🔺 <- dunce cap for using mutation

    private[kyo] def doRun(memoMap: scala.collection.mutable.Map[Layer[?, ?, ?], Any]): TypeMap[Out] < (S & Envs[In]) =
        type Expected = TypeMap[Out] < (S & Envs[In])
        memoMap.get(self) match
            case Some(result) =>
                result.asInstanceOf[Expected]

            case None =>
                self match
                    case And(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- rhs.doRun(memoMap)
                            yield leftResult.union(rightResult)
                        }.asInstanceOf[Expected]

                    case To(lhs, rhs) =>
                        {
                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- Envs.runTypeMap(leftResult)(rhs.doRun(memoMap))(using unsafe.bypass, Envs.bypass)
                            yield rightResult
                        }.asInstanceOf[Expected]

                    case layer @ FromKyo(kyo) =>
                        kyo().map { result =>
                            memoMap += (self -> result)
                            result
                        }.asInstanceOf[Expected]
        end match
    end doRun

end Layer
extension [In, Out, S](layer: Layer[In, Out, S])
    def run: TypeMap[Out] < (S & Envs[In]) =
        layer.doRun(scala.collection.mutable.Map.empty[Layer[?, ?, ?], Any])

extension [Out, S](layer: Layer[Any, Out, S])
    @annotation.targetName("OOPS")
    def run: TypeMap[Out] < S =
        layer.doRun(scala.collection.mutable.Map.empty[Layer[?, ?, ?], Any]).asInstanceOf[TypeMap[Out] < S]
//

// def run -> A < Effects
// V < Envs[A] & Envs[B]
// Envs.run(envMap.get[A])(v)
// Envs.runTypeMap
// macro? Envs.run(...)(kyo)
// zio.provide(...)
// Envs.run(...) <- accepts varargs
// A < S
// Layer[A, B, S]

object Layers:

    case class And[In1, Out1, In2, Out2, S](lhs: Layer[In1, Out1, S], rhs: Layer[In2, Out2, S]) extends Layer[In1 & In2, Out1 & Out2, S]
    case class To[In1, Out1, In2, Out2, S](lhs: Layer[In1, Out1, S], rhs: Layer[Out1 & In2, Out2, S]) extends Layer[In1 & In2, Out2, S]
    case class FromKyo[In, Out, S](kyo: () => TypeMap[Out] < (Envs[In] & S))(using val tag: Tag[Out]) extends Layer[In, Out, S]

    def apply[A, B: Tag, S](kyo: => B < (Envs[A] & S)): Layer[A, B, S] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    def scoped[A, B: Tag, S](kyo: => B < (Envs[A] & Resources & S)): Layer[A, B, Fibers & S] =
        apply { Resources.run(kyo) }

    def from[A: Tag, B: Tag, S](f: A => B < S): Layer[A, B, S] =
        Layers.apply {
            Envs.get[A].map(f)
        }

    def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S): Layer[A & B, C, S] =
        Layers.apply {
            for
                a <- Envs.get[A]
                b <- Envs.get[B]
            yield f(a, b)
        }

    // macro for merge?
//    def provide[B, S0, S1](layer: Layer[Any, B,] < S0)(v: Any < Envs[B] & S1) = ???
end Layers

// A->B >>> A&B>C
// extension [A0, B0](self: Layer[A0, B0])

// end extension

object LayerApp extends KyoApp:

    case class Config(name: String)
    object Config:
        val layer = Layers.apply(Config("Hello"))

    case class DB(config: Config)
    object DB:
        val layer = Layers {
            for
                config <- Envs.get[Config]
                _ <- Resources.acquireRelease(
                    IOs { println("Acquiring DB with config: " + config.name) }
                )(_ =>
                    IOs { println("Releasing DB with config: " + config.name) }
                )
            yield DB(config)
        }
    end DB

    case class Users(db: DB)
    object Users:
        val layer = Layers.apply {
            for
                db <- Envs.get[DB]
                _  <- IOs { println("I AM STARTING UP THE USERS WITH DB: " + db) }
            yield new Users(db)
        }
    end Users

    case class Events(db: DB, metrics: Metrics)
    object Events:
        val layer = Layers.apply {
            for
                db      <- Envs.get[DB]
                metrics <- Envs.get[Metrics]
                _       <- IOs { println(s"I AM STARTING UP THE EVENTS WITH DB: $db, $metrics") }
            yield new Events(db, metrics)
        }
    end Events

    case class Metrics(count: Int)
    object Metrics:
        val layer = Layers {
            for
                n <- Choices.get(0 to 3)
            yield Metrics(n)
        }
    end Metrics

    val configDbLayer = Config.layer >>> DB.layer
    val dbUsersLayer  = configDbLayer >>> Users.layer
    val dbEventsLayer = Metrics.layer ++ configDbLayer >>> Events.layer
    val combined      = dbUsersLayer ++ dbEventsLayer

    val program =
        for
            envMap <- combined.run
            _ <- IOs {
                println(s"""USERS: ${envMap.get[Users]}""")
                println(s"""EVENTS: ${envMap.get[Events]}""")
            }
        yield ()

    val p2 = Choices.run(program)
    run(p2.asInstanceOf[Seq[Unit] < Resources])
end LayerApp
