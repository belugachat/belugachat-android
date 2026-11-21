<div align="center">

# Beluga Chat

**Your keys. Every model. Zero limits.**

A free, open-source Android AI chat app that connects straight to DeepSeek, Claude, ChatGPT, Gemini, Mistral, and more using your own API key—no accounts, no monthly subscriptions, and no daily message caps.

[![Latest Release](https://img.shields.io/github/v/release/belugachat/belugachat-android?label=Download%20APK&color=3B6BFF&style=flat-square)](https://github.com/belugachat/belugachat-android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-16A34A?style=flat-square)](https://github.com/belugachat/belugachat-android/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-4B5563?style=flat-square)](./LICENSE)
[![Website](https://img.shields.io/badge/Website-belugachat.app-3B6BFF?style=flat-square)](https://belugachat.app)

[Download Latest APK](https://github.com/belugachat/belugachat-android/releases/latest) · [Website](https://belugachat.app) · [Setup Guides](https://belugachat.app/blog) · [Report a Bug](https://github.com/belugachat/belugachat-android/issues)

</div>

---

## What is Beluga Chat?

If you've ever hit a *"Server is busy"* or daily message limit in an official AI app, Beluga Chat fixes that.

Instead of paying $20/month for a single AI subscription, Beluga Chat lets you **plug in your own API key** and talk directly to the AI providers you already love. You only pay the provider for what you actually use (often just pennies per week with DeepSeek), and your chats stay 100% private on your phone.

---

## Getting Started (Takes 2 Minutes)

1. **Download the app** — Grab the latest `.apk` from [GitHub Releases](https://github.com/belugachat/belugachat-android/releases/latest) (or via [belugachat.app/download](https://belugachat.app/download)) and install it on your Android phone.
2. **Create a free/pay-as-you-go API key** — Never made an API key before? Follow our step-by-step screenshot guides:
   - [How to get a DeepSeek API Key](https://belugachat.app/blog/deepseek-api-key-guide) *(Recommended — works with DeepSeek V4.1 Flash & V4 Pro)*
   - [How to get a Mistral API Key](https://belugachat.app/blog/mistral-api-key-guide) *(Fast & free tier available)*
   - [How to get a Deploy.com API Key ($100 bonus on $5)](https://belugachat.app/blog/deploy-com-api-key-100-bonus)
3. **Paste your key & start chatting** — Open Beluga Chat, paste your key, pick a model, and you're ready to go.

> **Automatic Updates:** When a new version of Beluga Chat is released here on GitHub, the app will notify you automatically and let you update with a single tap—without losing your chats or API keys.

---

## Why People Use Beluga Chat

- **All your favorite AI models in one app** — Built-in presets for **DeepSeek** (including DeepSeek V4.1 Flash & V4 Pro reasoning support), **Anthropic Claude**, **OpenAI GPT**, **Google Gemini**, **Mistral**, **OpenRouter**, **Groq**, **xAI**, **Deploy.com**, **Z.ai**, and any custom OpenAI- or Anthropic-compatible server.
- **Calm, familiar interface** — Clean Arctic Light and neutral charcoal Dark themes designed for comfortable long-form reading, with collapsible reasoning blocks (`Thought for Xs`), crisp code blocks with one-tap copy, and formatted tables.
- **Edit, regenerate, and branch chats** — Fix a typo in an earlier message, try a different answer, flip between versions (`‹ 1/2 ›`), or branch off into a fresh conversation from any reply.
- **Photos, documents & web search** — Attach photos from your camera or gallery, PDFs, Word (`.docx`) files, or text/code files. Turn on built-in **Web Search** when you want up-to-date answers from the internet.
- **Power features when you want them** — Type `/` for autonomous `/goal` and `/deepsearch` modes, connect remote **MCP servers**, import custom **Skills**, and track your token usage in Settings.

---

## Privacy & Security

Beluga Chat has **no backend server, no user accounts, no ads, and no analytics tracking**.

- **Your API keys stay on your phone** — Encrypted with AES-GCM using a hardware-backed key in the Android Keystore, and masked immediately after saving.
- **Your chats stay on your phone** — Stored locally in an on-device database (`beluga-chat.db`) with Android cloud backup disabled.
- **Direct connection only** — Your messages go straight from your phone to the AI provider you choose. No middleman ever sees your prompts or keys.
- **100% Open Source** — Anyone can inspect the code in this repository to verify every claim above.

---

## Building from Source (For Developers)

<details>
<summary>Click to expand build instructions &amp; project structure</summary>

### Requirements
- JDK 17
- Android SDK 35 (Android 8.0 / API 26 minimum target)
- Gradle 8.10+ (included via `./gradlew`)

### Commands
```bash
./gradlew :app:testDebugUnitTest   # Run unit tests
./gradlew :app:assembleDebug       # Build debug APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleRelease     # Build minified release APK (signed if keystore.properties exists)
```

For signed release builds, create a local `keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Keep your release keystore backed up safely—future in-app updates must be signed with the same key.

### Project Structure
```text
app/src/main/java/com/belugachat/app/
├── data/      # Room database, DataStore settings, Android Keystore AES-GCM cipher, providers
├── llm/       # SSE parser, OpenAI-compatible (incl. DeepSeek reasoning) & Anthropic clients, presets
├── chat/      # ChatEngine (streaming & tool loop), message tree, history builder, attachments
├── tools/     # Built-in tools (web search, SSRF-hardened web fetch, subagents, skills)
├── ext/       # Remote MCP client (Streamable HTTP), skills parser, marketplace
├── update/    # GitHub Releases in-app update checker and APK installer
└── ui/        # Jetpack Compose screens (chat, composer, model picker, drawer, settings)
```

</details>

---

## License & Brand

- **Source Code:** Licensed under the [GNU General Public License v3.0](./LICENSE).
- **Brand & Visual Assets:** The **Beluga Chat** name, `belugachat.app` domain, and Beluga whale icon/artwork are **Copyright © 2026 Beluga Chat. All Rights Reserved.** See [TRADEMARK.md](./TRADEMARK.md) for fork and redistribution guidelines.
