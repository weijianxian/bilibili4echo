# Echo Bilibili Audio

An independent **music extension APK** for the Android version of [Echo](https://github.com/brahmkshatriya/echo). This project uses the [official extension template](https://github.com/brahmkshatriya/echo-extension-template) and Echo `common:1.0` interfaces; it does not modify the Echo app.

## Features

- Search Bilibili videos, page through results, and play the audio-only DASH streams.
- Paste a BV link/ID, `av` ID, or `au` audio link/ID into Echo's search box.
- Display video parts in a track's detail feed; pick a part to play its own CID.
- Open four music-oriented search categories from the home feed.
- Fetch a fresh stream URL for each playback resolution; URLs are short lived.

Only playback that Bilibili allows to a guest is supported. Paid, region-restricted, login-only or unavailable items can return an error or a preview. This extension does not request credentials, persist cookies across launches, or bypass account restrictions. Search can also be affected by Bilibili verification/rate limits.

## Build and install

Install JDK 17 and Android SDK Platform 36 (set `ANDROID_HOME` or `ANDROID_SDK_ROOT`), then run:

```sh
bash gradlew :ext:test :app:assembleDebug
```

The Android extension package is `app/build/outputs/apk/debug/app-debug.apk`. Open it in Echo's **Add extension → From file** flow, or install it as an Android package and restart Echo. The `extensions` text code in Echo points to an existing third-party extension **list**; it does not accept source code or automatically discover this new extension. To distribute through a list, publish the built APK to your own GitHub Releases and add an item with `id=bilibili_audio` and your releases API URL to a JSON list hosted at an HTTPS URL; enter that list URL in Echo's Add extension screen. See `extension-list.example.json`.

## API mapping

The user-provided archived Bilibili API collect documents were consulted for the following requests:

| Feature | Endpoint |
| --- | --- |
| WBI keys | `GET api.bilibili.com/x/web-interface/nav` |
| Video search | `GET api.bilibili.com/x/web-interface/wbi/search/type` |
| Video details and parts | `GET api.bilibili.com/x/web-interface/view` |
| Video audio | `GET api.bilibili.com/x/player/wbi/playurl` (`fnval=16`, DASH audio) |
| au details | `GET www.bilibili.com/audio/music-service-c/web/song/info` |
| au stream | `GET www.bilibili.com/audio/music-service-c/web/url` |

The archive is from January 2026 and these endpoints may change. The app uses only video audio streams, never a video container. It does not include or redistribute the API archive.

## Implementation notes

The source's `app` module declares Echo's music extension manifest metadata; `ext` implements `ExtensionClient`, `HomeFeedClient`, `SearchFeedClient`, and `TrackClient`. The runtime host provides Echo `common`, OkHttp and Kotlin runtime. WBI keys refresh hourly and retry once when verification fails. A guest cookie is obtained through the site before signed search.

Source is provided for local build. No APK is bundled unless a successful Android build has been performed.
