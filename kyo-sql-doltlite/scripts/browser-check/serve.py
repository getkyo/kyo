"""Static server for the browser check.

The stock http.server is unusable here on two counts: it guesses MIME types from a system table that does not
always carry `.mjs`, and a browser refuses a module whose Content-Type is not a JavaScript type; and it honours
conditional requests, which starves the page of its `.wasm`. It also sends no cross-origin isolation headers.
"""

import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".mjs": "text/javascript",
        ".js": "text/javascript",
        ".wasm": "application/wasm",
    }

    def end_headers(self):
        # Cross-origin isolation, which OPFS REQUIRES: a browser reaches sqlite3.oo1.OpfsDb only on an isolated
        # page, and a page that is not isolated gets memory storage and nothing else.
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        self.send_header("Cross-Origin-Resource-Policy", "cross-origin")
        # No caching. A warm cache answers 304 for the module, the page then never fetches the .wasm, and the
        # module never initializes.
        self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()

    def send_head(self):
        # Defeat the conditional request entirely, so a 304 can never reach the browser.
        self.headers.replace_header("If-Modified-Since", "") if "If-Modified-Since" in self.headers else None
        if "If-None-Match" in self.headers:
            del self.headers["If-None-Match"]
        return super().send_head()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
