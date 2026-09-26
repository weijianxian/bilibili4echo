# Echo Bilibili Audio

An independent **music extension APK** for the Android version of [Echo](https://github.com/brahmkshatriya/echo). This project uses the [official extension template](https://github.com/brahmkshatriya/echo-extension-template) and Echo `common:1.0` interfaces; it does not modify the Echo app.

## Features

- Search Bilibili videos, page through results, and play the audio-only DASH streams.
- Paste a BV link/ID, `av` ID, or `au` audio link/ID into Echo's search box.
- Display video parts in a track's detail feed; pick a part to play its own CID.
- Browse music, vocaloid, radio, knowledge, and technology sections from the home feed.
- Sign in through Echo's Bilibili WebView; open your own creator profile from the home feed.
- Open other creators through their space URL or `mid:<UID>` and browse paged uploads and collections.
- Open Bilibili video collections and series as Echo playlists, with paged audio tracks.
- Fetch a fresh stream URL for each playback resolution; URLs are short lived.

Paid, region-restricted, or unavailable items may still return an error or a preview. Bilibili verification and rate limits may affect search and browsing. Echo stores the WebView session cookie with the selected extension account so the login survives an app restart; logging out clears it from the active network client. The extension never asks for a password or sends session cookies to playback CDNs or other extensions.

## Build and install

Install JDK 17 and Android SDK Platform 36 (set `ANDROID_HOME` or `ANDROID_SDK_ROOT`), then run:

```sh
bash gradlew :ext:test :app:assembleDebug
```

The Android extension package is `app/build/outputs/apk/debug/app-debug.apk`. Open it in Echo's **Add extension → From file** flow, or install it as an Android package and restart Echo.

To install by URL, paste the following address in Echo's **Add extension → From link or code** field, then select **Bilibili Audio**:

```text
https://raw.githubusercontent.com/weijianxian/bilibili4echo/main/extensions.json
```

This file is a third-party extension list; the `extensions` text code in Echo points to a different list. On a push to `main`, GitHub Actions builds the APK and publishes it as a versioned GitHub Release. The list's `updateUrl` points to this repository's Releases API, which Echo uses to download the APK. Increase `extVersion` and `extVersionCode` in `gradle.properties` before publishing a subsequent version.

This build uses Android's debug signing key. Installing a future build over it may require uninstalling the previous APK and then adding the extension again, because the CI signing key can change between builds.

## API mapping

The user-provided January 2026 archive contains the project's closure notice but no API documentation. Available archived documentation from the same API collect project and Echo's source were consulted for these requests:

| Feature | Endpoint |
| --- | --- |
| WBI keys | `GET api.bilibili.com/x/web-interface/nav` |
| Video search | `GET api.bilibili.com/x/web-interface/wbi/search/type` |
| Video details and parts | `GET api.bilibili.com/x/web-interface/view` |
| Video audio | `GET api.bilibili.com/x/player/wbi/playurl` (`fnval=16`, DASH audio) |
| au details | `GET www.bilibili.com/audio/music-service-c/web/song/info` |
| au stream | `GET www.bilibili.com/audio/music-service-c/web/url` |
| WebView login check | `GET api.bilibili.com/x/web-interface/nav` |
| Creator profile and uploads | `GET api.bilibili.com/x/space/wbi/acc/info`, `/x/space/wbi/arc/search` |
| Creator collections and series | `GET api.bilibili.com/x/polymer/web-space/seasons_series_list`, `/x/polymer/web-space/seasons_archives_list`, `/x/series/archives` |
| Home sections | `GET api.bilibili.com/x/web-interface/dynamic/region` |

The archive is from January 2026 and these endpoints may change. The app uses only video audio streams, never a video container. It does not include or redistribute the API archive.

## Implementation notes

The source's `app` module declares Echo's music extension manifest metadata; `ext` implements Echo's home, search, track, creator, playlist and WebView login clients. The runtime host provides Echo `common`, OkHttp and Kotlin runtime. WBI keys refresh hourly and retry once when verification fails. A guest cookie is obtained through the site before signed search. Cookies are attached manually as headers because Echo nightly's minified OkHttp crashes with a nondefault CookieJar.

The latest APK is available on the repository's Releases page after the first successful push build.
