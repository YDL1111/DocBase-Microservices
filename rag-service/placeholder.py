from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/health", "/info"):
            self.send_error(404)
            return
        payload = {
            "service": "rag-service",
            "status": "UP",
            "stage": "placeholder",
            "model": os.getenv("DEEPSEEK_MODEL", "keep-existing-deepseek-model"),
            "embeddingModel": "BAAI/bge-m3",
        }
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print("%s - %s" % (self.address_string(), format % args), flush=True)


if __name__ == "__main__":
    port = int(os.getenv("SERVER_PORT", "8090"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
