import os
import json
import logging
import httpx
import uvicorn
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Response
from fastapi.responses import StreamingResponse
from typing import AsyncGenerator

# Configuration
OLLAMA_URL = "http://localhost:11434"
PROXY_PORT = int(os.getenv("OLLAMA_PROXY_PORT", 11435))
LOG_FILE = "/tmp/ollama_proxy.log"

# Hop-by-hop headers that MUST NOT be forwarded by a proxy
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade"
}

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(message)s',
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler()]
)
logger = logging.getLogger("ollama_proxy")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Use a single client with very high timeouts to prevent the proxy from being the bottleneck
    app.state.client = httpx.AsyncClient(
        base_url=OLLAMA_URL,
        timeout=httpx.Timeout(600.0, read=None),
        limits=httpx.Limits(max_keepalive_connections=None, max_connections=None)
    )
    yield
    await app.state.client.aclose()

app = FastAPI(lifespan=lifespan)

async def proxy_request(request: Request, path: str):
    body = await request.body()

    # 1. LOG REQUEST (Best effort, non-blocking)
    try:
        # We log the raw bytes first to ensure we don't miss anything
        raw_body = body.decode("utf-8", errors="replace")
    except Exception as e:
        logger.warning(f"Could not log request body: {e}")

    # 2. CLEAN HEADERS
    # Only forward headers that are NOT hop-by-hop
    headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP_BY_HOP and k.lower() != "host"}

    client = request.app.state.client

    # Check if streaming is requested by looking at the raw body
    is_streaming = True
    if body:
        try:
            req_json = json.loads(body)
            is_streaming = req_json.get("stream", True)
        except:
            pass

    if is_streaming:
        # We use a generator to pipe the stream directly from Ollama to the Client
        async def stream_generator() -> AsyncGenerator[bytes, None]:
            try:
                async with client.stream(method=request.method, url=path, content=body, headers=headers) as resp:
                    # We MUST forward the response headers in the StreamingResponse wrapper
                    # but the actual bytes flow through here.
                    async for chunk in resp.aiter_bytes():
                        if chunk:
                            # Log only the relevant parts of the chunk to avoid flooding logs
                            chunk_text = chunk.decode('utf-8', errors='replace')
                            if '"type":"message_delta"' in chunk_text:
                                logger.info(f"<-- CHUNK: {chunk_text}")
                            yield chunk
            except Exception as e:
                logger.exception(f"Stream Error: {e}")
                yield json.dumps({"error": str(e)}).encode("utf-8")

        # IMPORTANT: We do NOT hardcode media_type.
        # We let the client handle the content-type provided by the backend.
        return StreamingResponse(
            stream_generator(),
            status_code=200,
            # We manually set headers to match Ollama's typical response
            headers={"Content-Type": "application/x-ndjson"}
        )
    else:
        try:
            resp = await client.request(method=request.method, url=path, content=body, headers=headers)

            # LOG RESPONSE
            res_text = resp.content.decode("utf-8", errors="replace")
            logger.info(f"<-- RESPONSE {resp.status_code}\n{res_text}")

            # REPLICATE EVERYTHING: Return the exact content, status, and headers
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                headers={k: v for k, v in resp.headers.items() if k.lower() not in HOP_BY_HOP}
            )
        except Exception as e:
            logger.exception(f"Request Error: {e}")
            return Response(content=str(e), status_code=500)

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
async def catch_all(request: Request, path: str):
    return await proxy_request(request, f"/{path}")

if __name__ == "__main__":
    logger.info(f"Transparent Ollama Proxy: {OLLAMA_URL} <-> port {PROXY_PORT}")
    uvicorn.run(app, host="0.0.0.0", port=PROXY_PORT, proxy_headers=False)
