# Sound Panel 1.0.2

Minecraft Java **1.21.11** client mod. Requires Java 21, Fabric Loader 0.18.4 or newer, Fabric API, and Mod Menu 17. No server mods required.

1. Place the release JAR, Fabric API, and Mod Menu in your Minecraft instance's `mods` directory.
2. Put audio files into the instance's `sounds` folder (for the normal launcher profile, `.minecraft/sounds`; other launcher instances have their own game directory). Sound Panel creates this folder on first launch.
3. Open **Mods → Sound Panel → Configure**. **PvP** starts with common melee, crystal, survival, mace, shield and XP sounds. **All** keeps the full expandable sound catalog. Search the event list on the left and the audio file list on the right separately.
4. Select an event on the left and a file on the right. **Original** plays vanilla or the raw pack event, **Current** plays your installed replacement, and **Preview** lets you hear the selected file before saving. **Apply** is the only operation that saves a new replacement. **Reset** restores the original without removing your file.
5. **Files** asks the system file manager to open the instance's sound folder. If no window appears, click **Copy path** and paste into File Explorer or your file manager. Hover **Copy path** for the exact path. **Scan** refreshes the list after adding files. You can drop one supported file onto the panel. The arrow keys move through the focused list and Enter auditions the selected file; Ctrl+Enter (Cmd+Enter on a Mac) applies it.

Selected files remain selected when you move to another event so it is easy to reuse the same sound. Preview files live in a separate scratch folder and are discarded when you change files or leave the panel. Regular PvP and All assignments apply on the client in single-player and on multiplayer servers.

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
This includes checks for curated PvP sound IDs, compact and wide panel geometry, temporary previews, Bottle impact context, real Ogg Vorbis import, independent assignments, server provenance and persistence.
