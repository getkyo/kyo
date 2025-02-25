package kyo.test

import kyo.*
import kyo.test.Assertion.Arguments
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.control.NonFatal

case class TestResult(arrow: TestArrow[Any, Boolean]):
    lazy val result: TestTrace[Boolean]           = TestArrow.run(arrow, Right(()))
    lazy val failures: Option[TestTrace[Boolean]] = TestTrace.prune(result, false)
    def isFailure: Boolean                        = failures.isDefined
    def isSuccess: Boolean                        = failures.isEmpty
    def &&(that: TestResult): TestResult          = TestResult(arrow && that.arrow)
    def ||(that: TestResult): TestResult          = TestResult(arrow || that.arrow)
    def unary_! : TestResult                      = TestResult(!arrow)
    def implies(that: TestResult): TestResult     = !this || that
    def ==>(that: TestResult): TestResult         = this.implies(that)
    def iff(that: TestResult): TestResult         = (this ==> that) && (that ==> this)
    def <==>(that: TestResult): TestResult        = this.iff(that)
    def ??(message: String): TestResult           = this.label(message)
    def label(message: String): TestResult        = TestResult(arrow.label(message))
    def setGenFailureDetails(details: GenFailureDetails): TestResult =
        TestResult(arrow.setGenFailureDetails(details))
end TestResult

object TestResult:
    def allSuccesses(assert: TestResult, asserts: TestResult*): TestResult = asserts.foldLeft(assert)(_ && _)

    def allSuccesses(asserts: Iterable[TestResult])(using trace: Trace, frame: Frame): TestResult =
        allSuccesses(assertCompletes, asserts.toSeq*)

    def anySuccesses(assert: TestResult, asserts: TestResult*): TestResult = asserts.foldLeft(assert)(_ || _)

    def anySuccesses(asserts: Iterable[TestResult])(using trace: Trace, frame: Frame): TestResult =
        anySuccesses(!assertCompletes, asserts.toSeq*)

    implicit def liftTestResultToKyo[R, E](result: TestResult)(using trace: Trace): TestResult < (Env[R] & Abort[E]) =
        if result.isSuccess then result
        else Abort.panic(Exit(result))

    final private[kyo] case class Exit(result: TestResult) extends Throwable

    @deprecated("Use allSuccesses", "2.0.16")
    def all(asserts: TestResult*): TestResult = asserts.reduce(_ && _)

    @deprecated("Use anySuccesses", "2.0.16")
    def any(asserts: TestResult*): TestResult = asserts.reduce(_ || _)
end TestResult

sealed trait TestArrow[-A, +B]:
    self =>

    def render: String =
        val builder = ChunkBuilder.make[String]()
        @tailrec
        def loop(arrows: List[Either[String, TestArrow[_, _]]]): Unit =
            if arrows.nonEmpty then
                arrows.head match
                    case Right(TestArrow.And(left, right)) =>
                        loop(Left("(") :: Right(left) :: Left(" && ") :: Right(right) :: Left(")") :: arrows.tail)
                    case Right(TestArrow.Or(left, right)) =>
                        loop(Left("(") :: Right(left) :: Left(" || ") :: Right(right) :: Left(")") :: arrows.tail)
                    case Right(TestArrow.Not(arrow)) =>
                        loop(Left("not") :: Left("(") :: Right(arrow) :: Left(")") :: arrows.tail)
                    case Right(TestArrow.AndThen(left, right)) =>
                        loop(Right(left) :: Left("(") :: Right(right) :: Left(")") :: arrows.tail)
                    case Right(arrow: TestArrow.Meta[?, ?]) =>
                        val code = arrow.code.map { code =>
                            if arrow.codeArguments.nonEmpty then s"$code(${arrow.codeArguments.mkString(", ")})" else code
                        }
                        builder += code.mkString
                        loop(arrows.tail)
                    case Right(arrow @ TestArrow.TestArrowF(_)) =>
                        builder += arrow.toString
                        loop(arrows.tail)
                    case Right(arrow @ TestArrow.Suspend(_)) =>
                        builder += arrow.toString
                        loop(arrows.tail)
                    case Left(str) =>
                        builder += str
                        loop(arrows.tail)
        loop(List(Right(self)))
        builder.result.mkString
    end render

    def ??(message: String): TestArrow[A, B] = self.label(message)

    def label(message: String): TestArrow[A, B] = self.meta(customLabel = Some(message))

    def setGenFailureDetails(details: GenFailureDetails): TestArrow[A, B] =
        self.meta(genFailureDetails = Some(details))

    import TestArrow.*

    def meta(
        span: Option[Span] = None,
        parentSpan: Option[Span] = None,
        code: Option[String] = None,
        location: Option[String] = None,
        completeCode: Option[String] = None,
        customLabel: Option[String] = None,
        genFailureDetails: Option[GenFailureDetails] = None
    ): TestArrow[A, B] = self match
        case self: Meta[A, B] =>
            new Meta(
                self.arrow,
                span.orElse(self.span),
                parentSpan.orElse(self.parentSpan),
                code.orElse(self.code),
                location.orElse(self.location),
                completeCode.orElse(self.completeCode),
                customLabel.orElse(self.customLabel),
                genFailureDetails.orElse(self.genFailureDetails)
            ):
                override def codeArguments: Chunk[Arguments] = self.codeArguments
        case _ =>
            Meta(
                arrow = self,
                span = span,
                parentSpan = parentSpan,
                code = code,
                location = location,
                completeCode = completeCode,
                customLabel = customLabel,
                genFailureDetails = genFailureDetails
            )

    def span(span: (Int, Int)): TestArrow[A, B] =
        meta(span = Some(Span(span._1, span._2)))

    def withCode(code: String): TestArrow[A, B] =
        meta(code = Some(code))

    def withCode(code: String, arguments: Arguments*): TestArrow[A, B] = self match
        case self: Meta[A, B] =>
            new Meta(
                self.arrow,
                self.span,
                self.parentSpan,
                Some(code),
                self.location,
                self.completeCode,
                self.customLabel,
                self.genFailureDetails
            ):
                override def codeArguments: Chunk[Arguments] = Chunk.Indexed.from(arguments)
        case _ =>
            new Meta(
                arrow = self,
                span = None,
                parentSpan = None,
                code = Some(code),
                location = None,
                completeCode = None,
                customLabel = None,
                genFailureDetails = None
            ):
                override def codeArguments: Chunk[Arguments] = Chunk.Indexed.from(arguments)

    def withCompleteCode(completeCode: String): TestArrow[A, B] =
        meta(completeCode = Some(completeCode))

    def withLocation(using Frame: Frame): TestArrow[A, B] =
        meta(location = Some(s"${Frame.path}:${Frame.line}"))

    def withParentSpan(span: (Int, Int)): TestArrow[A, B] =
        meta(parentSpan = Some(Span(span._1, span._2)))

    def >>>[C](that: TestArrow[B, C]): TestArrow[A, C] =
        AndThen[A, B, C](self, that)

    def &&[A1 <: A](that: TestArrow[A1, Boolean])(using ev: B <:< Boolean): TestArrow[A1, Boolean] =
        And(self.asInstanceOf[TestArrow[A1, Boolean]], that)

    def ||[A1 <: A](that: TestArrow[A1, Boolean])(using ev: B <:< Boolean): TestArrow[A1, Boolean] =
        Or(self.asInstanceOf[TestArrow[A1, Boolean]], that)

    def unary_![A1 <: A](using ev: B <:< Boolean): TestArrow[A1, Boolean] =
        Not(self.asInstanceOf[TestArrow[A1, Boolean]])
