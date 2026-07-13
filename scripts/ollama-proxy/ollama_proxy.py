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

def update_token_log(in_tokens, out_tokens):
    try:
        current_in, current_out = 0, 0
        if os.path.exists(LOG_FILE):
            with open(LOG_FILE, "r") as f:
                lines = f.readlines()
                if lines:
                    last_line = lines[-1].strip()
                    if last_line:
                        current_in, current_out = map(int, last_line.split())
    except Exception:
        current_in, current_out = 0, 0

    new_in = current_in + in_tokens
    new_out = current_out + out_tokens

    with open(LOG_FILE, "a") as f:
        f.write(f"{new_in} {new_out}\n")

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
            final_usage = {"input_tokens": 0, "output_tokens": 0}
            try:
                async with client.stream(method=request.method, url=path, content=body, headers=headers) as resp:
                    async for chunk in resp.aiter_bytes():
                        if chunk:
                            chunk_text = chunk.decode('utf-8', errors='replace')
                            # 1. OpenAI-style streaming
                            if '"type":"message_delta"' in chunk_text:
                                try:
                                    if 'data: ' in chunk_text:
                                        data_str = chunk_text.split('data: ', 1)[1]
                                        data_json = json.loads(data_str)
                                        if 'usage' in data_json:
                                            final_usage['input_tokens'] = data_json['usage'].get('input_tokens', 0)
                                            final_usage['output_tokens'] = data_json['usage'].get('output_tokens', 0)
                                except Exception:
                                    pass
                            # 2. Native Ollama streaming
                            elif chunk_text.strip().startswith('{'):
                                try:
                                    data_json = json.loads(chunk_text)
                                    if 'prompt_eval_count' in data_json:
                                        final_usage['input_tokens'] = data_json.get('prompt_eval_count', 0)
                                        final_usage['output_tokens'] = data_json.get('eval_count', 0)
                                except Exception:
                                    pass
                            yield chunk
                update_token_log(final_usage['input_tokens'], final_usage['output_tokens'])
            except Exception as e:
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

            # LOG TOKENS
            try:
                res_json = resp.json()
                in_tokens = 0
                out_tokens = 0
                if "usage" in res_json:
                    usage = res_json["usage"]
                    in_tokens = usage.get("input_tokens", 0)
                    out_tokens = usage.get("output_tokens", 0)
                elif "prompt_eval_count" in res_json:
                    in_tokens = res_json.get("prompt_eval_count", 0)
                    out_tokens = res_json.get("eval_count", 0)
                update_token_log(in_tokens, out_tokens)
            except Exception:
                pass

            # REPLICATE EVERYTHING: Return the exact content, status, and headers
            return Response(
                content=resp.content,
                status_code=resp.status_code,
                headers={k: v for k, v in resp.headers.items() if k.lower() not in HOP_BY_HOP}
            )
        except Exception as e:
            return Response(content=str(e), status_code=500)

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
async def catch_all(request: Request, path: str):
    return await proxy_request(request, f"/{path}")

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PROXY_PORT, proxy_headers=False)
