package kyo.chatgpt

import kyo.chatgpt.ais._
import kyo.core._
import kyo.envs._
import kyo.frames._
import kyo.lists._
import kyo.sums._
import kyo.tries._
import kyo.ios._
import kyo.aspects._
import zio.schema.DeriveSchema
import zio.schema.codec.JsonCodec.JsonDecoder

import scala.compiletime.summonInline
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex
import java.io.StringWriter
import java.io.PrintWriter
import kyo.options.Options
import zio.schema.Schema

import kyo.chatgpt.ais
object quests {

  case class Source(file: String, code: String)

  opaque type Quests = Envs[AI] | Lists | AIs | Sums[Set[Source]]

  object Quests {

    private val firstJsonBlock: Regex = """(?s)```json\s*([\s\S]+?)\s*```""".r

    def log[T, U](msg: Any*)(using frame: Frame[T]): Unit > (Envs[AI] | AIs) =
      Envs[AI].get(_.system(frame, msg)).unit

    inline def select[T](desc: Any*)(using
        caller: sourcecode.File,
        frame: Frame["Quests.select"]
    ): T > Quests =
      select(DeriveSchema.gen[T], desc: _*)

    def select[T](schema: Schema[T], desc: Any*)(using
        caller: sourcecode.File,
        frame: Frame["Quests.select"]
    ): T > Quests =
      for {
        ai <- Envs[AI].get
        _  <- log(caller, schema, desc)
        text <- ai.ask(
            s"Please analyze the provided source code, the systems message, and produce a JSON block (```json)" +
              s"that will ensure the entire quest execution to succeed. Think about all " +
              s"quest interactions to produce responses that will satisfy them globally, " +
              s"including the interactions between the different replies you provide. " +
              s"When analyzing the systems message, reason about the timeline, the frame " +
              s"information, and the execution state based on the source code. "
        )
        result <- firstJsonBlock.findFirstMatchIn(text).map(_.group(1)) match {
          case None =>
            AIs.fail[T]("No JSON block found")
          case Some(json) =>
            JsonDecoder.decode(schema, json) match {
              case Left(err) => AIs.fail[T]("Invalid JSON block", err)
              case Right(v)  => Lists.foreach(v)
            }
        }
      } yield result

    def foreach[T, S](list: List[T] > S)(using
        frame: Frame["Quests.foreach"]
    ): T > (S | Quests) =
      for {
        v <- list
        _ <- log(v)
        v <- Lists.foreach(v)
      } yield v

    def filter[S](predicate: Boolean > S)(using
        frame: Frame["Quests.filter"]
    ): Unit > (S | Quests) =
      for {
        p <- predicate
        _ <- log(p)
        v <- Lists.filter(p)
      } yield v

    private val self = summon[sourcecode.File]

    def run[T](ai: AI)(q: T > Quests)(using
        caller: sourcecode.File,
        frame: Frame["Quests.run"]
    ): T > AIs =
      def trySolve(
          q: T > Quests
      ): Either[String, T] > (AIs | Sums[Set[Source]]) =
        Tries.run(Options.getOrElse(
            Lists.run(Envs[AI].let(ai)(q))(_.headOption),
            Tries.fail("Quest has failed becuase no results were produced.")
        )) {
          case Success(v) =>
            Right(v)
          case Failure(ex) =>
            Aspects.run {
              for {
                _ <- ai.system(frame, "failure", ex)
                why <- ai.ask("why has the quest failed? Provide a reply that will help you " +
                  "generate responses that will succeed the quest on the next try. Also provide " +
                  "pointers of the data you used so you can reuse them in the next attempt. " +
                  "One paragraph.")
              } yield Left(s"failure $ex why: $why")
            }
        }
      def loop(
          q: T > Quests,
          failures: List[String] = Nil
      ): T > (AIs | Envs[AI] | Sums[Set[Source]]) =
        AIs.ephemeral(log(
            s"Your previous attempts failed due to: ${failures.reverse}."
        )(_ => trySolve(q))) {
          case Left(failure) =>
            loop(q, failure :: failures)
          case Right(result) =>
            result
        }

      val preamble =
        for {
          ai <- Envs[AI].get
          _  <- log(caller, q)
          _ <- ai.system(
              s"Please analyze the provided source code, the systems message, and produce an output " +
                s"that will ensure the entire quest execution to succeed. Think about all" +
                s"quest interactions to produce responses that will satisfy them globally," +
                s"including the interactions between the different replies you provide."
          )
          _ <- observe(self)
          _ <- observe(caller)
        } yield ()

      AIs.ephemeral(
          Sums[Set[Source]].drop(
              Envs[AI].let(ai)(
                  preamble(_ => loop(q))
              )
          )
      )

    def run[T](q: T > Quests)(using
        caller: sourcecode.File,
        frame: Frame["Quests.run"]
    ): T > (IOs | AIs) =
      AIs.init(run(_)(q))

    private def observe(file: sourcecode.File): Unit > (Envs[AI] | Sums[Set[Source]] | AIs) =
      val code   = scala.io.Source.fromFile(file.value).getLines().mkString("\n")
      val source = Source(file.value.split('/').last, code)
      Sums[Set[Source]].get { sources =>
        if (sources.contains(source)) ()
        else
          Envs[AI].get(_.system("new source code found", source)) { _ =>
            Sums[Set[Source]].add(Set(source)).unit
          }
      }
  }
}
