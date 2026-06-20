package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspHandlerLiftTest extends Test:

    "LspHandlerLiftTest" - {

        "error mappings are applied to the lifted route" in {
            case class MyError(msg: String) derives Schema
            val handler = LspHandler.TextDocument.completion { _ =>
                Abort.fail(LspException.Dispatch.MethodNotFound("test"))
            }.error[MyError](-32099, "my error")

            // The handler should have one error mapping
            assert(handler.errorMappings.size == 1)
            assert(handler.errorMappings(0).code == -32099)
        }

        "RequestHandler carries inSchema and outSchema" in {
            val h = LspHandler.TextDocument.completion { _ =>
                LspHandler.CompletionResult.Items(Chunk.empty)
            }
            h match
                case rh: LspHandler.RequestHandler[?, ?, ?] =>
                    assert(rh.inSchema != null)
                    assert(rh.outSchema != null)
                case _ =>
                    fail("Expected RequestHandler")
            end match
        }

        "NotificationHandler carries inSchema" in {
            val h = LspHandler.TextDocument.didOpen { _ => () }
            h match
                case nh: LspHandler.NotificationHandler[?, ?] =>
                    assert(nh.inSchema != null)
                case _ =>
                    fail("Expected NotificationHandler")
            end match
        }

        "CustomHandler carries inSchema and outSchema" in {
            val h = LspHandler.custom[String]("vendor/test")(_ => 42)
            h match
                case ch: LspHandler.CustomHandler[?, ?, ?] =>
                    assert(ch.inSchema != null)
                    assert(ch.outSchema != null)
                case _ =>
                    fail("Expected CustomHandler")
            end match
        }

    }

end LspHandlerLiftTest
