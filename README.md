# Maa Assistant: a voice assistant for a parent

An Android app that lets someone who isn't comfortable with smartphones use one by talking. It was built for an Odia-speaking parent. They tap one big button (or a floating bubble), say what they need in Odia or Hindi, and the phone does it and answers out loud in simple, warm Hinglish.

## What it does

- **Understands everyday speech.** The app records a short clip and sends it to the Gemini API. Gemini works out what was asked and calls one function from a fixed list. It never runs anything directly: local Kotlin code decides how each request is carried out.
- **Replies out loud** in everyday Hinglish (Hindi with common English words), using Android's built-in text-to-speech.
- **Phone actions:**
  - change the ringer mode
  - save or call a contact (with sound-alike name matching)
  - send an SMS
  - set an alarm
  - open an app
  - search the web or YouTube
- **Share to WhatsApp.** When a reel, video or photo is on screen, they can say "send this to the family group". An Accessibility Service taps through the share sheet and picks the chat.
- **In-app help:** searching for a show inside an app, switching back from a Kids profile, and walking through simple fixes when an app misbehaves.
- **Floating bubble.** A tap-to-talk bubble stays on top of other apps and comes back after a reboot.
- **Safe by default.** Anything unclear or risky (money, deleting things, an unclear contact name) gets a spoken question back instead of a guess.

## Tech stack

- **Kotlin**, native Android (minSdk 26, targetSdk 34)
- **Gemini API** (function calling) for speech understanding and composing replies
- **Accessibility Service** for on-screen automation (WhatsApp share, in-app search)
- Android TTS (`hi-IN`), a foreground service and an overlay window for the bubble
- **GitHub Actions** builds a debug APK on every push

## Build it yourself

1. Get a Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey).
2. Create `local.properties` in the repo root (it's gitignored) and add your key:
   ```properties
   sdk.dir=/path/to/Android/sdk
   GEMINI_API_KEY=your-key-here
   # Optional: who the "app not working" helper offers to call
   # HELPER_CONTACT_NAME=Name
   # HELPER_PHONE_NUMBER=+91XXXXXXXXXX
   ```
   You can set the same keys as environment variables instead.
3. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. On the phone, open the app once and grant what it asks for: microphone, contacts, phone, Do Not Disturb access, display over other apps, and the Accessibility Service.

The CI build has no API key, so it's only a compile check. That APK installs, but it tells you the key is missing.

## Built with Claude Code

This app was designed and written together with [Claude Code](https://claude.com/claude-code).
