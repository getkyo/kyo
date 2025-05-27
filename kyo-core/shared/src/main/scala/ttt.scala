import kyo.{Abort as _, Choice as _, *}
import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import kyo.kernel.Loop.Outcome

sealed trait Choice extends ArrowEffect[Choice.Op[*], Id]

object Choice:
    enum Op[A] derives CanEqual:
        case Fail(msg: String) extends Op[Nothing]
        case Get()             extends Op[Boolean]

    def fail(msg: String): Nothing < Choice =
        ArrowEffect.suspend(Tag[Choice], Op.Fail(msg))

    def get: Boolean < Choice =
        ArrowEffect.suspend(Tag[Choice], Op.Get())

    def runThrowOnFail[A, S](v: A < (Choice & S)): Seq[A] < (Abort[String] & S) =
        ArrowEffect.handle(Tag[Choice], v.map(Seq(_)))(
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Fail(msg) => Abort.fail(msg)
                        case Op.Get()     => Kyo.foreach(Seq(true, false))(b => runThrowOnFail(cont(b))).map(_.flatten.flatten)
        )

    def runNoThrowOnFail[A, S](v: A < (Choice & S)): Seq[A] < S =
        ArrowEffect.handle(Tag[Choice], v.map(Seq(_)))(
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Fail(msg) => Seq.empty
                        case Op.Get()     => Kyo.foreach(Seq(true, false))(b => runNoThrowOnFail(cont(b))).map(_.flatten.flatten)
        )
end Choice

sealed trait Abort[E] extends ArrowEffect[Abort.Op[E, *], Id]

object Abort:
    enum Op[E, A]:
        case Fail[E](e: E)                                                     extends Op[E, Nothing]
        case Recover[E, A, S](f: E => Either[E, A] < S, v: A < (Abort[E] & S)) extends Op[E, A < S]

    def interpose[E](using
        Tag[E]
    )[B, S2](
        handler: [A, S] => (E => Either[E, A] < S, A < (Abort[E] & S)) => A < (Abort[E] & S & S2)
    )(
        v: B < (Abort[E] & S2)
    ): B < (Abort[E] & S2) =
        ArrowEffect.handleLoop(Tag[Abort[E]], v) {
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Fail(e) =>
                            Abort.fail(e)
                        case Op.Recover(handleError, action) =>
                            val x = handler(handleError, action).map(result => Loop.continue(cont(result.asInstanceOf[C])))
                            x.asInstanceOf[Outcome[B < (Abort[E] & S2), B] < (Abort[E] & S2)]
        }

    def recover[E](using Tag[E])[A, S](handleError: E => Either[E, A] < S)(v: A < (Abort[E] & S)): A < (S & Abort[E]) =
        ArrowEffect.suspendWith(Tag[Abort[E]], Op.Recover(handleError, v))(identity)

    def fail[E: Tag](e: E): Nothing < Abort[E] =
        ArrowEffect.suspend(Tag[Abort[E]], Op.Fail(e))

    def run[E](using Tag[E])[A, S](handleError: E => Either[E, A] < S)(v: A < (Abort[E] & S)): Either[E, A] < S =
        ArrowEffect.handle(Tag[Abort[E]], v.map(a => Right(a): Either[E, A])) {
            [C] =>
                (op, cont) =>
                    op match
                        case Op.Fail(e) =>
                            handleError(e)
                        case Op.Recover(handleError, v) =>
                            run(handleError)(v).map {
                                case Right(r) =>
                                    cont(r.asInstanceOf[C])
                                case either =>
                                    either.asInstanceOf[Either[E, A]]
                            }.asInstanceOf[Either[E, A] < (Abort[E] & S & S)]
        }
end Abort

def hookLoggingToRecover[A, S](v: A < (Abort[String] & IO & S)): A < (Abort[String] & IO & S) =
    Abort.interpose[String] {
        [B, S2] =>
            (handleError, action) =>
                println(1)
                for
                    _ <- Console.printLine("[LOG] Entering recover scope...")
                    result <- Abort.recover[String] { ex =>
                        for
                            _       <- Console.printLine(s"[LOG] Caught exception: $ex")
                            handled <- handleError(ex)
                        yield handled
                    }(action)
                yield result
                end for
    }(v)

def failWhenBothTrue: (Boolean, Boolean) < (Choice & IO) =
    for
        x <- Choice.get
        y <- Choice.get
        _ <- Console.printLine(s"x=$x, y=$y")
        _ <- if x && y then Choice.fail("x = y = True") else Kyo.unit
    yield (x, y)

def recoverFail: Either[String, (Boolean, Boolean)] < (Choice & IO & Abort[String]) =
    Abort.recover[String] { error =>
        Console.printLine(s"caught failure: $error").andThen(Left(error))
    } {
        failWhenBothTrue.map(Right(_))
    }

def program: Either[String, (Boolean, Boolean)] < (Choice & IO & Abort[String]) =
    hookLoggingToRecover(recoverFail)

object example extends App:
    import AllowUnsafe.embrace.danger
    {
        for
            _ <- Console.printLine("[NoThrowOnFail]")
            r1 <-
                Abort.run[String] { error =>
                    Console.printLine(s"Error: $error").map(_ => Left(error))
                } {
                    Choice.runNoThrowOnFail(recoverFail)
                    // Choice.runNoThrowOnFail(failWhenBothTrue.map(Right(_)))
                }
            _ <- Console.printLine(r1.toString)
        // _ <- Console.printLine("")
        // _ <- Console.printLine("[ThrowOnFail]")
        // r2 <- Abort.run[String] { error =>
        //     Console.printLine(s"Uncaught error: $error").map(_ => Left(error))
        // } {
        //     Choice.runThrowOnFail(program)
        // }
        // _ <- Console.printLine(r2.toString)
        yield ()
    }.handle(
        Debug.trace,
        IO.Unsafe.evalOrThrow
    )
end example
