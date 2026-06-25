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

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(message)s',
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler()]
)
logger = logging.getLogger("ollama_proxy")

@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.client = httpx.AsyncClient(base_url=OLLAMA_URL, timeout=httpx.Timeout(300.0, read=None))
    yield
    await app.state.client.aclose()

app = FastAPI(lifespan=lifespan)

async def proxy_request(request: Request, path: str):
    body = await request.body()

    # Log Request
    try:
        req_json = json.loads(body) if body else {}
        req_text = json.dumps(req_json, indent=2)
    except:
        req_text = body.decode("utf-8", errors="replace") if body else "Empty Body"

    client = request.app.state.client
    headers = {k: v for k, v in request.headers.items() if k.lower() != "host"}

    # Determine if streaming is requested
    is_streaming = True
    if body:
        try:
            req_json = json.loads(body)
            is_streaming = req_json.get("stream", True)
        except:
            pass

    if is_streaming:
        async def stream_generator() -> AsyncGenerator[bytes, None]:
            try:
                async with client.stream(method=request.method, url=path, content=body, headers=headers) as resp:
                    async for chunk in resp.aiter_bytes():
                        if chunk:
                            chunk_text = chunk.decode('utf-8', errors='replace')
                            if '"type":"message_delta"' in chunk_text:
                                logger.info(f"<-- RESPONSE CHUNK: {chunk_text}")
                            yield chunk
            except Exception as e:
                logger.exception(f"Error during streaming to {path}: {e}")
                yield json.dumps({"error": str(e)}).encode("utf-8")

        return StreamingResponse(stream_generator(), media_type="application/x-ndjson")
    else:
        try:
            resp = await client.request(method=request.method, url=path, content=body, headers=headers)
            res_content = resp.content
            res_text = res_content.decode("utf-8", errors="replace")
            try:
                res_text = json.dumps(json.loads(res_text), indent=2)
            except:
                pass
            logger.info(f"<-- RESPONSE {resp.status_code}\n{res_text}")
            return Response(content=res_content, status_code=resp.status_code, headers=dict(resp.headers))
        except Exception as e:
            logger.exception(f"Error during request to {path}: {e}")
            return Response(content=str(e), status_code=500)

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
async def catch_all(request: Request, path: str):
    return await proxy_request(request, f"/{path}")

if __name__ == "__main__":
    logger.info(f"Ollama Proxy: {OLLAMA_URL} <-> port {PROXY_PORT}")
    uvicorn.run(app, host="0.0.0.0", port=PROXY_PORT)
