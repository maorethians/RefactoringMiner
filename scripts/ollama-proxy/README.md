# Ollama Token Tracking Proxy

This proxy acts as a middleware between your LLM client (e.g., Claude Code) and an Ollama server. It intercepts requests to track token usage and reasoning tokens, logging them to a file for analysis.

## Architecture
`Claude` $\rightarrow$ `Local Proxy (Port 11435)` $\rightarrow$ `Local Tunnel (Port 11434)` $\rightarrow$ `Remote Hosting Machine (Port 11434)` $\rightarrow$ `Ollama`

## 1. Preparation
### Install System Dependencies (Linux)
If you don't have pip installed:
```bash
sudo apt-get update && sudo apt-get install -y python3-pip
```

### Install Python Dependencies
```bash
pip install fastapi uvicorn httpx
```
*Note: If you encounter an "externally-managed-environment" error on newer Linux versions, use:*
```bash
pip install --break-system-packages fastapi uvicorn httpx
```

## 2. Running the Proxy
To run the proxy in the background so it persists after you close your terminal:

```bash
nohup python3 ollama_proxy.py > ollama_proxy.log 2>&1 &
```

### Management Commands
- **Check if running:** `ss -tuln | grep 11435`
- **View server logs:** `tail -f ollama_proxy.log`
- **Stop the proxy:** `pkill -f ollama_proxy.py`

## 3. Configuration

### Configure Claude
Update the API base URL in your Claude configuration:
- **From:** `http://localhost:11434`
- **To:** `http://localhost:11435`

### Monitor Token Usage
The proxy logs every request's token counts to a central log file:
```bash
tail -f /tmp/ollama_tokens.log
```

**Log Fields:**
- `prompt_eval_count`: Input tokens.
- `eval_count`: Output tokens.
- `reasoning_tokens_est`: Estimated reasoning tokens (based on `<think>` tags).
- `eval_duration`: Response time.
