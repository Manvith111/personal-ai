# Aide - your personal phone assistant

Aide is an Android app that listens to your phone calls, voice notes and meetings, **understands
what was agreed, does the work for you, and gives you a review afterwards.**

| You say (on a call, in a voice note, or typed) | Aide does |
|---|---|
| "I'll meet you today at 5pm at Cafe Coffee Day" | Adds **"Meet Rahul" 5:00 PM** to your Google Calendar, with location and a 30-min alert |
| "Research the best co-working spaces in Andheri under 10k" | Searches the web, writes a report with **bottom line, key insights, next steps and sources**, and notifies you |
| "Remind me to pay the electricity bill on Friday" | Sets a reminder that fires on Friday morning (Done / Snooze buttons) |
| "I'll WhatsApp you the address" | Drafts the message; you send it with **one tap** (Aide never sends messages on its own) |
| "My new account number is…" | Saves it to your notes |
| "Wake me up at 6" | Sets an alarm |

Every evening (default 9 PM) you get a **daily review**: what you talked about, what Aide handled,
what still needs you, and tomorrow's schedule. Everything Aide did can be **undone** with one tap.

Under the hood, the "brain" is **Claude (Opus 5.5 by default)** via the official Anthropic SDK.
Recorded audio is turned into text first by a Whisper speech-to-text service (Groq or OpenAI).

---

## 1. Get the app onto your phone

You need an **Android phone** (Android 8 or newer). iPhones are not supported: Apple does not allow
any app to access phone calls.

### Option A - build it for free on GitHub (no software to install)

