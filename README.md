# Q-Translator 快译

**English** | [简体中文](README.zh-CN.md) | [User guide](docs/USER_GUIDE.md) | [使用说明](docs/USER_GUIDE.zh-CN.md)

Q-Translator is a fast, simple English ⇄ Chinese dictionary and translator for iPhone, iPad, Mac and Android. Open it and type: a word, a sentence or a whole paragraph. It also translates photos, interprets lectures live, helps two people talk face to face, and lets you practise spoken English with an AI role-play partner.

It is free and open source. Everything that can run on the device does, the app has no server of its own, and the AI features are optional: you bring your own API key.

![Q-Translator on iPhone: word lookup, photo translation, live interpretation record, English scene practice](docs/images/hero.png)

## Features

### Translate
- **Words**: UK and US phonetics with real-person audio, meanings by part of speech, every sense with examples (most common first), phrases, related words, synonym notes and etymology.
- **Sentences and paragraphs**: translated on the device by Apple's Translation framework (Google ML Kit on Android) once the language model is downloaded; online otherwise.
- **Photos**: take a photo or pick several from the library; the text is recognised on the device and the translation is drawn over the original text, in the same place. Tap to switch between the translation and the original, rotate without re-recognising, or recognise one image again.
- **Voice input**: tap the microphone, speak English or Chinese, and the words appear as you talk. Recognition runs on the device and recordings stay on it.
- **Conversations and scenes**: every translation is kept in a conversation; group conversations into scenes (classroom, renting, travel…), search them, filter by words / sentences / images, and jump back to anything you typed.
- **Word list**: star a word to keep it; words learned in scene practice go there too.

### Scenarios
- **Live interpretation**: for lectures and meetings. It listens continuously, splits speech into sentences and shows bilingual subtitles as people talk. It keeps going when you leave the page or lock the phone, can read the translation into your earphones, and saves each session as a record you can continue later, export, or summarise with AI.
- **Face to face**: put the phone on the table; the top half faces the other person. Each side taps their own button to speak, and the translation is shown in large type and read aloud.
- **English scene practice** (needs AI): the AI plays the landlord, waiter, interviewer or any role you describe, and you answer in English by voice or text. A hands-free voice-chat mode listens automatically and sends when you pause. Unnatural sentences get a "more natural" note with the reason, without interrupting the conversation; a summary at the end lists your corrections and new phrases.

### Reading aloud
- Eight hand-picked system voices (free, offline) and six natural AI voices (with an OpenAI key); tap one to hear a sample.
- One playback speed for everything: AI voices, system voices and word audio.

