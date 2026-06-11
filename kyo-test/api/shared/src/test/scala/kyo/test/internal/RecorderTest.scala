package kyo.test.internal

import kyo.Frame
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class RecorderTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    "Recorder" - {

        "record returns the value unchanged" in {
            val r      = new Recorder()
            val result = r.record(42, 0)
            assert(result == 42)
            Future.successful(succeed)
        }

        "diagram renders single subexpression value below source" in {
            val r = new Recorder()
            r.record(5, 7)
            val frame   = summon[Frame]
            val diagram = r.diagram("assert(x > 0)", frame)
            // The diagram must contain a '|' character under position 7 and '5' below it
            val lines = diagram.split("\n")
            assert(lines.length >= 3, s"Expected at least 3 lines, got:\n$diagram")
            val sourceLine = lines(0)
            val pipeLine   = lines(1)
            assert(sourceLine == "assert(x > 0)")
            assert(pipeLine.length > 7, s"Pipe line too short: '$pipeLine'")
            assert(pipeLine(7) == '|', s"Expected '|' at column 7 of pipe line '$pipeLine'")
            // The value '5' must appear somewhere after the pipe line
            assert(diagram.contains("5"), s"Expected '5' in diagram:\n$diagram")
            Future.successful(succeed)
        }

        "diagram renders multiple values at their positions" in {
            val r = new Recorder()
            r.record(10, 2)
            r.record(false, 8)
            r.record(3, 15)
            val frame   = summon[Frame]
            val diagram = r.diagram("assert(a + b && c > 0)", frame)
            val lines   = diagram.split("\n")
            assert(lines.length >= 3, s"Expected at least 3 lines in:\n$diagram")
            val pipeLine = lines(1)
            assert(pipeLine(2) == '|', s"Expected '|' at column 2: '$pipeLine'")
            assert(pipeLine(8) == '|', s"Expected '|' at column 8: '$pipeLine'")
            assert(pipeLine(15) == '|', s"Expected '|' at column 15: '$pipeLine'")
            assert(diagram.contains("10"), s"Expected '10' in:\n$diagram")
            assert(diagram.contains("false"), s"Expected 'false' in:\n$diagram")
            assert(diagram.contains("3"), s"Expected '3' in:\n$diagram")
            Future.successful(succeed)
        }

        "diagram includes frame position" in {
            val r     = new Recorder()
            val frame = summon[Frame]
            r.record(42, 0)
            val diagram = r.diagram("x == 42", frame)
            // Footer must include "at <fileName>:<lineNumber>"
            assert(diagram.contains("// at "), s"Expected '// at ' footer in:\n$diagram")
            assert(diagram.contains("RecorderTest.scala"), s"Expected 'RecorderTest.scala' in footer:\n$diagram")
            Future.successful(succeed)
        }

        "diagram on empty recorder returns source line and footer" in {
            val r       = new Recorder()
            val frame   = summon[Frame]
            val diagram = r.diagram("assert(true)", frame)
            assert(diagram.startsWith("assert(true)"), s"Expected source line first in:\n$diagram")
            assert(diagram.contains("// at "), s"Expected footer in:\n$diagram")
            Future.successful(succeed)
        }

        "diagram does not throw NegativeArraySizeException when maxCol == Int.MaxValue (M32 overflow guard)" in {
            val r = new Recorder()
            // Record a value at Int.MaxValue column. Without math.max(0, maxCol+1) the
            // Array.fill call would receive a negative size and throw NegativeArraySizeException.
            r.record(42, Int.MaxValue)
            val frame = summon[Frame]
            val result =
                try
                    val d = r.diagram("x", frame)
                    Right(d)
                catch
                    case e: NegativeArraySizeException => Left(e)
            assert(result.isRight, s"Expected diagram to succeed but got: $result")
            val diagramStr = result.toOption.get
            assert(diagramStr != null, "Expected non-null diagram string")
            Future.successful(succeed)
        }

        "record bounds a huge value to a short preview with a total-length marker" in {
            // Regression: a ~500KB recorded value (e.g. a rendered SVG) must not produce an unbounded
            // diagram. On Scala Native an oversized diagram overflows the test-interface RPC writeUTF
            // 64KB cap and crashes the whole suite's transport.
            val r    = new Recorder()
            val huge = "x" * 500000
            r.record(huge, 0)
            val diagram = r.diagram("assert(s == expected)", summon[Frame])
            assert(diagram.length <= Recorder.MaxDiagram, s"diagram length ${diagram.length} exceeds ${Recorder.MaxDiagram}")
            assert(diagram.contains("(500000 chars total)"), s"Expected total-length marker in:\n${diagram.take(200)}")
            Future.successful(succeed)
        }

        "render flattens control characters so a multi-line value stays on one line" in {
            val rendered = Recorder.render("a\nb\tc\rd")
            assert(!rendered.contains('\n'), s"newline leaked: '$rendered'")
            assert(!rendered.contains('\r'), s"carriage return leaked: '$rendered'")
            assert(!rendered.contains('\t'), s"tab leaked: '$rendered'")
            assert(rendered == "a\\nb\\tc\\rd", s"got: '$rendered'")
            Future.successful(succeed)
        }

        "render is null-safe (String.valueOf, not value.toString)" in {
            assert(Recorder.render(null) == "null")
            Future.successful(succeed)
        }

        "render leaves a short value unchanged" in {
            assert(Recorder.render(42) == "42")
            assert(Recorder.render("hi") == "hi")
            Future.successful(succeed)
        }

    }

end RecorderTest
