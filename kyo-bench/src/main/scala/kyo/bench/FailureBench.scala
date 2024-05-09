package kyo.bench

class Ex1  extends Exception derives CanEqual
object Ex1 extends Ex1
class Ex2  extends Exception derives CanEqual
object Ex2 extends Ex2
class Ex3  extends Exception derives CanEqual
object Ex3 extends Ex3

class FailureBench extends Bench.SyncAndFork[Either[Ex1 | Ex2, Int]]:

    val depth          = 100
    val expectedResult = Left(Ex2)

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

        def loop(i: Int): Int < Aborts[Ex1 | Ex2] =
            if i > depth then i
            else
                (i % 5) match
                    case 0 => loop(i + 1).map(_ => Aborts.fail(Ex1))
                    case 1 => loop(i + 1).map(_ => Aborts.fail(Ex2))
                    case 2 =>
                        Aborts.run[Ex1](loop(i + 1)).map {
                            case Left(Ex1) => Aborts.fail(Ex2)
                            case Right(v)  => v
                        }
                    case 3 =>
                        Aborts.run[Ex2](loop(i + 1)).map {
                            case Left(Ex2) => Aborts.fail(Ex1)
                            case Right(v)  => v
                        }
                    case 4 => loop(i + 1)
                end match
        end loop
        Aborts.run[Ex1 | Ex2](loop(0))
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