### AI (optional, bring your own key)
- Improve a machine translation, automatically or on demand.
- Draft a reply to a message you just translated (text message or email; from your key points, or by polishing your own draft).
- Summarise an interpretation record, power scene practice, and translate photos with an instruction ("only translate the dishes").
- Supported: Claude, ChatGPT (OpenAI), DeepSeek, and any OpenAI-compatible service. See [how to get an API key](docs/USER_GUIDE.md#5-ai-features-and-api-keys).

### Platform extras
- **iPhone**: Home Screen quick actions (long-press the icon) for interpretation, face to face, scene practice and a new translation.
- **Mac**: global hotkeys: `⌥D` main window, `⌥F` translate the selection in any app, `⌥R` translate and replace, `⌥V` clipboard, `⌥S` screenshot, `⌥A` quick input. Plus "translate" in the right-click Services menu, `⌘V` to paste an image, and a Features menu (`⇧⌘I` interpretation, `⇧⌘F` face to face, `⇧⌘P` practice).
- **Android**: share text or images to Q-Translator, or pick "快译" from the text-selection menu in any app.

<p align="center"><img src="docs/images/mac.png" width="760" alt="Q-Translator on Mac"></p>

## Platform status

| Platform | Status |
| --- | --- |
| iPhone | All features (`apple/`, iOS 26 or later) |
| Mac | All features (`apple/`, macOS 26 or later) |
| iPad | Runs the iPhone app with a wide layout; the newest features (voice, interpretation, face to face, practice) are being polished |
| Android phone / tablet | Dictionary, sentence, photo and AI features (`android/`, Android 8 or later); voice and scenario features are coming |

## Where translations come from

Q-Translator prefers free and offline sources and only goes online when it has to.

| Source | Used for | Notes |
| --- | --- | --- |
| Apple Translation framework | Sentences, paragraphs, live interpretation | On device, free, works offline once the language model is downloaded |
| Apple Speech | Voice input, live interpretation | On device where supported; recordings are not uploaded |
| Apple Vision | Text in photos | On device; images are never uploaded |
| Google ML Kit (Android) | Sentence translation and text recognition | On device; the Chinese model (about 30 MB) is downloaded once |
| Youdao dictionary JSON endpoint | Word entries and word audio | Public but unofficial; it may change without notice |
| MyMemory | Fallback sentence translation | Free tier with a daily limit |

Q-Translator is not affiliated with any of these providers.

## Privacy

- The text, photos and recordings you translate stay on your device, except for what a translation source or AI provider needs to answer (an online translation request, or the text you send to your own AI provider).
- API keys are stored in the system Keychain (Android Keystore on Android) and sent only to the provider you chose.
- The App Store build can send anonymous usage statistics (which features are used) through [Umeng](https://www.umeng.com/), only after you agree on first launch; you can switch it off in Settings. Builds from source contain no analytics keys and send nothing.

## Build from source

Requires Xcode 26 or later and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). The Xcode project is generated from `apple/project.yml` and is not checked in.

### Mac

```bash
cd apple
./build.sh install
```

This builds `Q-Translator.app` and copies it to `/Applications`. If your keychain has an "Apple Development" certificate, `build.sh` signs the app with it (or with `QTRANSLATOR_SIGN_IDENTITY` if set). macOS ties the Accessibility and Screen Recording permissions used by the hotkeys to the app's signature, so with a stable certificate you only grant them once.

To make an installer: `./make_dmg.sh` writes `dist/Q-Translator-<version>.dmg`. For a DMG that opens on other Macs you need a "Developer ID Application" certificate and notarization credentials saved with `xcrun notarytool store-credentials QTranslator --apple-id <Apple ID> --team-id <team ID>`.

### iPhone / iPad

```bash
cd apple
./Scripts/fetch_umeng.sh
QTRANSLATOR_TEAM_ID=YOUR_TEAM_ID xcodegen generate
open QTranslator.xcodeproj
```

Pick the `QTranslator-iOS` scheme and run it. `fetch_umeng.sh` downloads the analytics SDK into `apple/Vendor/` (not checked in). `QTRANSLATOR_TEAM_ID` is your Apple developer team ID and is only needed for real devices. On-device translation does not run in the iOS Simulator, so sentences fall back to the online translator there.

Analytics keys, if you want your own, go in `apple/Config/Local.xcconfig` (ignored by git); the custom events are listed in `apple/Config/umeng-events.csv`:

```
UMENG_APPKEY_IPHONE = ...
UMENG_APPKEY_IPAD = ...
UMENG_APPKEY_MAC = ...
```

### Android

Requires JDK 17 (Android Studio's bundled JDK works) and the Android SDK.

```bash
cd android
./gradlew assembleRelease
```

The APKs are written to `android/app/build/outputs/apk/release/`, one per CPU type (`arm64-v8a` for almost all current phones). Release builds are signed with the debug key so you can install them directly; use your own key before publishing to a store.

## Repository layout

```
apple/
  project.yml          XcodeGen spec (targets QTranslator-iOS and QTranslator-macOS)
  Shared/              Code shared by all Apple platforms
    Core/              Dictionary, translation, OCR, speech, voice input, interpretation
    AI/                Provider settings, API client, prompts, usage
    Conversation/      Conversations, scenes, storage and their views
    Modules/           Interpretation, face to face and practice records
    Views/             Settings and other SwiftUI views
    Support/           Theme, analytics, platform helpers
  iOS/                 iOS entry point, composer, camera, quick actions
  macOS/               macOS entry point, menu bar, hotkeys, Services
  Config/              Analytics config (keys stay local)
  Scripts/             Icon generator, SDK download
android/
  app/src/main/java/com/yishulabs/qtranslator/
    core/              Dictionary, translation, OCR, speech, settings
    ai/                Provider settings, API client, prompts, usage
    conversation/      Conversations, scenes, storage
    ui/                Jetpack Compose screens
docs/                  User guides and screenshots
```

## Contributing

Issues and pull requests are welcome. Please keep the app simple: open it and type, prefer free and offline sources, and keep AI optional.

## License

[MIT](LICENSE)
