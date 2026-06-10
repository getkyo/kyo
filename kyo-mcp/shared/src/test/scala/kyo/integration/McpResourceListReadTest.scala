package kyo.integration

import kyo.*

/** Integration test: resource list and read roundtrip. */
class McpResourceListReadTest extends Test:

    private val fileUri = McpResourceUri.parse("file:///a").get
    private val tmplUri = McpResourceUri.Template.parse("file:///{x}").get

    private val resRoute = McpHandler.resource(fileUri, "a-file") {
        Chunk(McpHandler.ResourceContents.Text(fileUri, Absent, "content-a"))
    }
    private val tmplRoute = McpHandler.resourceTemplate(tmplUri, "file-tmpl") { uri =>
        Chunk(McpHandler.ResourceContents.Text(uri, Absent, "content-tmpl"))
    }

    "listResources returns page with the registered resource URI" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute, tmplRoute),
                McpClient.initUnscoped(tc, McpInfo("r"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.listResources().flatMap { page =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(page.items.size == 1)
                        assert(page.items.head.uri == fileUri)
                        assert(page.items.head.uri.asString == "file:///a")
                        assert(page.nextCursor == Absent)
                    end for
                }
            }
        }
    }

    "listResourceTemplates returns page with the registered template" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute, tmplRoute),
                McpClient.initUnscoped(tc, McpInfo("r"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.listResourceTemplates().flatMap { tmplPage =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(tmplPage.items.size == 1)
                        assert(tmplPage.items.head.uriTemplate == tmplUri)
                    end for
                }
            }
        }
    }

    "readResource returns Text contents for the registered URI" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, resRoute, tmplRoute),
                McpClient.initUnscoped(tc, McpInfo("r"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.readResource(fileUri).flatMap { contents =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(contents.size == 1)
                        contents.head match
                            case McpHandler.ResourceContents.Text(uri, _, text) =>
                                assert(uri == fileUri)
                                assert(text == "content-a")
                            case other =>
                                fail(s"expected Text contents, got $other")
                        end match
                    end for
                }
            }
        }
    }

end McpResourceListReadTest