1. Create a free account at **github.com** (if you don't have one) and click **New repository**.
   Name it `aide-assistant`. Choose **Private**. Click **Create repository**.
2. On the new repository page click **"uploading an existing file"**, and drag in **everything
   inside this folder** (including the hidden `.github` folder - on a computer, unzip
   `aide-assistant.zip` and drag the contents). Click **Commit changes**.
   *(If you use git: `git init && git add . && git commit -m "Aide" && git push` to the new repo.)*
   *Tip: if the **Actions** tab shows no "Build Aide APK" workflow, the hidden `.github` folder was
   not uploaded. Click **Actions → set up a workflow yourself**, name it `build-apk.yml`, paste the
   contents of `.github/workflows/build-apk.yml` from this folder, and commit.*
3. Open the **Actions** tab. A build called **"Build Aide APK"** starts by itself and takes about
   5-10 minutes. (If GitHub asks, click "I understand my workflows, enable them" and then
   **Run workflow**.)
4. When it shows a green tick, open **Releases** (right side of the repository's main page), tap
   the newest **Aide build**, and download **Aide.apk** - do this **on your phone**.
5. Open the downloaded file. Android will ask you to allow installing apps from your browser /
   files app - allow it, then tap **Install**.

### Option B - Android Studio

Open this folder in Android Studio, connect your phone with USB debugging on, and press **Run ▶**.

---

## 2. Set it up (about 2 minutes)

Open Aide - it starts on the **Settings** tab.

1. **Claude API key** - go to **console.anthropic.com → API keys → Create key**, copy it, paste it
   into Aide, and tap **Test connection**. (You pay Anthropic per use; see *Costs* below.)
2. **Speech-to-text key** (needed only for call/meeting recordings) - the default is **Groq**, which
   has a free tier: **console.groq.com/keys → Create API key**, paste it in. OpenAI also works.
3. **Your name and language** - so Aide knows which side of the call is you, and which language to
   write summaries in (e.g. English or Hindi). Calls in Hindi/Hinglish are understood.
4. **Permissions** - tap **Allow everything**. Also tap **Run in background → Open** and allow it.
   On **Xiaomi / Redmi / POCO / Oppo / Realme / Vivo** phones also enable **Autostart** for Aide
   (Settings → Apps → Aide → Autostart), otherwise the phone may stop Aide after calls.
5. **Turn on call recording in your phone's dialer** - Phone app → ⋮ → Settings → **Call recording
   → Record calls automatically** (the wording differs by brand). Aide picks up those recordings
   by itself after each call.

---

## 3. How you use it

- **Just make calls.** About 15 seconds after a call ends, Aide finds the recording, understands
  it, and does what was agreed. You get a notification like
  *"Call with Rahul: 📅 Meet Rahul · 🔎 Research co-working spaces"*.
- **Call not recorded?** (e.g. phones using the Google Phone app, which keeps recordings private)
  Aide sends a notification after the call: *"Anything to handle?"* - tap it, say one sentence
  ("meeting him Friday 11 at his office, I need to send the quote"), and Aide handles it.
- **Speak or type** on the Home screen: *"Remind me to call mom at 8"*, *"Research flights to Goa
  in December"*, *"Lunch with Priya tomorrow 1pm at Social"*.
- **Meetings in person:** tap **⏺ Meeting**, put the phone on the table, tap **Stop** at the end.
- **WhatsApp voice notes, texts, emails:** use **Share → Aide** from any app.
- **My list** tab: everything Aide is tracking - approvals, upcoming events, to-dos, notes. Tap
  **Export … (.ics)** to save your events, reminders and to-dos as a calendar file you can import into
  Google/Apple/Outlook calendar, or share.
- **Research** tab: ask anything, read reports with sources.
- **Review** tab: your daily reviews; tap **Review my day now** any time, or **Review my week** for a
  summary of the last 7 days (numbers, highlights, what is still open, what is coming next week).
- **Ask Aide** (button on the Home screen): ask questions about your own history - *"What did Rahul say
  about the price?"*, *"What's still pending for me?"*, *"What's my landlord's account number?"*. Aide
  answers **only from what it recorded**, says so when it has no answer, and links to the conversations
  the answer came from. It remembers the chat, so follow-ups like *"and by when do I send it?"* work.
  Under the question box, tap a person's name for a **pre-call briefing**: your history with them, what
  you each promised, what is overdue, and suggested talking points.

Prefer to approve things first? In **Settings → What I do automatically**, switch off the
automatic calendar / reminders / research and Aide will ask before each one.

---

## Costs

Aide uses your own API keys, so you pay the providers directly:
- **Claude Opus 5.5:** about $4 per million input tokens and $20 per million output tokens. A
  typical 5-minute call is roughly 2-5 cents to understand; a web research report roughly
  10-50 cents (it includes web searches). You can pick the cheaper **Claude Sonnet 5.5** or
  **Claude Haiku 5.5** in Settings. You can set a monthly spending limit in the Anthropic console.
- **Groq Whisper:** free tier covers typical personal use.
- Ask Aide, briefings and the weekly review each cost a fraction of a cent to a few cents (only the
  relevant part of your history is sent, never everything). **Settings → Claude** shows this month's
  calls and tokens used on your key.

## Privacy and the law

- Aide stores everything **on your phone only**. To understand a conversation, its text (and for
  recordings, the audio) is sent to Anthropic's Claude API and to your speech-to-text provider,
  using your keys. Nothing is sent anywhere else.
- **Recording calls can require the other person's consent** depending on where you live. Make
  sure you follow the law that applies to you.
- Requests that Claude's safety systems decline are automatically retried on Anthropic's
  recommended fallback model (server-side fallbacks are switched on).

## Limitations (honest list)

- Android does not let normal apps record call audio directly, so Aide relies on the recordings your
  phone's **own dialer** saves. Samsung, Xiaomi, OnePlus, Oppo, Realme and Vivo dialers do this; the
  Google Phone app does not share its recordings - use the after-call check-in instead.
- Speech recognition can mishear names or numbers - check the event details Aide shows you.
- Aide never sends messages, pays, or deletes anything on its own; it drafts and asks.

---

## For developers

```
core/   Pure Kotlin: Claude "brain" (analysis with structured output, web research with
        pause_turn handling, daily review), Whisper client, PCM/WAV tools, JSON store. Unit-tested.
app/    Android (Kotlin + Jetpack Compose): call detection, recording finder, WorkManager
        pipeline, calendar/reminder/alarm executors, meeting recorder, share target, UI.
```

- Build: `./gradlew :app:assembleDebug` (JDK 17, Android SDK 35). Tests: `./gradlew :core:test`.
- **Live tests** (real Claude / Whisper APIs, with your own keys; skipped when a key is missing and
  never run by `:core:test` or CI): `ANTHROPIC_API_KEY=... GROQ_API_KEY=... ./gradlew :core:liveTest`.
  They default to the cheapest model; set `AIDE_LIVE_MODEL=claude-opus-5-5` to test the app default.
  The speech tests synthesise audio with `ffmpeg` (needs the `flite` filter).
- New in `core/`: `search/HistorySearch` (offline keyword search, Hindi-safe), `brain/HistoryContext`
  (picks the relevant history for a question; all third-party text is sanitised), `AssistantBrain.ask /
  briefing / weeklyReview`, `people/PeopleIndex`, `review/WeeklyLog` (week numbers computed in code, not
  by the model), `export/IcsExporter` (RFC 5545), `Transcriber.transcribeDetailed` (language + timestamps),
  and token `Usage` on every Claude result.
- Pipeline: call ends → `CallStateReceiver` → `ScanWorker` finds the recording (MediaStore / chosen
  folder) and matches it to the call log → `ProcessWorker` → `Pipeline`: speech-to-text →
  `AssistantBrain.analyze` (Claude, JSON schema output) → `ActionExecutor` (CalendarContract,
  AlarmManager, research queue, drafts) → notification. `ReviewWorker` runs daily.
- Models: `claude-opus-5-5` (default), `claude-sonnet-5-5`, `claude-haiku-5-5`, chosen in Settings.
