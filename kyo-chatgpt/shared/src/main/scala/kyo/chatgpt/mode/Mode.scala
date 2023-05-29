package kyo.chatgpt.mode

import kyo.chatgpt.ais._
import kyo.aspects._
import kyo.consoles._
import kyo._
import kyo.envs._
import kyo.ios._
import kyo.requests._
import kyo.tries._
import kyo.locals._
import java.nio.file.Paths
import scala.util.Try
import scala.util.matching.Regex

import kyo.chatgpt.ais

private[kyo] abstract class Mode(val ais: Set[AI])
    extends Cut[(AI, String), String, AIs] {

  def apply[S2, S3](v: (AI, String) > S2)(next: ((AI, String)) => String > (S3 with Aspects))
      : String > (AIs with S2 with S3 with Aspects) =
    v.map {
      case tup @ (ai, msg) =>
        if (ais.contains(ai))
          AIs.ephemeral {
            this(ai, msg)(next(ai, _))
          }.map { r =>
            for {
              _ <- ai.user(msg)
              _ <- ai.assistant(r)
            } yield r
          }
        else
          next(tup)
    }

  def apply[S](
      ai: AI,
      msg: String
  )(next: String => String > (S with Aspects))
      : String > (S with Requests with Tries with IOs with Aspects with AIs)
}