end TestArrow

object TestArrow:
    def succeed[A](value: => A): TestArrow[Any, A] = TestArrowF(_ => TestTrace.succeed(value))

    def fromFunction[A, B](f: A => B): TestArrow[A, B] = make(f andThen TestTrace.succeed)

    def suspend[A, B](f: A => TestArrow[Any, B]): TestArrow[A, B] = TestArrow.Suspend(f)

    def make[A, B](f: A => TestTrace[B]): TestArrow[A, B] =
        makeEither(e => TestTrace.panic(e).annotate(TestTrace.Annotation.Rethrow), f)

    def makeEither[A, B](onFail: Throwable => TestTrace[B], onSucceed: A => TestTrace[B]): TestArrow[A, B] =
        TestArrowF {
            case Left(error)  => onFail(error)
            case Right(value) => onSucceed(value)
        }

    private def attempt[A](expr: => TestTrace[A]): TestTrace[A] =
        try expr
        catch
            case ex if NonFatal(ex) =>
                ex.setStackTrace(ex.getStackTrace.filterNot { (ste: StackTraceElement) =>
                    ste.getClassName.startsWith("kyo.test.TestArrow")
                })
                TestTrace.panic(ex)

    def run[A, B](arrow: TestArrow[A, B], in: Either[Throwable, A]): TestTrace[B] = attempt {
        arrow match
            case TestArrowF(f) =>
                f(in)
            case AndThen(f, g) =>
                val t1 = run(f, in)
                t1.result match
                    case Result.Fail           => t1.asInstanceOf[TestTrace[B]]
                    case Result.Die(err)       => t1 >>> run(g, Left(err))
                    case Result.Succeed(value) => t1 >>> run(g, Right(value))
                end match
            case And(lhs, rhs) =>
                run(lhs, in) && run(rhs, in)
            case Or(lhs, rhs) =>
                // Implementation for Or operator conversion (detailed logic to be added based on semantics)
                ???
            case Not(arrow) =>
                // Implementation for Not operator conversion
                ???
            case Suspend(f) =>
                // Implementation for Suspend conversion
                ???
            case meta: Meta[?, ?] =>
                // For Meta, delegate to the underlying arrow
                run(meta.arrow, in)
    }

    // Definitions for TestArrow subtypes

    final case class TestArrowF[-A, +B](runF: Either[Throwable, A] => TestTrace[B]) extends TestArrow[A, B]:
        override def toString: String = s"TestArrowF($runF)"

    final case class AndThen[-A, +B, +C](left: TestArrow[A, B], right: TestArrow[B, C]) extends TestArrow[A, C]:
        override def toString: String = s"AndThen($left, $right)"

    final case class And[-A](left: TestArrow[A, Boolean], right: TestArrow[A, Boolean]) extends TestArrow[A, Boolean]:
        override def toString: String = s"And($left, $right)"

    final case class Or[-A](left: TestArrow[A, Boolean], right: TestArrow[A, Boolean]) extends TestArrow[A, Boolean]:
        override def toString: String = s"Or($left, $right)"

    final case class Not[-A](arrow: TestArrow[A, Boolean]) extends TestArrow[A, Boolean]:
        override def toString: String = s"Not($arrow)"

    final case class Suspend[-A, +B](f: A => TestArrow[Any, B]) extends TestArrow[A, B]:
        override def toString: String = s"Suspend($f)"

    final case class Meta[-A, +B](
        arrow: TestArrow[A, B],
        span: Option[Span],
        parentSpan: Option[Span],
        code: Option[String],
        location: Option[String],
        completeCode: Option[String],
        customLabel: Option[String],
        genFailureDetails: Option[GenFailureDetails]
    ) extends TestArrow[A, B]:
        def codeArguments: Chunk[Arguments] = Chunk.empty
        override def toString: String       = s"Meta($arrow)"
    end Meta

end TestArrow
