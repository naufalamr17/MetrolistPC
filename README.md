# Metrolist Desktop for Windows

An unofficial, educational Windows port of [Metrolist](https://github.com/MetrolistGroup/Metrolist), built with Kotlin and Compose Desktop.

**This project is intended for educational and research purposes only.** It studies how an open-source Android music client can be adapted into a desktop application. It is not an official Metrolist release and is not affiliated with or endorsed by Metrolist Group, YouTube, or Google.

The original GPL license remains unchanged. The educational-purpose statement describes the project's intent; it does not add restrictions to the rights granted by that license. Respect content owners' rights and the terms of the services you use. This project does not grant permission to access or redistribute music or lyrics.

## What this port is

This is a native JVM desktop application, not an APK running inside an Android emulator and not the earlier browser-based MetroDesk prototype. It compiles the original Metrolist InnerTube and LRCLIB Kotlin source directly, adds a desktop interface, and uses a bundled FFmpeg decoder for audio playback.

The desktop port was developed against upstream commit [`76fa5a78b5110674442884b6f21c0cef51cdb782`](https://github.com/MetrolistGroup/Metrolist/commit/76fa5a78b5110674442884b6f21c0cef51cdb782). The Android source, original notices, and Git history are retained.

The Windows work is on [`feat/windows-desktop-port`](https://github.com/naufalamr17/MetrolistPC/tree/feat/windows-desktop-port). The repository's `main` branch remains unchanged.

## Install on Windows

1. Open the [Windows desktop preview release](https://github.com/naufalamr17/MetrolistPC/releases/tag/desktop-v13.7.0-preview.1) and download `Metrolist-Desktop-Setup.exe`.
2. Run the installer, then open **Metrolist Desktop** from the Start menu or desktop shortcut.
3. Open **Home** and click **Play my mix** to start discovering music without typing a title.

The installer targets Windows x64 and includes its Java runtime and audio decoder. You do not need Android Studio, an Android emulator, or a separate Java installation to use it.

The installer is **unsigned**. Windows may show an unknown-publisher warning. Verify the release checksum and source before deciding whether to install; do not disable Windows security protections.

The installer is distributed as a Release attachment rather than committed into the source tree. Source ZIP/TAR downloads alone are not installers.

## Available features

- Online music discovery, guest home shelves, and search for songs, videos, albums, artists, and public playlists. Video results play their audio.
- **Play my mix** with no search text. Guest collections provide initial seeds; local likes and listening history help choose later seeds for upstream radio recommendations.
- Play/pause, previous/next, seeking when duration is known, volume, playback speed, shuffle, repeat, queue editing, and related tracks.
- Synchronized or plain lyrics, active-line highlighting, click-to-seek, and adjustable lyric timing.
- Local likes, listening history, editable playlists, local audio imports, and library backup export/merge import.
- Light/dark interface and a sleep timer.

This is an early desktop port, **not full Android feature parity**. Recommendations use the guest service and local library, not a signed-in Google account. Lyrics and streaming depend on external providers and can fail or vary by region.

## Known limitations

Google sign-in/cloud sync, offline downloads, casting, Listen Together, Last.fm/Discord integration, AI lyric translation, complete word-level/background-vocal lyrics, equalizer, normalization, crossfade, silence skipping, Android widgets/Android Auto, and global media keys are not implemented.

Collection browsing and public playlist import currently load the **first page only**. Local audio without a known duration cannot use the seek slider. External service changes can break playback or discovery.

The desktop app uses the retained upstream network implementation. This port does not claim that third-party service access is supported or approved by those services.

## Data and privacy

Your desktop library is stored locally at:

```text
%APPDATA%\MetrolistDesktop\library.json
```

Library backups contain metadata and local file paths, not audio files. Imported audio stays in its original location. Online discovery/playback and lyrics make requests to their respective providers; this is not an offline-only application. The earlier MetroDesk browser library is separate.

## Build from source

The current build setup is Windows-specific. The published installer was built with Java 21 and FFmpeg 7.1.1 essentials.

1. Clone the desktop branch and open PowerShell in its root folder:

   ```powershell
   git clone --branch feat/windows-desktop-port https://github.com/naufalamr17/MetrolistPC.git
   cd MetrolistPC
   ```

2. Download a Windows x64 **JDK 21** from [Eclipse Adoptium](https://adoptium.net/temurin/releases/?version=21). Extract it into `.tools` so the JDK folder is named `.tools\jdk-21...` and contains `bin\java.exe`.
3. Install/provide the [Gyan FFmpeg essentials build](https://www.gyan.dev/ffmpeg/builds/). The current desktop build expects these files at the following paths:
   - `C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe`
   - `C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\LICENSE`
   - `C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\README.txt`
4. Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-Desktop.ps1
```

The script runs the desktop tests and builds the application image and EXE installer. Gradle downloads build dependencies on the first run. The desktop build is separate from Android and does not require an Android SDK.

Outputs:

```text
desktop\build\compose\binaries\main\app\
desktop\build\compose\binaries\main\exe\
```

`Run-Metrolist.cmd` launches the locally built app image. See [Windows-Guide.txt](Windows-Guide.txt) for usage details. Build tools, caches, personal libraries, and generated installers are not part of the source repository.

## Verification

The packaged desktop build passed 12 automated tests covering library handling, lyric timing, queue/UI behavior, and audio-source validation. A separate live packaged-app check exercised guest home discovery, a 50-track no-input mix, search, synchronized lyrics, stream extraction, and play/pause.

These checks are a snapshot, not a guarantee of service availability or complete feature coverage. Android APK compilation was not verified successfully in the local offline environment. No Android database schema was changed.

## Credits

The original application and most of the retained source belong to the upstream authors and contributors:

- [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist), [Mo Agamy](https://github.com/mostafaalagamy), and [all Metrolist contributors](https://github.com/MetrolistGroup/Metrolist/graphs/contributors): original Android application, shared Kotlin engine, LRCLIB integration, resources, and artwork.
- [InnerTune](https://github.com/z-huang/InnerTune) by Zion Huang, and [InnerTune](https://github.com/Malopieds/InnerTune) by Malopieds: upstream foundations acknowledged by Metrolist.
- [OuterTune](https://github.com/DD3Boh/OuterTune), Davide Garberi, and [Michael Zh](https://github.com/mikooomich): upstream inspiration acknowledged by Metrolist.
- [InnerTubeX](https://github.com/MetrolistGroup/innertubex), [LRCLIB](https://lrclib.net/), [Kotlin](https://kotlinlang.org/), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform), [Ktor](https://ktor.io/), [Eclipse Temurin](https://adoptium.net/), and [FFmpeg](https://ffmpeg.org/): libraries, services, runtime, and playback tools used by the desktop port.

REA was used during educational APK inspection; the desktop application does not depend on REA at runtime. No original APK or music files are redistributed in this repository.

Please support and credit the original projects. Desktop-port issues belong in this repository, not in the original Metrolist issue tracker.

## License and third-party notices

See [LICENSE](LICENSE) for the retained GNU GPL version 3 license and [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) for bundled runtime/decoder provenance and source links. Third-party components keep their own licenses. All names, trademarks, artwork, music, and lyrics remain the property of their respective owners.

This experimental project is provided without warranty. The educational-purpose note does not replace any applicable license or service terms.
