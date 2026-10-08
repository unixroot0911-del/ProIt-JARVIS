# Jarvis: Phase 1 (core)

Android assistant: voice in, free cloud brain with automatic fallback, long-term on-device memory, HUD orb, notification trigger.
Cost: $0. Not yet compiled or tested on a device; first build may need small fixes.

## Run it (you have a PC)
1. Install Android Studio. Open the `jarvis` folder as a project and let Gradle sync.
2. Enable Developer options and USB debugging on the phone, plug it in, press Run.
3. Get two free keys: Gemini at aistudio.google.com, Groq at console.groq.com.
4. In the app: SETTINGS, paste the keys, SAVE.
5. Tap TALK, or tap the persistent "Jarvis" notification (your chosen trigger, no always-on mic).

## What works in Phase 1
- Speech recognition (default ar-MA, editable in settings) and deep-pitch spoken replies in Arabic or English.
- Brain: Gemini first, Groq automatically if Gemini fails or runs out of quota. Replies in your language, Darija included.
- Memory: facts about you and recent conversation stored in a local SQLite database. "Erase memory" in settings.
- Actions through standard Android intents: open app, alarm, timer, web search, open dialer.
- Guard: payments and deletions always ask for confirmation, and are not executed yet in this version.

## Limits to know now
- 4GB RAM: no local model in Phase 1. Without internet there is no thinking, only the recognizer if the phone has offline Arabic.
- Free tiers have rate limits. The fallback covers short outages, not heavy sustained use.
- Replies are spoken by the phone's own TTS. Arabic voice quality depends on the voice pack installed.

## Roadmap
- Phase 2: read notification summaries (NotificationListenerService), reply drafts for WhatsApp/Telegram, morning and evening briefings, study mode.
- Phase 3: screen agent via AccessibilityService for multi-step tasks, camera vision, Telegram bot for remote control, finance and habit tracking.
- Phase 4: tiny offline model for quick commands, encrypted backup of memory.
