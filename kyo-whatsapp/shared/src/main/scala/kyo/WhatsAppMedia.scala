package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec

/** Media-API namespace for the WhatsApp Cloud API media upload and download surface.
  * Groups the `Source` ADT (id-XOR-link), the typed `MediaType` MIME vocabulary, the
  * `MediaInfo` retrieve-url response, and the five media operations.
  *
  * Operations:
  *   - `upload` POSTs a multipart form to the phone-number media endpoint and returns
  *     the assigned `WhatsAppId.MediaId`
  *   - `resolveUrl` GETs the media endpoint to retrieve the temporary pre-signed URL
  *     and stored metadata as a `MediaInfo`
  *   - `download` fuses `resolveUrl` and `downloadFrom` into a single call by id
  *   - `downloadFrom` GETs the bytes from an already-resolved `MediaInfo.url`
  *   - `delete` DELETEs a previously uploaded asset and consumes the `{success:true}`
  *     acknowledgment
  *
  * All operations require a bound `WhatsAppConfig` (via `WhatsApp.let`) and abort
  * with a typed `WhatsAppError` leaf on Graph API errors, transport failures, or
  * structurally unparseable responses. The `Source` ADT enforces id-XOR-link at
  * construction so both-or-neither is unrepresentable.
  */
object WhatsAppMedia:

    /** A media reference: either a previously uploaded asset id or a public link. Modeled
      * as a sealed union so "both id and link" and "neither" are unrepresentable (the
      * Cloud API rejects both/neither); a caller picks exactly one at construction.
      */
    sealed trait Source derives CanEqual
    object Source:
        final case class ById(id: WhatsAppId.MediaId) extends Source derives CanEqual
        final case class ByLink(link: String)         extends Source derives CanEqual

    /** The typed MIME vocabulary. Each case object carries its `mime` string; `Other`
      * is the forward-spec escape for a MIME the enumeration does not name.
      */
    sealed trait MediaType derives CanEqual:
        def mime: String

    object MediaType:
        case object ImageJpeg extends MediaType:
            def mime = "image/jpeg"
        case object ImagePng extends MediaType:
            def mime = "image/png"
        case object AudioAac extends MediaType:
            def mime = "audio/aac"
        case object AudioAmr extends MediaType:
            def mime = "audio/amr"
        case object AudioMp3 extends MediaType:
            def mime = "audio/mpeg"
        case object AudioMp4 extends MediaType:
            def mime = "audio/mp4"
        case object AudioOgg extends MediaType:
            def mime = "audio/ogg"
        case object VideoMp4 extends MediaType:
            def mime = "video/mp4"
        case object Video3gp extends MediaType:
            def mime = "video/3gp"
        case object DocumentPdf extends MediaType:
            def mime = "application/pdf"
        case object DocumentText extends MediaType:
            def mime = "text/plain"
        case object StickerWebp extends MediaType:
            def mime = "image/webp"
        final case class Other(mime: String) extends MediaType
    end MediaType

    /** The retrieve-url response: the temporary url plus the stored media metadata. */
    final case class MediaInfo(id: WhatsAppId.MediaId, url: String, mimeType: String, sha256: String, fileSize: Long)
        derives CanEqual

    /** Uploads a media asset via a three-part multipart POST and returns the assigned media
      * id. The request body contains three named parts: `messaging_product` with value
      * "whatsapp", `type` with the MIME string, and `file` with the bytes, an optional
      * filename (defaults to a type-derived name when absent), and the content-type. The
      * upload endpoint is under the configured phone-number id. Returns the decoded
      * `WhatsAppId.MediaId` from the `{id}` response field. Aborts with a typed `WhatsAppError`
      * leaf on Graph API errors, transport failures, or a response that does not carry an `id`
      * field.
      */
    def upload(bytes: Span[Byte], mediaType: MediaType, filename: Maybe[String] = Absent)(
        using Frame
    ): WhatsAppId.MediaId < (Async & Abort[WhatsAppError]) =
        WhatsApp.use { c =>
            val route = HttpRoute.postRaw(s"${c.apiVersion}/${c.phoneNumberId.value}/media")
                .request(_.bodyMultipart)
                .response(_.bodyBinary)
            val parts = Seq(
                HttpRequest.Part(
                    "messaging_product",
                    Absent,
                    Absent,
                    Span.from("whatsapp".getBytes("UTF-8"))
                ),
                HttpRequest.Part(
                    "type",
                    Absent,
                    Absent,
                    Span.from(mediaType.mime.getBytes("UTF-8"))
                ),
                HttpRequest.Part(
                    "file",
                    Present(filename.getOrElse(defaultName(mediaType))),
                    Present(mediaType.mime),
                    bytes
                )
            )
            val parsedUrl: Result[WhatsAppError, HttpUrl] = HttpUrl.parse(uploadUrl(c)) match
                case Result.Success(u) => Result.Success(u)
                case Result.Failure(e) => Result.fail(WhatsAppCodec.mapError(e))
                case Result.Panic(t)   => Result.panic(t)
            Abort.get(parsedUrl).map { uploadHttpUrl =>
                val request = HttpRequest.postRaw(uploadHttpUrl)
                    .addField("body", parts)
                    .addHeader("Authorization", s"Bearer ${c.token}")
                Abort.runWith[HttpException](
                    HttpClient.use(_.sendWith(route, request) { resp =>
                        if !resp.status.isSuccess then
                            resp.rawBody match
                                case Present(b) =>
                                    Abort.fail(HttpStatusException(resp.status, "POST", uploadHttpUrl.baseUrl, b))
                                case Absent =>
                                    Abort.fail(HttpStatusException(resp.status, "POST", uploadHttpUrl.baseUrl))
                        else resp.fields.body
                    })
                ) {
                    case Result.Success(respBytes) => Abort.get(WhatsAppCodec.decodeMediaId(respBytes))
                    case Result.Failure(e)         => Abort.fail(WhatsAppCodec.mapError(e))
                    case Result.Panic(ex)          => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
                }
            }
        }

    /** Retrieves the temporary download URL and metadata for a media id. Issues a GET to
      * the media endpoint at the graph root (not under the phone-number id). The response
      * carries a temporary `url` valid for approximately five minutes, along with MIME type,
      * SHA-256 hash, file size, and the media id. Aborts with a typed `WhatsAppError` leaf
      * on Graph API errors, transport failures, or a structurally unparseable response body.
      */
    def resolveUrl(id: WhatsAppId.MediaId)(using Frame): MediaInfo < (Async & Abort[WhatsAppError]) =
        WhatsApp.use { c =>
            Abort.runWith[HttpException](HttpClient.getBinary(mediaUrl(c, id), headers = bearer(c))) {
                case Result.Success(respBytes) => Abort.get(WhatsAppCodec.decodeMediaInfo(respBytes))
                case Result.Failure(e)         => Abort.fail(WhatsAppCodec.mapError(e))
                case Result.Panic(ex)          => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
            }
        }

    /** Resolves the temporary url then downloads the authenticated bytes in a single call,
      * fusing the url-resolution and the download into one. Equivalent to
      * `resolveUrl(id).map(downloadFrom)`. Returns the raw media bytes byte-identical to
      * what the Cloud API stored. Aborts with a typed `WhatsAppError` leaf on any error in
      * either the url-resolution or the download step.
      */
    def download(id: WhatsAppId.MediaId)(using Frame): Span[Byte] < (Async & Abort[WhatsAppError]) =
        resolveUrl(id).map(downloadFrom)

    /** Downloads the bytes from an already-resolved `MediaInfo`. The `info.url` is the
      * temporary pre-signed URL from a prior `resolveUrl` call; it is valid for approximately
      * five minutes. Sends the bearer token and a `User-Agent` header on the download
      * request. Returns the raw media bytes byte-identical to what the server serves. Aborts
      * with a typed `WhatsAppError` leaf on Graph API errors or transport failures.
      */
    def downloadFrom(info: MediaInfo)(using Frame): Span[Byte] < (Async & Abort[WhatsAppError]) =
        WhatsApp.use { c =>
            val headers = bearer(c) :+ ("User-Agent" -> "kyo-whatsapp")
            Abort.runWith[HttpException](HttpClient.getBinary(info.url, headers = headers)) {
                case Result.Success(respBytes) => respBytes
                case Result.Failure(e)         => Abort.fail(WhatsAppCodec.mapError(e))
                case Result.Panic(ex)          => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
            }
        }

    /** Deletes a previously uploaded media asset. Issues a DELETE to the media endpoint at
      * the graph root. Consumes the `{success:true}` acknowledgment response as `Unit`; a
      * `{success:false}` or an unparseable body surfaces as a `WhatsAppError.DecodeError`.
      * Aborts with a typed `WhatsAppError` leaf on Graph API errors, transport failures, or
      * decode failures.
      */
    def delete(id: WhatsAppId.MediaId)(using Frame): Unit < (Async & Abort[WhatsAppError]) =
        WhatsApp.use { c =>
            Abort.runWith[HttpException](HttpClient.deleteBinary(mediaUrl(c, id), headers = bearer(c))) {
                case Result.Success(respBytes) => Abort.get(WhatsAppCodec.decodeSuccess(respBytes))
                case Result.Failure(e)         => Abort.fail(WhatsAppCodec.mapError(e))
                case Result.Panic(ex)          => Abort.get(WhatsAppCodec.mapTransportPanic(ex))
            }
        }

    private def mediaUrl(c: WhatsAppConfig, id: WhatsAppId.MediaId): String =
        s"${c.baseUrl}/${c.apiVersion}/${id.value}"

    private def uploadUrl(c: WhatsAppConfig): String =
        s"${c.baseUrl}/${c.apiVersion}/${c.phoneNumberId.value}/media"

    private def bearer(c: WhatsAppConfig): Seq[(String, String)] =
        Seq("Authorization" -> s"Bearer ${c.token}")

    private def defaultName(mediaType: MediaType): String = mediaType match
        case MediaType.ImageJpeg   => "file.jpg"
        case MediaType.ImagePng    => "file.png"
        case MediaType.VideoMp4    => "file.mp4"
        case MediaType.AudioMp3    => "file.mp3"
        case MediaType.DocumentPdf => "file.pdf"
        case _                     => "file.bin"

end WhatsAppMedia
