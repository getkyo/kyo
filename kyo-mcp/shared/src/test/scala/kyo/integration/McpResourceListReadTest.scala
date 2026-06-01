package kyo.integration

import kyo.*

/** Integration test: resource list and read roundtrip (T-016, INV-022, INV-023). */
class McpResourceListReadTest extends Test:

    private val fileUri = McpResourceUri.parse("file:///a").get
    private val tmplUri = McpResourceUri.Template.parse("file:///{x}").get

    private val resRoute = McpRoute.resource(fileUri, "a-file").handler { uri =>
        Chunk(McpRoute.ResourceContents.Text(uri, Absent, "content-a"))
    }
    private val tmplRoute = McpRoute.resourceTemplate(tmplUri, "file-tmpl").handler { uri =>
        Chunk(McpRoute.ResourceContents.Text(uri, Absent, "content-tmpl"))
    }

    "listResources returns page with the registered resource URI (T-016, INV-022, INV-023)" in run {
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

    "listResourceTemplates returns page with the registered template (T-016, INV-022)" in run {
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

    "readResource returns Text contents for the registered URI (T-016, INV-022)" in run {
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
                            case McpRoute.ResourceContents.Text(uri, _, text) =>
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
