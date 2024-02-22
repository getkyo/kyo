package kyo.llm.tools

import kyo.*
import kyo.llm.*

import sttp.client3.*

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

object Vision extends Tool:

    case class In(
        @doc("A description of the environment in which the image is displayed. " +
            "This includes the webpage or application interface, nearby visual elements, " +
            "and surrounding textual content, which may provide additional insight or " +
            "relevance to the image in question.")
        environment: String,
        @doc("The question the AI needs to answer regarding the provided image.")
        question: String,
        @doc("The URL where the image can be found.")
        imageUrl: String
    )

    type Out = String

    val info =
        Info(
            "vision_interpret_image",
            "interprets the contents of the provided image"
        )

    def run(input: In) =
        Configs.let(_.model(Model.gpt4_vision).maxTokens(Some(4000))) {
            Requests[Array[Byte]](
                _.get(uri"${input.imageUrl}")
                    .response(asByteArray)
            ).map { bytes =>
                val payload = encodeImage(bytes)
                if payload.isEmpty then
                    IOs.fail(s"Failed to encode image at ${input.imageUrl}")
                else
                    AIs.init.map { ai =>
                        ai.userMessage(
                            p"""
                Context: ${input.environment}
                Question: ${input.question}
              """,
                            s"data:image/jpeg;base64,$payload" :: Nil
                        ).andThen(ai.gen[String])
                    }
                end if
            }
        }

    private def encodeImage(bytes: Array[Byte]) =
        val inputStream   = new ByteArrayInputStream(bytes)
        val originalImage = ImageIO.read(inputStream)

        // Convert image to a type compatible with JPEG (if needed)
        val image =
            if
                originalImage.getType == BufferedImage.TYPE_INT_ARGB ||
                originalImage.getType == BufferedImage.TYPE_4BYTE_ABGR
            then
                val convertedImg = new BufferedImage(
                    originalImage.getWidth,
                    originalImage.getHeight,
                    BufferedImage.TYPE_INT_RGB
                )
                val g = convertedImg.createGraphics()
                g.drawImage(originalImage, 0, 0, null)
                g.dispose()
                convertedImg
            else
                originalImage

        val jpegStream = new ByteArrayOutputStream()
        ImageIO.write(image, "jpg", jpegStream)
        Base64.getEncoder.encodeToString(jpegStream.toByteArray())
    end encodeImage
end Vision
