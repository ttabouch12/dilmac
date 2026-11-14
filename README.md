# Dilmaç

Free, real-time speech translation for Android (Turkish ↔ English first, more languages later):
live captions with speaker labels, translated voice in your headphones, and phone-call
translation through [SimBridge](https://github.com/ttabouch12/simbridge).

## Status

**Step 0 – Lab build.** The current app is a test bench that measures, on your own phone,
every engine the real app will choose between:

1. Device and audio routes
2. On-device speech recognition support (Turkish / English)
3. On-device Turkish recognition + ML Kit translation, with timings
4. On-device English recognition + ML Kit translation, with timings
5. ML Kit translation speed
6. Gemini Live Translate (free tier) latency and stall detection
7. Whether the phone mic and a Bluetooth headset mic can record at the same time
8. Text-to-speech start-up time

## Install

Download `dilmac.apk` from the [latest release](../../releases/tag/latest) on the phone and open it.

A Gemini API key (free, from [Google AI Studio](https://aistudio.google.com/apikey)) is only needed
for test 6. It is typed into the app and stays on the phone; it is never part of this repository.

## Building

GitHub Actions builds and tests every push to `main` and publishes the APK to the `latest` release.
Locally: `./gradlew assembleDebug` with the Android SDK installed.

## Credits

- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live-api/live-translate) – live speech translation
- [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) – on-device translation
- [OkHttp](https://square.github.io/okhttp/) – WebSocket client
- Inspired by [RTranslator](https://github.com/niedev/RTranslator)
