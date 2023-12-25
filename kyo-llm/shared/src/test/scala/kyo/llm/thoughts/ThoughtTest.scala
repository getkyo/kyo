package kyo.llm.thoughts

import kyo._
import kyo.llm.json._
import kyo.llm.agents._
import kyo.llm.ais._
import kyoTest.KyoTest
import zio.schema.{Schema => ZSchema}
import scala.concurrent.Future
import org.scalatest.compatible.Assertion
import kyo.llm.KyoLLMApp

abstract class ThoughtTest extends KyoTest {

  trait Task[T] {
    def show: String
    def eval: T
  }

  case class Result[Thought, T](
      thought: Thought,
      result: T
  )

  def test[Thought, T](
      gen: => Task[T],
      successThreshold: Double
  )(
      implicit
      t: Json[Agents.Request[Result[Thought, T]]],
      f: Flat[T]
  ): Assertion = {
    KyoLLMApp.run {
      def check(results: List[(Task[T], T)]): Boolean < AIs = {
        val total = results.size
        val ok    = results.count(r => r._1.eval == r._2)
        ok.toDouble / total >= successThreshold
      }

      def loop(results: List[(Task[T], T)], tries: List[Int]): Assertion < AIs =
        tries match {
          case Nil =>
            fail()
          case parallelism :: tail =>
            AIs.parallelTraverse(List.fill(parallelism)(gen)) { task =>
              AIs.gen[Result[Thought, T]](task.show).map(r => task -> r.result)
            }.map(results ++ _).map { results =>
              val percent = {
                val total = results.size
                val ok    = results.count(r => r._1.eval == r._2)
                ok.toDouble / total
              }
              if (percent >= successThreshold) {
                succeed
              } else if (tail.isEmpty) {
                assert(percent >= successThreshold)
              } else {
                loop(results, tail)
              }
            }
        }
      loop(Nil, List(4, 6))
    }
  }
}
