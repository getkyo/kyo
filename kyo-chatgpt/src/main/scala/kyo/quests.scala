package kyo

import kyo.ais._
import kyo.core._
import kyo.envs._
import kyo.frames._
import kyo.lists._
import kyo.sums._
import kyo.tries._
import kyo.ios._
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

import sbt.io.IO

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
            s"Please provide a JSON value in a code block (```json) for the schema $schema and description $desc. " +
              "Remember not to use placeholder data. Re-analyze the previous failures reported by the system to fullfil the quest."
        )
        result <- firstJsonBlock.findFirstMatchIn(text).map(_.group(1)) match {
          case None =>
            AIs.fail[T](
                frame,
                "no code block found, this is the regex used to extract the json from your response: " + firstJsonBlock
            )
          case Some(json) =>
            JsonDecoder.decode(schema, json) match {
              case Left(err) =>
                AIs.fail[T](
                    frame,
                    "JsonDecoder.decode failed, review zio-schema's documentation to provide a valid json",
                    err
                )
              case Right(v) =>
                Lists.foreach(v)
            }
        }
      } yield result

    def foreach[T, S](list: List[T] > S)(using
        frame: Frame["Quests.foreach"]
    ): T > (S | AIs | Envs[AI] | Lists) =
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
          q: T > Quests,
          lastFailure: Option[String]
      ): Either[String, T] > (AIs | Sums[Set[Source]]) =
        Tries.run(Options.getOrElse(
            Lists.run(Envs[AI].let(ai)(q))(_.headOption),
            Tries.fail("empty quest result")
        )) {
          case Success(v) =>
            Right(v)
          case Failure(ex) =>
            for {
              _ <- ai.system(frame, "failure", ex)
              why <- ai.ask(
                  "The quest failed. As we restart the execution, please summarize the reasons for the failure " +
                    "and provide pointers for generating the necessary data again. Keep in mind that you may need to generate the same data " +
                    "as in the previous attempt if it helps in solving the quest so provide samples or descriptions of it as well. "
              )
            } yield Left(s"failure $ex why: $why")
        }
      def loop(
          q: T > Quests,
          failures: List[String] = Nil
      ): T > (AIs | Envs[AI] | Sums[Set[Source]]) =
        AIs.ephemeral(log(
            s"Your previous attempts failed due to: ${failures.reverse}."
        )(_ => trySolve(q, failures.headOption))) {
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
              "You are now interacting an automated script that shares relevant code snippets and expects you to " +
                "respond accurately as an AI within the Quests effect. Kyo is a library for algebraic effects in Scala, " +
                "using an innovative technique for composable effects. During each interaction, the script will consider " +
                "the first JSON value in your response. Make sure your responses are accurate and avoid using placeholder " +
                "data. The script will loop until a successful response is obtained, so pay attention to previous " +
                "interactions and use your knowledge up to September 2021. Your goal is to fulfill the final query without " +
                "accessing other systems, generating data as you would in a regular chat session. For example, if the " +
                "select is 'the best movies in the 90s', think of a user asking 'what were the best movies in the 90s' and " +
                "answer accordingly with the JSON."
          )
          _ <- observe(self)
          _ <- observe(caller)
          _ <- ai.ask(
              "Analyze the code at " + frame + " to understand the data it's trying to obtain. What is the final query? " +
                "What values can be used to satisfy the final query results?"
          )
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

    private def observeResource(path: String): String =
      if (path.endsWith("kyo-chatgpt/src/main/scala/kyo/quests.scala")) {
        val relPath = "/scala/kyo/quests.scala"
        println(s"relative path: ${relPath}")
        val stream = getClass.getResourceAsStream(relPath)
        return scala.io.Source.fromInputStream(stream).mkString
      } else {
        return scala.io.Source.fromFile(path).getLines().mkString("\n")
      }

    private def observe(file: sourcecode.File): Unit > (Envs[AI] | Sums[Set[Source]] | AIs) =
      // val code   = scala.io.Source.fromFile(file.value).getLines().mkString("\n")
      println(s"observing file ${file}")
      val code   = observeResource(file.value)
      val source = Source(file.value.split('/').last, code)
      println(s"source: ${source}")
      Sums[Set[Source]].get { sources =>
        if (sources.contains(source)) ()
        else
          Envs[AI].get(_.system("new source code found", source)) { _ =>
            Sums[Set[Source]].add(Set(source)).unit
          }
      }
  }
}
