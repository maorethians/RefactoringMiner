import os
import json
import logging
import httpx
import uvicorn
import re
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Response
from fastapi.responses import StreamingResponse
from typing import AsyncGenerator

# Configuration
OLLAMA_URL = "http://localhost:11434"
PROXY_PORT = int(os.getenv("OLLAMA_PROXY_PORT", 11435))
LOG_FILE = "/tmp/ollama_tokens.log"

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(LOG_FILE),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("ollama_proxy")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Set a high timeout to prevent indefinite hangs, but allow long reads
    timeout = httpx.Timeout(300.0, read=None)
    app.state.client = httpx.AsyncClient(base_url=OLLAMA_URL, timeout=timeout)
    yield
    await app.state.client.aclose()

app = FastAPI(lifespan=lifespan)

def estimate_reasoning_tokens(text: str) -> int:
    """
    Estimates reasoning tokens by finding text between <think> and </think> tags.
    Since we don't have a tokenizer, we'll use a rough approximation: 1 token ~= 4 characters.
    """
    reasoning_content = re.findall(r'<think>(.*?)</think>', text, re.DOTALL)
    if not reasoning_content:
        return 0

    full_reasoning_text = "".join(reasoning_content)
    # Rough approximation: 4 chars per token
    return len(full_reasoning_text) // 4

async def log_metrics(endpoint: str, payload: dict, metrics: dict, reasoning_text: str = ""):
    reasoning_tokens = estimate_reasoning_tokens(reasoning_text)

    log_data = {
        "endpoint": endpoint,
        "model": payload.get("model"),
        "prompt_eval_count": metrics.get("prompt_eval_count"),
        "eval_count": metrics.get("eval_count"),
        "eval_duration": metrics.get("eval_duration"),
        "reasoning_tokens_est": reasoning_tokens,
    }
    logger.info(f"Metrics: {json.dumps(log_data)}")

async def proxy_request(request: Request, path: str):
    # Prepare request body and headers
    body = await request.body()
    headers = dict(request.headers)
    # Remove host header to avoid conflicts with the target server
    headers.pop("host", None)

    # Parse request body for model info and streaming preference
    try:
        request_json = json.loads(body) if body else {}
    except json.JSONDecodeError:
        request_json = {}

    # Access the client from app state
    client = request.app.state.client

    # We only intercept metrics for /api/generate and /api/chat
    if path not in ["/api/generate", "/api/chat"]:
        # For other endpoints, just forward normally
        try:
            resp = await client.request(
                method=request.method,
                url=path,
                content=body,
                headers=headers,
            )
            return Response(content=resp.content, status_code=resp.status_code, headers=dict(resp.headers))
        except Exception as e:
            logger.exception(f"Error forwarding request to {path}")
            return Response(content=str(e), status_code=500)

    # For /api/generate and /api/chat, handle streaming vs non-streaming
    is_streaming = request_json.get("stream", True) # Ollama defaults to streaming: true

    if is_streaming:
        async def stream_generator() -> AsyncGenerator[bytes, None]:
            try:
                async with client.stream(
                    method=request.method,
                    url=path,
                    content=body,
                    headers=headers,
                ) as response:
                    full_content = ""
                    metrics = {}

                    async for line in response.aiter_lines():
                        if not line:
                            continue

                        # Forward the line to the client as bytes
                        line_bytes = (line + "\n").encode("utf-8")
                        yield line_bytes

                        # Accumulate content and metrics for logging
                        try:
                            chunk = json.loads(line)
                            if "response" in chunk:
                                full_content += chunk["response"]
                            elif "message" in chunk:
                                full_content += chunk["message"].get("content", "")

                            # Use "done" to finalize metrics capture
                            if chunk.get("done"):
                                metrics = chunk
                        except json.JSONDecodeError:
                            pass

                    # Log metrics after the stream is finished
                    await log_metrics(path, request_json, metrics, full_content)
            except Exception as e:
                logger.exception(f"Error during streaming request to {path}")
                error_payload = json.dumps({"error": f"Proxy Error: {str(e)}"})
                yield error_payload.encode("utf-8")

        return StreamingResponse(stream_generator(), media_type="application/x-ndjson")
    else:
        # Non-streaming response
        try:
            resp = await client.request(
                method=request.method,
                url=path,
                content=body,
                headers=headers,
            )

            try:
                response_json = resp.json()
                full_content = ""
                if "response" in response_json:
                    full_content = response_json["response"]
                elif "message" in response_json:
                    full_content = response_json["message"].get("content", "")

                await log_metrics(path, request_json, response_json, full_content)
            except json.JSONDecodeError:
                pass

            return Response(content=resp.content, status_code=resp.status_code, headers=dict(resp.headers))
        except Exception as e:
            logger.exception(f"Error during non-streaming request to {path}")
            return Response(content=str(e), status_code=500)

@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
async def catch_all(request: Request, path: str):
    # Ensure we use the leading slash for the path
    normalized_path = f"/{path}" if not path.startswith("/") else path
    return await proxy_request(request, normalized_path)

if __name__ == "__main__":
    logger.info(f"Starting Ollama Proxy on port {PROXY_PORT}, forwarding to {OLLAMA_URL}")
    uvicorn.run(app, host="0.0.0.0", port=PROXY_PORT)
