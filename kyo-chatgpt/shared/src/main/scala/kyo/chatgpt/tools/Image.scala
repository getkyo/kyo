package kyo.chatgpt.tools

import kyo._
import kyo.requests._
import kyo.chatgpt.ais._
import kyo.chatgpt.contexts._
import kyo.chatgpt.configs._
import kyo.lists.Lists
import java.util.Base64
import sttp.client3._
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.Base64
import sttp.client3._
import sttp.client3.ziojson._
import zio.json._
import scala.concurrent.duration.Duration

object Image {

  import internal._

  case class Task(
      @desc("A text description of the desired image")
      prompt: String,
      @desc("The quality of the image that will be generated. hd " +
        "creates images with finer details and greater consistency " +
        "across the image.")
      hdQuality: Boolean,
      @desc("Must be one of 1024x1024, 1792x1024, or 1024x1792")
      size: String,
      @desc("The style of the generated images. Must be one of vivid " +
        "or natural. Vivid causes the model to lean towards generating " +
        "hyper-real and dramatic images. Natural causes the model to produce " +
        "more natural, less hyper-real looking images.")
      style: String
  )

  val tool = Tools.init[Task, String](
      "image_generation",
      "Generates an image via DALL-E and returns its URL"
  ) { (_, task) =>
    val req = Request(
        task.prompt,
        if (task.hdQuality) "hd" else "standard",
        task.size,
        task.style
    )
    Configs.apiKey.map { key =>
      Requests[Response](
          _.contentType("application/json")
            .header("Authorization", s"Bearer $key")
            .post(uri"https://api.openai.com/v1/images/generations")
            .body(req)
            .readTimeout(Duration.Inf)
            .response(asJson[Response])
      ).map { resp =>
        resp.data.headOption.map(_.url)
          .getOrElse(AIs.fail[String]("Can't find the generated image URL."))
      }
    }
  }

  object internal {
    case class Data(url: String)
    case class Request(
        prompt: String,
        quality: String,
        size: String,
        style: String,
        n: Int = 1,
        model: String = "dall-e-3"
    )
    case class Response(
        created: Int,
        data: List[Data]
    )

    implicit val requestEncoder: JsonEncoder[Request]   = DeriveJsonEncoder.gen[Request]
    implicit val dataDecoder: JsonDecoder[Data]         = DeriveJsonDecoder.gen[Data]
    implicit val responseDecoder: JsonDecoder[Response] = DeriveJsonDecoder.gen[Response]
  }
}
