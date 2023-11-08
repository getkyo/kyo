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

object Vision {

  case class Task(
      @desc("The question the AI needs to asnwer regarding the provided image.")
      question: String,
      @desc("The image's URL.")
      imageUrl: String
  )

  val tool = Tools.init[Task, String](
      "vision_interpret_image",
      "interprets the contents of the provided image"
  ) { (_, task) =>
    Tools.disabled {
      Configs.let(_.model(Model.gpt4_vision).maxTokens(Some(4000))) {
        Requests(
            _.get(uri"${task.imageUrl}")
              .response(asByteArray)
        ).map { bytes =>
          val payload = encodeImage(bytes)
          val msg =
            Message.UserMessage(
                task.question,
                s"data:image/jpeg;base64,$payload" :: Nil
            )
          AIs.init.map { ai =>
            ai.addMessage(msg).andThen(ai.ask)
          }
        }
      }
    }
  }

  private def encodeImage(bytes: Array[Byte]) = {
    val inputStream = new ByteArrayInputStream(bytes)
    val image       = ImageIO.read(inputStream)
    val jpegStream  = new ByteArrayOutputStream()
    ImageIO.write(image, "jpg", jpegStream)
    Base64.getEncoder.encodeToString(jpegStream.toByteArray())
  }
}
