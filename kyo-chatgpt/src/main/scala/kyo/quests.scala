package kyo

import kyo.ais._
import kyo.core._
import kyo.envs._
import kyo.frames._
import kyo.lists._
import kyo.sums._
import kyo.tries._
import kyo.direct._
import zio.schema.DeriveSchema
import zio.schema.codec.JsonCodec.JsonDecoder

import scala.compiletime.summonInline
import scala.io.Source
import scala.util.Failure
import scala.util.Success

object quests {

  case class Source(file: String, code: String)

  opaque type Quests = Envs[AI] | Lists | AIs | Sums[Set[Source]]

  object Quests {

    inline def select[T](desc: Any*)(using
        caller: sourcecode.File,
        frame: Frame["Quests.select"]
    ): T > Quests = {
      def loop(): T > Quests = {
        val schema = DeriveSchema.gen[T]
        for {
          _  <- observe(caller)
          ai <- Envs[AI].get
          _  <- ai.system(frame, "select", desc, schema)
          text <-
            ai.ask(
                "produce a json in a code block (```json) for " + desc.mkString(
                    " "
                ) + " with the zio schema " + schema + ". Avoid empty fields. DO NOT USE PLACEHOLDER DATA."
            )
          r <- text.replaceAll("json", "").split("```").toList
            .map(_.trim).filter(!_.isEmpty).drop(1).headOption match {
            case None =>
              Envs[AI].get(_.system(
                  frame,
                  "no code block found, this is the code used to extract the json from your response: " +
                    "text.replaceAll('json', '').split('```'').toList.map(_.trim).filter(!_.isEmpty).drop(1).headOption"
              )) { _ =>
                loop()
              }
            case Some(json) =>
              JsonDecoder.decode(schema, json) match {
                case Left(err) =>
                  Envs[AI].get(_.system(
                      frame,
                      "JsonDecoder.decode failed, review zio-schema's documentation to provide a valid json",
                      err
                  )(
                      _.ask(
                          "please reflect on why you failed, re-analyze the code and your answer"
                      )
                  )) {
                    _ =>
                      loop()
                  }
                case Right(v) => Lists.foreach(v)
              }
          }
        } yield r
      }
      loop()
    }

    def drop[T](using
        caller: sourcecode.File,
        frame: Frame["Quests.drop"]
    ): T > Quests =
      Envs[AI].get(_.system(frame, "drop")) { _ =>
        Lists.drop
      }

    def foreach[T, S](v: List[T] > S): T > (S | Quests) =
      Lists.foreach(v)

    def filter[S](p: Boolean > S)(using frame: Frame["Quests.filter"]): Unit > (S | Quests) =
      p { p =>
        Envs[AI].get(_.system(frame, "filter", p)) { _ =>
          Lists.filter(p)
        }
      }

    def fail[T](reason: String)(using frame: Frame["Quests.fail"]): T > Quests =
      Envs[AI].get(_.system(frame, "fail", reason)) { _ =>
        AIs.fail[T](reason)
      }

    private val self = summon[sourcecode.File]

    def run[T](ai: AI)(q: T > Quests)(using
        caller: sourcecode.File,
        frame: Frame["Quests.run"]
    ): T > AIs =
      println(1)
      def loop(q: T > Quests): T > (AIs | Sums[Set[Source]]) =
        Tries.run(Lists.run(Envs[AI].let(ai)(q))) {
          case Success(v :: _) => v
          case res =>
            ai.system(
                "pay special attention to this infinite loop in the source code. On each execution, analyze previous " +
                  "interactions to provide data that will use creativity to eventually be able to fullful the complete query"
            ) { _ =>
              ai.system(frame, "failure", res) { _ =>
                loop(q)
              }
            }
        }
      for {
        _ <- ai.system(
            "You're interfacing with an automated script. It'll share the relevant " +
              "code and assume you're going to behave as expected from the AI being used " +
              "in the Quests effect. On each interaction, it'll only consider the first " +
              "json value in your response. Make your best effort to fulfill the queries. " +
              "You'll use data as of you cutoff date of September 2021. The script will " +
              "run in a loop until it's sucessful so pay attention to the previous " +
              "interactions. Your goal is to fulfill the final query without any access " +
              "to other systems and generate the data as you'd in a regular chat session. For" +
              "example, if the select is 'the best movies in the 90s', you'll think of a " +
              "user asking 'what were the best movies in the 90s' and answer accordingly " +
              "with the json."
        )
        _ <- ai.system(frame, "run")
        r <- Sums[Set[Source]].drop(loop {
          for {
            _ <- observe(self)
            _ <- observe(caller)
            s <- ai.ask(
                "What data is the code at " + frame + " trying to obtain? What is the final query? What values " +
                  "could be used to satisfy the final query results?"
            )
            q <- q
          } yield q
        })
      } yield r

    def run[T](q: T > Quests)(using
        caller: sourcecode.File,
        frame: Frame["Quests.run"]
    ): T > AIs =
      AIs.init(run(_)(q))

    private def observe(file: sourcecode.File): Unit > Quests =
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
