package kyo.ai

import kyo.*

/** A minimal base64 image carrier for multimodal LLM messages.
  *
  * `Image` is a thin carrier of an already-base64-encoded image payload for inclusion in a user message
  * sent to a vision-capable model. It is a transport carrier only: it does not decode, resize, or validate
  * the image, and it makes no assumption about the media type beyond what the provider request layer
  * encodes (the completion backends emit a `jpeg` media-type marker around the base64 payload). The only
  * consumer reads `base64`. Build one from an existing base64 string via `Image.fromBase64`, or from raw
  * bytes via `Image.fromBytes` (which encodes via the cross-platform `kyo.Base64`). Two images compare
  * equal when their base64 content is equal (`derives CanEqual`).
  */
final case class Image private (base64: String) derives CanEqual, Schema

object Image:

    /** Wraps an already-base64-encoded payload. */
    def fromBase64(encoded: String): Image = Image(encoded)

    /** Builds an image from raw bytes, encoding via the cross-platform `kyo.Base64` (RFC 4648). */
    def fromBytes(bytes: Span[Byte]): Image = Image(Base64.encode(bytes))

end Image
