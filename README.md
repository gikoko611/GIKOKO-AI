# G.I KO KO AI — CREATE BEYOND LIMITS
Chat-first AI workspace for Android (Kotlin, no native libs → runs on armeabi-v7a / Redmi 9A and arm64).
POWERED BY I KO KO

## Build with GitHub Actions
1. Push this folder to a GitHub repo (branch `main`). The workflow `.github/workflows/build.yml` runs automatically.
2. Open the repo → **Actions** → latest run → **Artifacts** → `GIKOKOAI-apk` (debug + release APK).
3. For a Release page: `git tag v1.0.0 && git push origin v1.0.0` (APKs are attached automatically).

## First use
Settings → add an OpenAI-compatible provider (HTTPS base URL, API key, chat model ID).
Optional: image model (e.g. `dall-e-3`) and speech model (e.g. `tts-1`) and tick the capabilities.
Chat commands: `/image <prompt>`, `/voice <text>`.

## Implemented
Streaming chat + Markdown/code highlight, provider manager (Keystore-encrypted keys, model list, test, capability guess),
image generation, text-to-speech, image/text attachments, sandboxed HTML preview, ZIP export, history (search/rename/delete),
Files tab, SAF save, share, job states + restart recovery, stop/retry/regenerate/edit.

## Phase 2 additions\n- Media Studio: local audio playback, provider-based speech-to-text using `/audio/transcriptions`, local video metadata inspection and first-frame preview, and PDF export with Unicode grapheme-aware line wrapping.\n- Open from the new STUDIO bottom navigation tab. Audio transcription requires an active OpenAI-compatible provider that supports the transcription endpoint and a compatible transcription model (default `whisper-1`). Video inspection and PDF creation work locally.\n\n## Not in this build (honest limits)
Video generation/editing (FFmpeg/MediaCodec), speech-to-text, Lottie/Three.js engines, WorkManager background service,
PDF generation. Video requests return a script + storyboard only. See roadmap in chat.
