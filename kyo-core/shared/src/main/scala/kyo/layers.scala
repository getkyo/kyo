package kyo

import kyo.Layers.And

// -> Pending
// -> Result

// Envs[A] -> A.size == 1

// Type level memo

//type Layer[P, R] = ???

//object layers {}

// ZLayer[A & A2, E, B]
// Layer[B] < Envs[A] & Envs[A2] & Aborts[E]
// Envs.run(a)

// layer: Layer[A & B]
// kyo : Thing < Envs[A] & Envs[B]
// kyo.provide(layer) : Thing

// ZIO.serviceWithZIO{App](_.run).provide(App.live, ...)
// App.live.run(
//
// Envs.get[App].map(_.run).providing(App.live)
//
sealed trait Layer[-A, +B]:
    self =>

    import Layers.*

    def >>>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, C] =
        To(self, that)

    def ++[B1, C](that: Layer[B1, C]): Layer[A & B1, B & C] =
        And(self, that)

    def >+>[B1, C](that: Layer[B & B1, C]): Layer[A & B1, B & C] =
        self ++ (self >>> that)

    //  - DB >>> Users
    //  - DB >>> App
    //  And(To(FromKyo(DB), FromKyo(Users)), To(FromKyo(DB), App))
    //

    val tagAny = summon[Tag[Envs[Any]]]
    //  🔺 <- dunce cap for using mutation
    def run(memoMap: scala.collection.mutable.Map[Layer[?, ?], Any]): EnvMap[B] < (Fibers) =
        println("HELLO")
        // look into the memoMap for ourselves.

        // if it DOES exist, we have to run
        memoMap.get(self) match
            case Some(result) =>
                ???
            case None =>
                self match
                    case And(lhs, rhs) =>
                        for
                            leftResult  <- lhs.run(memoMap)
                            rightResult <- rhs.run(memoMap)
                        yield leftResult.union(rightResult).asInstanceOf[EnvMap[B] < (Fibers)]

                    case layer @ FromKyo(kyo) =>
                        println("Running FromKyo")
                        kyo.map { result =>
                            memoMap += (self -> result)
                            result
                        }.asInstanceOf[EnvMap[B] < (Fibers)]
        end match
    end run

end Layer

extension [In, Out](layer: Layer[In, Out])
    def build: Out < Fibers & Envs[In] = ???

extension [Out](layer: Layer[Any, Out])
    def build: Out < Fibers = ???

// def run -> A < Effects

object Layers:

    // ++
    case class And[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[In2, Out2]) extends Layer[In1 & In2, Out1 & Out2]

    // >>>
    case class To[In1, Out1, In2, Out2](lhs: Layer[In1, Out1], rhs: Layer[Out1 & In2, Out2]) extends Layer[In1 & In2, Out2]

    case class FromKyo[In, Out](kyo: EnvMap[Out] < (Envs[In] & Fibers))(using tag: Tag[Out]) extends Layer[In, Out]

    type Effects = Fibers & IOs

    def make[A, B: Tag](v: B < (Effects & Envs[A])): Layer[A, B] =
        FromKyo(v.map(EnvMap(_)))

    // macro for merge?
    def provide[B, S0, S1](layer: Layer[Any, B] < S0)(v: Any < Envs[B] & S1) = ???

end Layers

// A->B >>> A&B>C
// extension [A0, B0](self: Layer[A0, B0])

// end extension

object LayerApp extends KyoApp:
    trait Config
    trait DB
    trait Bank:
        def start: Unit < IOs
    trait Users

    val kyoApp: Unit < (IOs & Envs[Bank]) =
        Envs.get[Bank].map(_.start)

    // val makeBank: Bank < Envs[DB] & Envs[Config]   = ???
    // val makeUsers: Users < Envs[DB] & Envs[Config] = ???
    // val makeDB: DB < Envs[Config]                  = ???
    // val makeConfig: Config < IOs                   = ???

    // val userLayer: Layer[DB & Config, Users] = Layers.make(makeUsers)
    // val bankLayer: Layer[DB & Config, Bank]  = Layers.make(makeBank)

    // val usersFinalLayer: Layer[DB, Users] = configLayer >>> userLayer
    // val bankFinalLayer: Layer[Any, Bank]  = configLayer >+> dbLayer >>> bankLayer
    // val c: Layer[DB, Users & Bank]        = bankFinalLayer ++ usersFinalLayer

    // lazy val dbLayer: Layer[Config, DB]      = Layers.make(makeDB)
    // lazy val configLayer: Layer[Any, Config] = Layers.make(makeConfig)

    val stupidLayer = Layers.make(
        IOs {
            println("HELLO")
            "HELLO"
        }
    )

    run(stupidLayer.run(scala.collection.mutable.Map.empty[Layer[?, ?], Any]).map(_.get[String]))
end LayerApp

//    run:
//        Layers.provide()
//        Layers.merge(bankLayer, dbLayer, configLayer).using(kyoApp)

final class EnvMap[+R](private[kyo] val map: Map[Tag[?], Any]):
    def get[A >: R](using tag: Tag[A]): A = map(tag).asInstanceOf[A]

    def add[A](value: A)(using tag: Tag[A]): EnvMap[R & A] =
        new EnvMap(map.updated(tag, value))

    def union[R0](that: EnvMap[R0]): EnvMap[R0] =
        new EnvMap(map ++ that.map)

end EnvMap

object EnvMap extends App:
    val empty: EnvMap[Any] = EnvMap(Map.empty[Tag[?], Any])

    def apply[A: Tag](a: A): EnvMap[A] = new EnvMap(Map(Tag[A] -> a))

    val x: EnvMap[String]           = EnvMap("String")
    val y: EnvMap[String & Boolean] = x.add(false)

    assert(y.get[Boolean].isInstanceOf[Boolean])
    assert(y.get[String].isInstanceOf[String])
//    assert(y.get[String & Boolean])
//    assert(y.get[Int])
end EnvMap

// ----------------
// 1. !Startup! Injection -> immediate extraction
//      -> DB
//      -> Config
//      -> Logger

// def run = for {
//     db <- ZIO.service[DB]
//     config <- ZIO.service[Config]

// Config -> DB
// Config -> Logger

// ------------------
// 2. !Runtime! Injection
//      -> Metrics
//      -> Request Context
//      -> transaction

//      ZIO[Metrics, E, A]
//          .provide(
//              Metrics.live ?? -> creates new every time
//              Transaction.layer(requestId) ?? -> creates new every time
//          )
//          )
//          )
//          )
//          )
