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

    }

end RecorderTest
