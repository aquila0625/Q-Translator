# Q-Translator 快译

Q-Translator is a fast, simple English ⇄ Chinese dictionary and translator for iPhone, iPad, Mac and Android. Open it, type a word, a sentence or a paragraph, and get the result right away.

Q-Translator（快译）是一个简单、直接、高效的中英词典和翻译工具：打开就能输入，单词、句子、整段话都能翻译。

## Status

| Platform | Status |
| --- | --- |
| iPhone / iPad | Working (`apple/`, iOS 26 or later) |
| macOS | Working (`apple/`, macOS 26 or later) |
| Android phone / tablet | Working (`android/`, Android 8 or later) |

## Features

- **Conversations**: every translation is kept in a conversation; group conversations into scenes (classroom, travel, renting…), search them, and jump back to anything you typed
- **Words**: UK/US phonetics, a summary by part of speech, every meaning listed one by one with examples (common meanings first), phrases, related words, synonym notes and etymology
- **Sentences and paragraphs**: translated on device (Apple's Translation framework, or Google ML Kit on Android) once the language model is installed, online otherwise
- **Pronunciation**: British and American English, and Chinese
- **Image translation**: take a photo, pick an image, or paste / drop one on the Mac; text is recognised on device
- **History and word list**: recent lookups, with a star to keep words
- **AI (optional, bring your own key)**: check and correct a machine translation, and draft a reply to a message you just translated (text message or email; from your key points or by polishing your own draft)
- **macOS extras**: global hotkey `⌥D`, "translate selection" in the right-click Services menu, `⌘V` to paste an image
- **Android extras**: share text or images to Q-Translator, or pick "快译" from the text-selection menu in any app

## Data sources

Q-Translator prefers free and offline sources and only goes online when it has to.

| Source | Used for | Notes |
| --- | --- | --- |
| Apple Translation framework | Sentences and paragraphs | On device, free, works offline once the language model is downloaded |
| Apple Vision | Text recognition in images | On device, images are never uploaded |
| Google ML Kit (Android) | Sentence translation and text recognition | On device; the Chinese translation model (about 30 MB) is downloaded once |
| Youdao dictionary JSON endpoint | Word entries and word audio | Public but unofficial endpoint; it may change without notice |
| MyMemory | Fallback sentence translation | Free tier, limited daily quota |

Q-Translator is not affiliated with any of these providers.

## AI features: bring your own key

AI features are optional. Q-Translator ships with no API key and has no server of its own. You register with a provider yourself, paste your API key into Settings, and the app talks to the provider directly from your device. The key is stored in the system Keychain (on Android, encrypted with the Android Keystore) and usage is billed to you by the provider.

Supported providers:

| Provider | Where to get a key |
| --- | --- |
| Claude (Anthropic) | <https://platform.claude.com/> |
| ChatGPT (OpenAI) | <https://platform.openai.com/api-keys> |
| DeepSeek | <https://platform.deepseek.com/> |
| Custom | Any OpenAI-compatible endpoint: enter its base URL and model name |

## Build

Requires Xcode 26 or later and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). The Xcode project is generated from `apple/project.yml` and is not checked in.

### macOS

```bash
cd apple
./build.sh install
```

This builds `Q-Translator.app` and copies it to `/Applications`.

If your keychain has an "Apple Development" certificate, `build.sh` signs the app with it (or with `QTRANSLATOR_SIGN_IDENTITY` if you set one). macOS ties the Accessibility and Screen Recording permissions used by the menu bar features to the app's signature, so with a stable certificate you only grant them once. Without a certificate the app is ad-hoc signed and you have to grant them again after every rebuild.

### macOS installer (DMG)

```bash
cd apple
./make_dmg.sh
```

This writes `dist/Q-Translator-<version>.dmg`. For a download that other people can open, you need a "Developer ID Application" certificate (Xcode › Settings › Accounts › Manage Certificates) and notarization credentials saved once with `xcrun notarytool store-credentials QTranslator --apple-id <Apple ID> --team-id <team ID>`. With both, the script signs with Developer ID, submits the DMG for notarization and staples the ticket. Without them the DMG is only suitable for your own testing; macOS will refuse to open it on other Macs.

### iPhone / iPad

```bash
cd apple
./Scripts/fetch_umeng.sh
QTRANSLATOR_TEAM_ID=YOUR_TEAM_ID xcodegen generate
open QTranslator.xcodeproj
```

`fetch_umeng.sh` downloads the Umeng analytics SDK into `apple/Vendor/` (it is not checked in).

Pick the `QTranslator-iOS` scheme and run it on a simulator or a device. `QTRANSLATOR_TEAM_ID` is your Apple developer team ID and is only needed for real devices; you can also leave it out and choose the team in Xcode.

Apple's on-device translation does not run in the iOS Simulator, so sentences fall back to the online translator there.

### Usage analytics

The iPhone and iPad app can send anonymous usage statistics (which features are used, never the text, images or audio you translate) through [Umeng](https://www.umeng.com/), and only after the user agrees on first launch; it can be turned off in Settings. The repository contains no Umeng app keys, so builds from source send nothing. To use your own, put them in `apple/Config/Local.xcconfig` (ignored by git):

```
UMENG_APPKEY_IPHONE = ...
UMENG_APPKEY_IPAD = ...
UMENG_APPKEY_MAC = ...
```

The custom events are listed in `apple/Config/umeng-events.csv`. Umeng has no macOS SDK at the moment, so the Mac app does not send statistics yet.

### Android

Requires JDK 17 (Android Studio's bundled JDK works) and the Android SDK.

```bash
cd android
./gradlew assembleRelease
```

The APKs are written to `android/app/build/outputs/apk/release/`, one per CPU type (`arm64-v8a` for almost all current phones). Release builds are signed with the debug key so you can install them directly; use your own signing key before publishing to a store. You can also open `android/` in Android Studio.

## Repository layout

```
apple/
  project.yml        XcodeGen spec (two targets: QTranslator-iOS, QTranslator-macOS)
  Shared/            Code shared by all Apple platforms
    Core/            Dictionary, translation, OCR, speech, history
    AI/              Provider settings, API client, prompts
    Views/           SwiftUI views
    Support/         Theme and platform helpers
  iOS/               iOS app entry point, camera
  macOS/             macOS app entry point, global hotkey, Services
  Scripts/           Icon generator
android/
  app/src/main/java/com/yishulabs/qtranslator/
    core/            Dictionary, translation, OCR, speech, settings
    ai/              Provider settings, API client, prompts, usage
    conversation/    Conversations, scenes, storage
    ui/              Jetpack Compose screens
```

## License

[MIT](LICENSE)
