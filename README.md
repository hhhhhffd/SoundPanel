# Sound Panel 1.0.0

Minecraft Java **1.21.11** client mod. Requires Java 21, Fabric Loader 0.18.4 or newer, Fabric API, and Mod Menu 17. No server mods required.

1. Place the release JAR, Fabric API, and Mod Menu in your Minecraft instance's `mods` directory.
2. Put audio files into the instance's `sounds` folder (for a standard install, `.minecraft/sounds`). Sound Panel creates this folder on first launch.
3. Open **Mods → Sound Panel → Configure**. Expand an entity in **Entities**, choose a specific action, then **Choose file**. You can also drop a single file onto the panel after selecting an action. In the file view click **Refresh** if you just added more clips.
4. **Preview** plays your replacement. **Original** plays Minecraft's sound. **Reset** restores the action without deleting your audio file.

Search accepts an entity, an action, or a sound ID. **All sounds** also includes non-entity sounds such as blocks, UI, and music. Regular entity and All sounds assignments are client-side and apply in single-player and on multiplayer servers.

**Server sounds** shows events declared by the currently active server resource packs (including detected file-only sound overrides). Enable/download the server pack normally through Minecraft; the mod neither downloads nor scans inactive packs itself. Regular local packs are excluded from this tab. A replacement made in this tab is stored separately for the current server address and applies only while its sound is present in an active server pack. With no assignment, the server's original sound plays.

## Bottle o' Enchanting example

- **Throw:** a separate replacement for the bottle's throw.
- **Shatter:** uses the splash packet and nearby tracked projectiles to distinguish a bottle impact from an ordinary potion. If the bottle is not tracked, the event is ambiguous, or the server changes its protocol, Minecraft plays the ordinary sound instead.
- **XP pickup:** replaces the experience-orb pickup sound. Vanilla uses the same sound for all XP sources, including mob drops; this setting affects all XP orb pickups.

## Audio formats

Ogg Vorbis (`.ogg` and `.oga`, mono or stereo, max 24 MB) works natively. When **FFmpeg with libvorbis** is installed and discoverable on Minecraft's PATH, the mod also imports common formats such as MP3, WAV, FLAC, AAC/M4A, Opus, AIFF, WMA, and audio tracks from MP4/WebM. The converter caps inputs at 64 MB and the result at 10 minutes or 24 MB. It converts to mono for directional playback. The mod does not install FFmpeg itself or upload any sound.

A validated playback copy is stored in `config/soundpanel/clips`; `config/soundpanel/sounds.json` stores each assignment. Moving the source file does not break its saved replacement. Resetting an action does not remove the source or the playback copy. If you remove the corresponding playback copy, the original sound is used.

## Build

Use a **full JDK 21** installation:
```sh
./gradlew build
```
This includes checks for grouping, Bottle impact context, real Ogg Vorbis import, independent assignments and persistence.
