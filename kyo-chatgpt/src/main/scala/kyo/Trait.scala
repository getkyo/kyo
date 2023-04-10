package kyo

import kyo.ais._
import kyo.aspects._
import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.ios._
import kyo.locals._
import java.nio.file.Paths
import scala.util.Try
import scala.util.matching.Regex

abstract class Trait(val ais: Set[AI])
    extends Cut[(AI, String), String, AIs] {
  def apply[S2, S3](v: (AI, String) > S2)(next: ((AI, String)) => String > (S3 | Aspects))
      : String > (AIs | S2 | S3 | Aspects) =
    v {
      case tup @ (ai, msg) =>
        if (ais.contains(ai))
          AIs.ephemeral {
            this(ai, msg)(next(ai, _))
          } { r =>
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
  )(next: String => String > (S | Aspects)): String > (S | Aspects | AIs)
}
