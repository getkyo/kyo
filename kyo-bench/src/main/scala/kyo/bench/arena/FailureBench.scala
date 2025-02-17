package kyo.bench.arena

class Ex1  extends Exception derives CanEqual
object Ex1 extends Ex1
class Ex2  extends Exception derives CanEqual
object Ex2 extends Ex2
class Ex3  extends Exception derives CanEqual
object Ex3 extends Ex3

class FailureBench extends ArenaBench.SyncAndFork[Either[Ex1 | Ex2, Int]](Left(Ex2)):

    val depth = 100

    def catsBench() =
        import cats.effect.*

        def loop(i: Int): IO[Either[Ex1, Either[Ex2, Int]]] =
            if i > depth then IO.pure(Right(Right(i)))
            else
                (i % 5) match
                    case 0 =>
                        loop(i + 1).map(_ => Right(Left(Ex2)))
                    case 1 =>
                        loop(i + 1).map(_ => Left(Ex1))
                    case 2 =>
                        loop(i + 1).map {
                            case Left(Ex1) => Right(Left(Ex2))
                            case r         => r
                        }
                    case 3 =>
                        loop(i + 1).map {
                            case Right(Left(Ex2)) => Left(Ex1)
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

        def loop(i: Int): Int < Abort[Ex1 | Ex2] =
            if i > depth then i
            else
                (i % 5) match
                    case 0 => loop(i + 1).map(_ => Abort.fail(Ex1))
                    case 1 => loop(i + 1).map(_ => Abort.fail(Ex2))
                    case 2 =>
                        Abort.run[Ex1](loop(i + 1)).map {
                            case Result.Failure(_) => Abort.fail(Ex2)
                            case Result.Success(v) => v
                            case Result.Panic(ex)  => Abort.panic(ex)
                        }
                    case 3 =>
                        Abort.run[Ex2](loop(i + 1)).map {
                            case Result.Failure(_) => Abort.fail(Ex1)
                            case Result.Success(v) => v
                            case Result.Panic(ex)  => Abort.panic(ex)
                        }
                    case 4 => loop(i + 1)
                end match
        end loop
        Abort.run[Exception](loop(0)).map {
            case Result.Failure(ex: (Ex1 | Ex2)) => Left(ex)
            case Result.Success(v)               => Right(v)
            case _                               => ???
        }
    end kyoBench

    def zioBench() =
        import zio.*

        def loop(i: Int): ZIO[Any, Ex1 | Ex2, Int] =
            if i > depth then ZIO.succeed(i)
            else
                (i % 5) match
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
