# Dilmaç

Free, real-time speech translation for Android (Turkish ↔ English first, more languages later):
live captions with speaker labels, translated voice in your headphones, and phone-call
translation through [SimBridge](https://github.com/ttabouch12/simbridge).

## Features

- Live captions while people talk (every half-finished sentence is translated, offline)
- Any pair of languages your phone can recognise offline and ML Kit can translate
- Tells people apart by voice (Person 1, 2, 3…), each with their own colour and, in your
  headphones, their own voice
- Your words spoken to them from the phone speaker
- Material You design with a themed icon

## Status (older notes)

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
Locally: download the two files the workflow fetches (sherpa-onnx AAR into `app/libs/`,
the CAM++ voice model into `app/src/main/assets/`), then `./gradlew assembleDebug`.

## Credits

- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live-api/live-translate) – live speech translation
- [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) – on-device translation
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and the [3D-Speaker](https://github.com/modelscope/3D-Speaker) CAM++ model – offline speaker recognition
- [OkHttp](https://square.github.io/okhttp/) – WebSocket client
- Inspired by [RTranslator](https://github.com/niedev/RTranslator)
