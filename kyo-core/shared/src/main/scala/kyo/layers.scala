package kyo

import kyo.Flat.unsafe
import kyo.core.*

sealed trait Layer[-A, +B]:
    self =>

    import Layers.*

    def >>>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, C]     = To(self, that)
    def ++[B1, C](that: Layer[B1, C]): Layer[A & B1, B & C]      = And(self, that)
    def >+>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, B & C] = self ++ (self >>> that)

    //  🔺 <- dunce cap for using mutation
    def run: TypeMap[B] < Effects =
        doRun(scala.collection.mutable.Map.empty[Layer[?, ?], Any])

    private def doRun(memoMap: scala.collection.mutable.Map[Layer[?, ?], Any]): TypeMap[B] < Effects =
        memoMap.get(self) match
            case Some(result) =>
                result.asInstanceOf[TypeMap[B] < Effects]

            case None =>
                self match
                    case And(lhs, rhs) =>
                        for
                            leftResult  <- lhs.doRun(memoMap)
                            rightResult <- rhs.doRun(memoMap)
                        yield leftResult.union(rightResult).asInstanceOf[TypeMap[B] < Effects]

                    case To(lhs, rhs) =>
                        {

                            for
                                leftResult  <- lhs.doRun(memoMap)
                                rightResult <- Envs.runTypeMap(leftResult)(rhs.doRun(memoMap))(using unsafe.bypass, Envs.bypassHasEnvs)
                            yield rightResult
                        }.asInstanceOf[TypeMap[B] < Effects]

                    case layer @ FromKyo(kyo) =>
                        kyo().map { result =>
                            memoMap += (self -> result)
                            result
                        }.asInstanceOf[TypeMap[B] < Effects]
        end match
    end doRun

end Layer

// def run -> A < Effects
// V < Envs[A] & Envs[B]
// Envs.run(envMap.get[A])(v)

object Layers:
    type Effects = Fibers & IOs & Resources

    case class And[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[In2, Out2])       extends Layer[In1 & In2, Out1 & Out2]
    case class To[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[Out1 & In2, Out2]) extends Layer[In1 & In2, Out2]
    case class FromKyo[In, Out](kyo: () => TypeMap[Out] < (Envs[In] & Effects))(using val tag: Tag[Out]) extends Layer[In, Out]

    // COMPLEXITY:
    // - ZLayer.scoped <<<
    //
    // - Finalizers (Ref Counting)
    // - Memoizaiton
    // - Parallelization
    // - FiberRefs
    //
    // db >>> users
    // db >>> events
    def apply[A, B: Tag](kyo: => B < (Effects & Envs[A])): Layer[A, B] =
        FromKyo { () =>
            kyo.map { result => TypeMap(result) }
        }

    def from[A: Tag, B: Tag](f: A => B): Layer[A, B] =
        Layers.apply { Envs.get[A].map(f) }

    def from[A: Tag, B: Tag, C: Tag](f: (A, B) => C): Layer[A & B, C] =
        Layers.apply {
            for
                a <- Envs.get[A]
                b <- Envs.get[B]
            yield f(a, b)
        }

    // macro for merge?
    def provide[B, S0, S1](layer: Layer[Any, B] < S0)(v: Any < Envs[B] & S1) = ???

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
        val layer: Layer[Config, DB] = Layers.apply {
            for
                config <- Envs.get[Config]
                _ <- Resources.acquireRelease(
                    IOs { println("Acquiring DB with config: " + config.name) }
                )(_ =>
                    IOs { println("Releasing DB with config: " + config.name) }
                )
                _ <- IOs { println("DONE") }
            yield DB(config)
        }
    end DB

    case class Users(db: DB)
    object Users:
        val layer: Layer[DB, Users] = Layers.apply {
            for
                db <- Envs.get[DB]
                _  <- IOs { println("I AM STARTING UP THE USERS WITH DB: " + db) }
            yield new Users(db)
        }
    end Users

    case class Events(db: DB)
    object Events:
        val layer: Layer[DB, Events] = Layers.apply {
            for
                db <- Envs.get[DB]
                _  <- IOs { println("I AM STARTING UP THE EVENTS WITH DB: " + db) }
            yield new Events(db)
        }
    end Events

    val configDbLayer: Layer[Any, DB]        = Config.layer >>> DB.layer
    val dbUsersLayer: Layer[Any, Users]      = configDbLayer >>> Users.layer
    val dbEventsLayer: Layer[Any, Events]    = configDbLayer >>> Events.layer
    val combined: Layer[Any, Users & Events] = dbUsersLayer ++ dbEventsLayer

    val program =
        for
            envMap <- combined.run
            _ <- IOs {
                println(s"""USERS: ${envMap.get[Users]}""")
                println(s"""EVENTS: ${envMap.get[Events]}""")
            }
        yield ()

    run(program)
end LayerApp
