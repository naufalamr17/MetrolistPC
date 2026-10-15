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
- A mobile-inspired Home layout with a three-row quick-pick grid, horizontal playlist/recent-listening cards, mood browsing, and Home/search continuation pages.
- **Play my mix** with no search text. Guest collections provide initial seeds; local likes and listening history help choose later seeds for upstream radio recommendations.
- Play/pause, previous/next, seeking when duration is known, volume, playback speed, shuffle, repeat, queue editing, and related tracks.
- Synchronized or plain lyrics, active-line highlighting, click-to-seek, and adjustable lyric timing.
- Local likes, listening history, editable playlists, local audio imports, and library backup export/merge import.
- Light/dark interface and a sleep timer.

This is an early desktop port, **not full Android feature parity**. Recommendations use the guest service and local library, not a signed-in Google account. Lyrics and streaming depend on external providers and can fail or vary by region.

## Known limitations

Google sign-in/cloud sync, offline downloads, casting, Listen Together, Last.fm/Discord integration, AI lyric translation, complete word-level/background-vocal lyrics, equalizer, normalization, crossfade, silence skipping, Android widgets/Android Auto, and global media keys are not implemented.

Public playlists load continuation pages, subject to limits of 100 pages, 10,000 songs, and 180 seconds. Artist/album browsing still depends on the pages exposed by the upstream methods. Local audio without a known duration cannot use the seek slider. HLS-only/live and SABR-only playback are not supported; this desktop build requests direct media with bounded byte ranges. External service changes can break playback or discovery. See [Desktop-Feature-Status.txt](Desktop-Feature-Status.txt) for the current scope.

The desktop app uses the retained upstream network implementation. This port does not claim that third-party service access is supported or approved by those services.

## Data and privacy

Your desktop library is stored locally at:

```text
%APPDATA%\MetrolistDesktop\library.json
```

Library backups contain metadata and local file paths, not audio files. Imported audio stays in its original location. Online discovery/playback and lyrics make requests to their respective providers; this is not an offline-only application. The earlier MetroDesk browser library is separate.

## Build from source

Build on **Windows x64**, using a full **JDK 21** and **FFmpeg**. The current setup was tested with Eclipse Temurin 21.0.12.1+1 and FFmpeg 7.1.1 essentials. The Gradle wrapper is included; you do not need to install Gradle, Android Studio, or an Android SDK for the desktop build. The first build needs internet access to download dependencies and packaging tools.

### 1. Open the desktop source

For a fresh checkout, open PowerShell and run:

```powershell
git clone --branch feat/windows-desktop-port https://github.com/naufalamr17/MetrolistPC.git
Set-Location .\MetrolistPC
```

For an existing checkout, open PowerShell in the folder containing `Build-Desktop.ps1`. Build the checkout that contains the changes you want: local edits are included, but building does not commit, push, or update the GitHub Release. A previously published Release may contain an older build.

### 2. Prepare the build tools

Download a Windows x64 JDK 21 from [Eclipse Adoptium](https://adoptium.net/temurin/releases/?version=21). Extract it into `.tools` inside the repository. Keep the extracted directory name starting with `jdk-21`, for example:

```text
MetrolistPC\
  Build-Desktop.ps1
  gradlew.bat
  .tools\
    jdk-21.0.12.1+1\
      bin\java.exe
      bin\jpackage.exe
      lib\modules
```

Use a full JDK, not a JRE. `Build-Desktop.ps1` selects the first `.tools\jdk-21*` directory, sets `JAVA_HOME`, and applies the process-local Java pipe workaround used on this Windows host. Keep only the intended JDK 21 directory there.

Provide the [Gyan FFmpeg essentials build](https://www.gyan.dev/ffmpeg/builds/) at these paths, which are currently fixed in `desktop/build.gradle.kts`:

```text
C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\bin\ffmpeg.exe
C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\LICENSE
C:\ProgramData\chocolatey\lib\ffmpeg\tools\ffmpeg\README.txt
```

The EXE and accompanying license files are bundled into the app image. The original Metrolist artwork is converted into the Windows app/installer icon during the build.

### 3. Build and test

From the repository root, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-Desktop.ps1
```

The script runs these Gradle tasks in the standalone `desktop` project:

```text
test createDistributable packageExe --no-daemon
```

Wait for `BUILD SUCCESSFUL` and an exit code of `0`. Do not use the root Android build task to create the Windows EXE. Later builds reuse cached dependencies and unchanged outputs. If packaging reports a locked file, close the locally built app and retry; do not delete your library.

### 4. Run the latest build or installer

The exact outputs are:

```text
desktop\build\compose\binaries\main\app\Metrolist Desktop\Metrolist Desktop.exe
desktop\build\compose\binaries\main\exe\Metrolist Desktop-13.7.0.exe
```

To try the new UI without installing, double-click `Run-Metrolist.cmd` in the repository root. Keep the entire app-image folder together; its launcher needs the adjacent runtime, libraries, and audio decoder.

To install, close Metrolist Desktop and run the generated installer. **Building does not replace an existing installed app or its Start menu shortcut.** That shortcut can still open the old UI until you update the installation. Back up `%APPDATA%\MetrolistDesktop\library.json` before changing installations. The installer is unsigned; do not disable Windows security protections.

The package version stays `13.7.0` under the upstream contribution rules. Different rebuilds can therefore have the same filename and version. Compare a checksum when sharing a specific build:

```powershell
certutil.exe -hashfile ".\desktop\build\compose\binaries\main\exe\Metrolist Desktop-13.7.0.exe" SHA256
```

See [Windows-Guide.txt](Windows-Guide.txt) for usage details. Build tools, caches, personal libraries, and generated installers are not committed into the source repository. Share the EXE as a GitHub Release attachment together with the corresponding source and third-party notices.

## Verification

The current desktop source has 21 automated tests covering library handling, lyric timing, playback recovery, queue controls, quick-pick loading, and UI rendering at wide/narrow window sizes. Build success requires those tests to pass. A separate live packaged-app check has exercised guest home discovery, a 50-track no-input mix, search continuation, 45 seconds of audio playback, play/pause, paused seeking, next-track playback, discovery, and a native UI snapshot. Lyrics are an optional live check because provider coverage and rate limits vary.

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
