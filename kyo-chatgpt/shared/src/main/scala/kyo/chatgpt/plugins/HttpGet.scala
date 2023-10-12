package kyo.chatgpt.plugins

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

object HttpGet {

  val plugin = Plugins.init[String, String](
      "http_get",
      "returns the contents of a URL"
  ) { (ai, url) =>
    Requests(
        _.contentType("text/html; charset=utf-8")
          .get(uri"$url")
    ).map(_.body).map {
      case Left(error) =>
        AIs.fail("BraveSearch plugin failed: " + error)
      case Right(value) =>
        value
    }
  }
}
