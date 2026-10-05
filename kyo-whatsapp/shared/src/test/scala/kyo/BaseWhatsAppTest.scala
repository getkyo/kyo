package kyo

import kyo.charset.Charset
import kyo.crypto.Hmac
import kyo.internal.charset.Utf8
import scala.reflect.ClassTag

abstract class BaseWhatsAppTest extends kyo.test.Test[Any]:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    def tokenOf(text: String)(using Frame): WhatsAppToken = WhatsAppToken.init(text).getOrThrow

    def appSecretOf(text: String)(using Frame): WhatsAppAppSecret = WhatsAppAppSecret.init(text).getOrThrow

    def verifyTokenOf(text: String)(using Frame): WhatsAppVerifyToken = WhatsAppVerifyToken.init(text).getOrThrow

    def pathOf(text: String)(using Frame): WhatsAppPath = WhatsAppPath.init(text).getOrThrow

    def webhookConfigOf(secret: String, verify: String, path: String = "")(using Frame): WhatsAppWebhookConfig =
        WhatsAppWebhookConfig.init(appSecretOf(secret), verifyTokenOf(verify), path).getOrThrow

    def configOf(
        token: String,
        phoneNumberId: WhatsAppId.PhoneNumberId,
        baseUrl: HttpUrl = WhatsAppConfig.GraphApi,
        maxResponseLength: ByteSize = 100.mb
    )(using Frame): WhatsAppConfig =
        WhatsAppConfig.init(tokenOf(token), phoneNumberId, baseUrl = baseUrl, maxResponseLength = maxResponseLength).getOrThrow

    def sendResultOf(contacts: Chunk[WhatsAppSendResult.Contact], messages: Chunk[WhatsAppSendResult.Message]): WhatsAppSendResult =
        WhatsAppSendResult.init(contacts, messages).getOrElse(throw new IllegalArgumentException("a send result needs a message"))

    def epoch(seconds: Long): Instant = Instant.Epoch + seconds.seconds

    def utf8(text: String): Span[Byte] = Utf8.encode(text)

    def textOf(bytes: Span[Byte]): String = Charset.Utf8.decode(bytes)

    def byteSpan(values: Int*): Span[Byte] = Span.from(values.map(_.toByte))

    /** The lowercase hex `X-Hub-Signature-256` value Meta sends for `body` under `secret`. */
    def signatureOf(secret: String, body: Span[Byte]): String = s"sha256=${Hex.encode(Hmac.sha256(utf8(secret), body))}"

    /** A JSON text as a value in which an object's keys are sorted, so two texts compare equal when they hold the same objects in any
      * key order. kyo-schema writes a renamed field after the others, so an encoding's key order is not Meta's.
      */
    def unordered(text: String)(using Frame): Structure.Value =
        def sorted(value: Structure.Value): Structure.Value =
            value match
                case Structure.Value.Record(fields)  => Structure.Value.Record(fields.map((k, v) => (k, sorted(v))).sortBy(_._1))
                case Structure.Value.Sequence(elems) => Structure.Value.Sequence(elems.map(sorted))
                case other                           => other
        sorted(Json.decode[Structure.Value](text).getOrThrow)
    end unordered

    /** The leaf a failed result holds, of type `L`; fails the test on any other outcome. */
    def failureOf[L](result: Result[Any, Any])(using ct: ClassTag[L], frame: Frame, scope: kyo.test.AssertScope): L =
        result match
            case Result.Failure(l: L) => l
            case other                => fail(s"expected a failure of ${ct.runtimeClass.getSimpleName}, got: $other")

    /** A wrapped cause, of type `C`; fails the test on any other cause. */
    def causeOf[C](cause: Throwable)(using ct: ClassTag[C], frame: Frame, scope: kyo.test.AssertScope): C =
        cause match
            case c: C  => c
            case other => fail(s"expected a ${ct.runtimeClass.getSimpleName} cause, got: $other")

end BaseWhatsAppTest

object BaseWhatsAppTest:

    /** Every text a failure renders: `toString` and `getMessage` of the failure and of each cause in its chain. */
    def renderings(t: Throwable): Chunk[String] =
        @scala.annotation.tailrec
        def loop(current: Throwable, acc: Chunk[String]): Chunk[String] =
            val here = acc :+ current.toString :+ String.valueOf(current.getMessage)
            val next = current.getCause
            if (next eq null) || (next eq current) then here else loop(next, here)
        end loop
        loop(t, Chunk.empty)
    end renderings

    /** A `Log` that records the text of every message at every level, and the rendering of every throwable passed with one. */
    final class RecordingLog(sink: AtomicRef.Unsafe[Chunk[String]]) extends Log.Unsafe:
        private def record(msg: => String, t: Maybe[Throwable])(using AllowUnsafe): Unit =
            val texts = msg +: t.fold(Chunk.empty[String])(renderings)
            discard(sink.updateAndGet(_ ++ texts))
        def level: Log.Level                                                       = Log.Level.trace
        def name: String                                                           = "recording"
        def withName(name: String): Log.Unsafe                                     = this
        def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
        def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
        def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg, Absent)
        def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg, Present(t))
        def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record(msg, Absent)
        def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record(msg, Present(t))
        def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record(msg, Absent)
        def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record(msg, Present(t))
    end RecordingLog

end BaseWhatsAppTest
