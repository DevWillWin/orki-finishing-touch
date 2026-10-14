# Offline Bodo TTS and Perchance image generation: engineering notes

Companion to the fixes for "offline TTS freezes / closes the app" and "Perchance reports a DNS error".
Everything below was verified against the real artifacts or live services where stated; anything not
verifiable from CI is listed under *Open items*.

## 1. Offline neural TTS

**Why it froze.** `OrkiViewModel.playTts()` runs inside `viewModelScope.launch {}` (Main). It called
`getOrInitNeuralEngine()`, a *non-suspending* function that loaded the 123 MB ONNX model (file reads,
`System.loadLibrary`, `OrtEnvironment`, `createSession`) **on the main thread**; only inference was moved to
`Dispatchers.Default` afterwards. Android's 5 s input timeout turns that into "App isn't responding".
Related defects fixed on the way: the engine held `synchronized(initLock)` for the whole inference
(`close()` on main could block behind it), tensors/results were closed only on the success path, only
`Exception` was caught (`UnsatisfiedLinkError`/OOM crashed the app), an init failure left the player
loading forever, taps were never superseded, and speaker selection used `voice.contains("male")`, which
also matches `female_*` (both voices used speaker 5).

**Design** (`data/audio/`):

| Piece | Role |
|---|---|
| `BodoNeuralTtsEngine` | single-flight init (IO), Mutex-serialized inference on one compute thread, `RunOptions.setTerminate` on cancel, non-blocking `close()`, atomic WAV output, `TtsException` for every failure |
| `OrtTtsBackend` | the only code that touches `ai.onnxruntime`; reads the contract from the loaded model; closes every tensor/result in `finally` |
| `TtsInference` | `ModelContractValidator`: input names/dtypes/ranks + `sample_rate`/`add_blank`/`n_speakers`/`num_emotions` validated at load, never guessed |
| `BodoTtsText` | speakers (BRX_F=4, BRX_M=5), tokenizer, grapheme-safe chunker |
| `WavAudio` | streamed 16-bit mono PCM WAV with patched header |
| `BodoVoicePackManager` | download pinned to an immutable Hub revision, size + SHA-256 checked while streaming, installed atomically |
| `EngineGuard` | crash marker: an interrupted native run is reported once at the next start |

Offline mode never calls the online service. In `auto` mode an offline failure is shown to the user and the
online voice is used visibly.

**Verified model contract** (exporter `export_onnx_vits_rasa_13.py`, sherpa-onnx PR #3849, and the real
file loaded with ORT 1.31 in CI): inputs `x` int64 `[N,L]`, `x_length` int64 `[N]`, `noise_scale`,
`length_scale`, `noise_scale_w` float32 **rank-0**, `sid` int64 `[N]`, `emotion_id` int64 `[N]`; output
`y` float32 `[N,1,T]`; metadata `add_blank=1`, `n_speakers=1024`, `num_emotions=32`, and
**`sample_rate=24000`** (the model card and the old hard-coded constant said 22050, which played audio
about 8 % too slow and lower in pitch). A rank-0 declaration accepts both rank-0 and `[1]` feeds, but a `[1]`
declaration rejects rank-0, so shapes follow the declared contract.

**Measured on the real model** (ORT 1.31, x86 CI runner, 2 threads, `BASIC_OPT`):

| | |
|---|---|
| load | ~0.4-0.5 s, +185 MB resident |
| real-time factor | 0.17-0.4 |
| transient memory per chunk (3 runs, about ±40 MB noise) | 40 chars +0..20 MB, 80 +80..90, 120 +120..165, 180 +200..250, 240 +330, 320 +370..460 |

Hence 120-character chunks, a 1000-character cap per request (reported as truncated, never silent), and
free-RAM pre-flight thresholds of 400 MB (load) / 300 MB (synthesis). These are heuristics: phones will
differ, so retune with `RealVoicePackSmokeTest` on hardware.

**Opt-in real-pack smoke test**: `ORKI_REAL_VOICE_PACK_DIR=/dir/with/model.onnx+tokens.txt gradle testDebugUnitTest --tests '*RealVoicePackSmokeTest*'`
(skipped otherwise). It checks the contract, vocabulary coverage of the normalizer, audio plausibility,
female-vs-male pitch (speaker 4 measured ~280 Hz, speaker 5 ~160 Hz) and memory per stage.

## 2. Perchance image generation

**Findings.** (a) `PerchanceImageEngine` was never called by `ImageGenerationService`. (b) It loaded the stock
generator *UI* page with a `#prompt=` fragment and waited for messages that page never sends; the documented
API is the community generator `https://perchance.org/perchance-ai-api`, which answers via
`postMessage({api:"perchance-image-api", result:{id,ok,url,...}})`. (c) Results were fetched with OkHttp
without the WebView's cookies. (d) WebView errors were only logged. (e) Live check from a neutral network
(GitHub runner): DNS, TCP and TLS succeed for every Perchance host, but **every Perchance page answers
HTTP 403 `cf-mitigated: challenge`** (Cloudflare managed challenge) and `X-Frame-Options: SAMEORIGIN`, and the
challenge page probes decoy sub-domains of `challenges.cloudflare.com` that fail with
`ERR_NAME_NOT_RESOLVED`: harmless noise that reads like a DNS failure in the debugger log. A headless browser
from a datacenter IP never cleared the check.

**Now.** Hidden WebView hosting `assets/perchance_host.html` from the same-site base URL
`https://perchance.org/` (so the frames are first-party and cookies are shared with the visible debugger),
documented protocol with origin + request-id checks, warm-up that waits for Cloudflare's clearance cookie,
fail-fast `CLOUDFLARE_CHALLENGE` when a check is not completed, typed failures (DNS / connection / TLS /
HTTP / timeout / protocol / invalid image), https-only host allow-list for the hosted image, cancel-safe
download. No TLS or certificate check is relaxed anywhere. Select it under Settings > AI image generation
engine > Perchance AI; on failure the reason is shown and Orki Image Studio is used visibly.

**Debugging.** Perchance WebView Debugger > *Diagnostics*: device/network facts (Private DNS, VPN, proxy,
WebView version), DNS -> TCP -> TLS -> HTTP checks for every host including the fallback providers, a test
generation with timeline and preview, and a copyable report. If a check needs a human, open the *Image API
page* tab, let it finish, then retry (cookies are shared).

Behaviour tests of the bridge page: `node app/src/test/js/perchance_host_test.js`.

## 3. Open items

* Not verified on a physical device: real-phone latency/memory, MediaPlayer playback of the WAV, WebView
  behaviour with Cloudflare on a residential connection.
* The Perchance generator page is third-party and may change; the code reports a protocol error instead of
  hanging if it does.
* **Security:** `UserPreferences` still ships default credentials in the APK (image Worker bearer,
  Pollinations `sk_` key, Azure/Bytez keys). Moving them to `BuildConfig` would not remove them from the
  APK; rotate them and proxy the calls server-side (the repo contains no Worker source).
