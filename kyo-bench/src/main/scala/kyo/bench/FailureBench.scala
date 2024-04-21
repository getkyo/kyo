package kyo.bench

class Ex1  extends Exception
object Ex1 extends Ex1
class Ex2  extends Exception
object Ex2 extends Ex2
class Ex3  extends Exception
object Ex3 extends Ex3

class FailureBench extends Bench.SyncAndFork[Either[Ex1 | Ex2, Int]]:

    val depth = 100

    def catsBench() =
        import cats.effect.*

        def loop(i: Int): IO[Either[Ex1, Either[Ex2, Int]]] =
            if i > depth then IO.pure(Right(Right(i)))
            else
                (i % 4) match
                    case 0 => loop(i + 1).map(_ => Left(Ex1))
                    case 1 => loop(i + 1).map(_ => Right(Left(Ex2)))
                    case 2 =>
                        loop(i + 1).map {
                            case Left(ex1) => Right(Left(Ex2))
                            case r         => r
                        }
                    case 3 =>
                        loop(i + 1).map {
                            case Right(Left(ex2)) => Left(Ex1)
                            case r                => r
                        }
                    case 4 => loop(i + 1)
        end loop

        loop(0).map {
            case Right(Left(ex2)) => Left(ex2)
            case Left(ex1)        => Left(ex1)
            case Right(value)     => value
        }
    end catsBench

    def kyoBench() =
        import kyo.*

        def loop(i: Int): Int < (IOs & Aborts[Ex1 | Ex2]) =
            if i > depth then i
            else
                (i % 4) match
                    case 0 => loop(i + 1).map(_ => Aborts[Ex1].fail(Ex1))
                    case 1 => loop(i + 1).map(_ => Aborts[Ex2].fail(Ex2))
                    case 2 =>
                        Aborts[Ex1].run(loop(i + 1)).map {
                            case Left(ex) => Aborts[Ex2].fail(Ex2)
                            case Right(v) => v
                        }
                    case 3 =>
                        Aborts[Ex2].run(loop(i + 1)).map {
                            case Left(ex) => Aborts[Ex1].fail(Ex1)
                            case Right(v) => v
                        }
                    case 4 => loop(i + 1)
                end match

        Aborts[Ex1 | Ex2].run(loop(0))
    end kyoBench

    def zioBench() =
        import zio.*

        def loop(i: Int): ZIO[Any, Ex1 | Ex2, Int] =
            if i > depth then ZIO.succeed(i)
            else
                (i % 4) match
                    case 0 => loop(i + 1).flatMap(_ => ZIO.fail(Ex1))
                    case 1 => loop(i + 1).flatMap(_ => ZIO.fail(Ex2))
                    case 2 =>
                        loop(i + 1).catchSome {
                            case ex1: Ex1 => ZIO.fail(Ex2)
                        }
                    case 3 =>
                        loop(i + 1).catchSome {
                            case ex2: Ex2 => ZIO.fail(Ex1)
                        }
                    case 4 => loop(i + 1)
        end loop
        loop(0).either
    end zioBench
end FailureBench
