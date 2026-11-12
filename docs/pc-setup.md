# PC "second opinion" setup (Ollama + Cloudflare Tunnel)

The app's **Ask my PC** button sends the photo to a vision model running in [Ollama](https://ollama.com)
on your own computer. A Cloudflare Tunnel gives it a stable web address without opening ports on your
router, and a Cloudflare Access service token makes sure only the app can use it.

Replace `ai.example.com` below with a subdomain of your own domain that isn't already in use.
A new tunnel and a new subdomain don't change any existing DNS records or tunnels.

## 1. Ollama and a vision model

1. Install Ollama for Windows from https://ollama.com/download and let it run in the tray.
2. In a terminal, pull a vision model. With a 24–32 GB GPU, good choices are:
   ```
   ollama pull qwen2.5vl:32b     # default in the app; strong at fine detail
   ollama pull gemma3:27b        # alternative
   ```
   Check it's there with `ollama list`. Ollama listens on `http://localhost:11434`.

If another app on the PC also uses the GPU, Ollama will queue or swap models, so a second opinion may
take a little longer while that app is busy.

## 2. Cloudflare Tunnel

1. In the Cloudflare dashboard open **Zero Trust → Networks → Tunnels → Create a tunnel**, choose
   **Cloudflared**, and name it e.g. `nature-ai`. Use a **separate** tunnel from any existing one.
2. Follow the Windows install command it shows (it installs `cloudflared` as a service).
3. Add a **Public hostname**:
   - Subdomain / domain: `ai` / `example.com` (your own)
   - Service: `HTTP` → `localhost:11434`
   - Under **Additional application settings → HTTP Settings**, set **HTTP Host Header** to
     `localhost:11434`. Ollama rejects requests whose Host header isn't local, so this is required.

## 3. Lock it down with Cloudflare Access

1. **Zero Trust → Access → Service auth → Service Tokens → Create service token.** Copy the
   **Client ID** and **Client Secret** (the secret is shown once).
2. **Zero Trust → Access → Applications → Add an application → Self-hosted.** Domain:
   `ai.example.com`. Add a policy with action **Service Auth** that includes the service token
   from step 1.

Now requests without the token get blocked at Cloudflare and never reach your PC.

## 4. In the app

Settings → *Second opinion from your PC*:

- Address: `https://ai.example.com`
- Vision model name: e.g. `qwen2.5vl:32b`
- Cloudflare Access client ID and secret from step 3

Tap **Test connection**. It should say the model is ready.

### Without Cloudflare

On your home Wi-Fi you can instead set Ollama to listen on the network (`OLLAMA_HOST=0.0.0.0`) and
use `http://<pc-ip>:11434` as the address, leaving the Cloudflare fields empty. This only works while
the phone is on the same network as the PC.
