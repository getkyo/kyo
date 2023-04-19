package kyo.chatgpt.mode

import kyo.core._
import kyo.aspects._
import kyo.chatgpt.ais._
import kyo.direct._

import kyo.chatgpt.ais
import kyo.chatgpt.mode.Mode

class Reflect(val prompt: String, ais: Set[AI]) extends Mode(ais) {
  def this(ais: Set[AI]) = this("", ais)
  def apply[S](ai: AI, msg: String)(next: String => String > (S | Aspects)) =
    for {
      r1 <- AIs.ephemeral(next(msg))
      _ <- ai.user(
          "This could be your response:\n\n" + r1 + "\n\n\n" + prompt + ". Edit it with improvements ensure it's " +
            "compatible with the user request. Consider all messages in this session. The user shouldn't know this " +
            "specific exchange happened."
      )
      r2 <- ai.ask(msg)
    } yield r2
}
