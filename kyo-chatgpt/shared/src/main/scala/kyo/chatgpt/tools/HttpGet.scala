package kyo.chatgpt.tools

import kyo._
import kyo.locals._
import kyo.options._
import kyo.tries._
import kyo.chatgpt.ais._
import kyo.requests._
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import scala.util.Success
import scala.util.Failure
import kyo.chatgpt.tools.Tools
import kyo.loggers.Loggers

object HttpGet {

  private val logger = Loggers.init("kyo.chatgpt.tools.HttpGet")

  val tool = Tools.init[String, String](
      "http_get",
      "returns the contents of a URL"
  ) { (ai, url) =>
    for {
      _ <- logger.debug(url)
      res <- Requests(
          _.contentType("text/html; charset=utf-8")
            .get(uri"$url")
      )
      _ <- logger.debug(res)
    } yield res
  }
}
