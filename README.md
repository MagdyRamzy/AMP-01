# HeadUnit Launcher
1. Build a variant in Android Studio (Build Variants panel) or with Gradle:
   - `gradle assembleLiteDebug`  -> location only (speed + alerts + apps + theme; visualizer runs as a demo animation). Use this if install is blocked for "sensitive data".
   - `gradle assembleFullDebug`  -> adds audio capture + notification access (real visualizer, track info, controls).
   Install with: `adb install -r app/build/outputs/apk/lite/debug/app-lite-debug.apk`
2. Press Home on the head unit, choose "HeadUnit Launcher" -> Always.
3. Allow Location (speed) and Microphone/Audio (the visualizer reads the audio output).
4. Optional: tap "Tap to allow track info" in the music widget and enable notification access for title/artist + prev/play/next.

Usage
- Tap app = open. Long-press app = pin/unpin in dock.
- Speaker card: tap the bell button to mute/unmute alert sounds (the warning banner still shows).
- Long-press speed card = test mode.
- Dock: music button swaps the apps panel for the visualizer. Tap visualizer = next style, long-press = previous (9 styles).
- Palette button: colours change live; the dialog stays open until you press Done.
