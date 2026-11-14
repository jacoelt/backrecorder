---
name: debug
description: >-
  Diagnose a real defect on a real phone over wireless ADB, and report what is wrong — never fix it.
  Takes a GitHub issue number or a plain description of what to look for, connects to the device with
  `adb pair`/`adb connect` using an IP and pairing code asked from the user, arms a background logcat
  capture, then walks the user through the reproduction one step at a time while a background agent
  reads the logs for each step. Ends with a written root-cause report (evidence, exact file:line,
  what is NOT the cause) saved to a file and summarised in chat. The report is usually consumed by
  another agent that will implement the fix, so this skill stays available afterwards to answer
  follow-up questions from its captured logs. Use when the user says "/debug 57", "/debug the upgrade
  button does nothing", "debug this on my phone", "connect to my phone and find out why X", or when
  an agent asks for a device-level diagnosis of an issue. Do NOT use for fixing, for writing code, or
  for debugging that needs no device (static reading, CI failures, unit tests).
argument-hint: "<issue-number | what to look for>  (e.g. /debug 72  ·  /debug upgrade button spins forever)"
---

# /debug — find the fault on the device, and only find it

**Command**: `/debug <issue-number | description>`

A diagnosis run against a **real phone over wireless ADB**. It ends in a report, not a commit.

Repo: `jacoelt/backrecorder`. App id `com.backrecorderapp`, `minSdk` 26, `targetSdk` 36. `CLAUDE.md` is the architecture contract and is the fastest route from a symptom to the code that owns it — read the bullet that covers the area before you read the code.

**Pipeline:** 0 guards · 1 the brief · 2 where to look · 3 connect the phone · 4 arm the capture · 5 walk the user through it · 6 narrow it down · 7 write the report · 8 stay available.

---

## The one hard rule

**You diagnose. You do not fix, and you do not design the fix.**

- No edits to `app/src/**`, no commits, no branches, no PRs.
- No "the fix would be to…", no patch sketches, no "just change X to Y" in the report. Naming the defective line and saying *what it does wrong* is diagnosis; saying *what it should do instead* is the next agent's job, and a wrong suggestion from here is worse than none because it will be trusted.
- The one exception: temporary, **non-committed** instrumentation is allowed in Step 6 when nothing else will settle a question — and only after you have said so to the user, and only if you revert it before the run ends (`git status --porcelain` must be empty at Step 7).

If the user asks you to fix what you found, say the report is ready and that `/work <issue>` or a fresh session carries it — then hand over the report path. Do not start fixing inside this run.

---

## Step 0 — Guards

Each failure **stops** the run with the stated reason.

1. **`adb`.** It is **not on `PATH`** on this machine. Resolve it once and reuse it:

   ```bash
   ADB="D:/Development/Android/Sdk/platform-tools/adb.exe"; "$ADB" version
   ```

   Not there → look under `$LOCALAPPDATA/Android/Sdk/platform-tools/` and `$ANDROID_HOME/platform-tools/`. Found nowhere → stop and say Android SDK platform-tools are missing. Version must be **≥ 30.0.0** for `adb pair`; 35.0.2 is what is installed.
2. **Scratchpad.** Every capture, slice and intermediate file goes in the session scratchpad directory, never in the repo and never in `/tmp`. Make a run folder and keep its absolute path in `RUN` — you will quote it to the user, so it must stay a full path.
3. **Argument.** `$ARGUMENTS` must say what to look for. Empty → ask once, before anything else happens, and stop until answered.
4. **Clean tree.** `git status --porcelain` must be empty. This skill never writes to the working tree, so anything already there is the user's; note it and carry on, but refuse to run Step 6 instrumentation over a dirty tree.

---

## Step 1 — The brief

**A number** (or `#57`, or a `github.com/jacoelt/backrecorder/issues/57` URL) → read it:

```bash
gh issue view <N> --json number,title,body,labels,state,comments,url
```

Read the body **and the comments** — on this repo the real reproduction steps often arrive in a comment, not the body.

**Anything else** → the text is the brief as written. Do not go looking for a matching issue; the user chose words over a number for a reason.

Produce three things and put them in your first message to the user:

- **SYMPTOM** — what the user sees, in their words, in one sentence.
- **EXPECTED** — what should happen instead. If the brief does not say, and both readings lead to different diagnoses, ask now. This is the only question you may stop the run for before the phone is connected.
- **REPRO** — the shortest sequence of device actions you believe will produce the symptom. Numbered, each line one physical action. If the brief gives steps, use *those*, even if you think they are longer than needed — reproducing the user's path is the point.

`SYMPTOM`/`EXPECTED`/`REPRO` are the yardstick for the whole run. The report measures against them.

---

## Step 2 — Where to look, before you look

Ten minutes of reading here saves an hour of staring at logcat. Do this **before** connecting.

1. **Find the owning area in `CLAUDE.md`.** Billing and the upgrade flow, recording/segments/rotation, Drive auth and sync, notifications, API 36 targeting — each has a bullet that states the invariants and names the classes. The bullets also record defects already fixed; a symptom that matches one of them is either a regression or a different cause wearing the same clothes, and saying which is half the report.
2. **Read the code that owns the symptom.** Enough to name the functions on the path and the branches that could produce it. Use subagents for breadth (`Explore`) so the main context stays free for logs.
3. **Write 2–4 HYPOTHESES.** Each one: what is wrong, and **what you would expect to see in logcat if it were true** — and, as importantly, what you would see if it were false. A hypothesis that no log line can distinguish is not a hypothesis; replace it.
4. **Pick the tags.** This app's own tags are the spine of the capture:

   | Tag | Owns |
   | --- | --- |
   | `PlayBilling` | `PlayBillingRepository` — connect, product query, offer resolution, launch, acknowledge. Response codes and debug messages land here and **nowhere else**. |
   | `RecordingService` | foreground service lifecycle, rotation, segment start/stop, flush handshake |
   | `CloudSyncManager` | sync ticks, gate decisions |
   | `DriveAuthManager`, `DriveIdentity`, `DriveCloudStorage` | OAuth grant, token refresh, Drive REST calls |
   | `SettingsViewModel`, `BackRecorderApp`, `BootReceiver` | settings actions, app-launch resume, boot restart |
   | `waveform_canvas` | waveform drawing and touch |

   Platform tags that carry the other half of the story: `AndroidRuntime` (uncaught throw), `ActivityManager`/`ActivityTaskManager` (process death, ANR, foreground-service kills — `ForegroundServiceDidNotStartInTimeException` shows here), `Finsky` and `BillingClient` (Play's own side of a purchase), `WM-WorkerWrapper` (WorkManager), `libc`/`DEBUG` (native crash), `StrictMode`.

---

## Step 3 — Connect the phone

Ask, then connect. **Two addresses are involved and they are not the same** — confusing them is the single most common failure of this step.

On the phone: *Settings → System → Developer options → Wireless debugging*.
- The main **Wireless debugging** screen shows `IP address & Port` — this is the **connect** address.
- *Pair device with pairing code* opens a dialog showing a **different port** plus a **6-digit code** — that is the **pair** address, valid only while the dialog is open.

### Ask

Try `"$ADB" connect <ip>:<port>` with the connect address first: **pairing persists per machine**, so a phone paired in an earlier run needs no code. Ask for the pairing pair only if that fails.

Ask in prose (an IP and a code are free text, not a menu), one message, then end the turn with `wrap_up` carrying `blocked: true`:

> On your phone open **Settings → System → Developer options → Wireless debugging** and leave that screen open. Tell me the **IP address & Port** shown there (e.g. `192.168.1.42:37561`). If I can't connect I'll then ask you to tap **Pair device with pairing code** and read me that dialog's port and 6-digit code — the ports are different, so keep them apart.

Two more things in the same message, because each costs a whole round trip to discover later:
- the phone and this PC must be on the **same Wi-Fi network** (and Wireless debugging survives neither a Wi-Fi change nor, usually, a reboot);
- **which build is installed** — a Play internal build or a locally installed debug build. It changes what the logs will show (release is minified) and whether a symptom is even expected to be present.

### Connect

```bash
"$ADB" connect 192.168.1.42:37561
"$ADB" pair 192.168.1.42:41234 123456     # only if connect failed
"$ADB" devices -l
```

`adb devices` must list the address as `device`. Then confirm you are looking at the right software before you trust a single log line:

```bash
"$ADB" shell getprop ro.product.model; "$ADB" shell getprop ro.build.version.release
"$ADB" shell dumpsys package com.backrecorderapp | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime"
```

Record model, Android version, versionName/versionCode in the report header. A diagnosis is only valid for the build it was taken on.

### When it will not connect

| Symptom | Cause / move |
| --- | --- |
| `failed to connect ... actively refused` | wrong port (you used the pairing port), or the Wireless debugging screen was closed — the port changes when it is toggled |
| `failed to authenticate` / nothing in `devices` | not paired on this machine; take the pairing code route |
| `protocol fault` / `offline` | `"$ADB" kill-server` then `"$ADB" connect …` again |
| pairing says success, connect still fails | different Wi-Fi networks, or an AP with client isolation — ask the user to check both are on the same SSID |
| worked last run, not now | phone rebooted or dropped Wi-Fi; the port is new. Re-ask for the address |

Three failed attempts → stop and report what was tried. Do not guess ports.

---

## Step 4 — Arm the capture

**Capture everything, filter when reading.** A filtered capture cannot be un-filtered, and the line that explains the defect is routinely one you did not predict — a `Finsky` refusal, a kill by `ActivityManager`, a throw in a thread you weren't watching. Process-id filters are worse than useless here: the pid changes the moment the process dies, which is exactly the case you are chasing.

```bash
"$ADB" logcat -c                      # clear the ring buffer, so t=0 is now
"$ADB" logcat -b all -v threadtime     # -> background, redirected to $RUN/logcat.txt
```

Start it with Bash `run_in_background` and redirect to `$RUN/logcat.txt`. It never exits on its own; stop it at Step 7. Verify it is actually growing before you tell the user to do anything — a capture that silently failed turns the whole walkthrough into wasted taps.

**Mark the log, don't guess at timestamps.** Before and after each walkthrough step, write a marker into logcat itself:

```bash
"$ADB" shell log -p i -t DEBUGRUN "=== STEP 3 BEGIN: tap Upgrade to Premium ==="
```

The markers are what makes a slice exact. Correlating by wall clock across a PC and a phone is guesswork, and it is wrong often enough to cost a run.

**If the app should start clean**, decide this now and say so to the user, because both are destructive to their data on that device: `"$ADB" shell pm clear com.backrecorderapp` wipes preferences, the Room database and recordings; `"$ADB" shell am force-stop com.backrecorderapp` only kills it. Prefer force-stop. Never `pm clear` without asking first, in that message, in those words.

---

## Step 5 — Walk the user through it

The user is your hands. Give them **one step per message**, and wait.

Each step message is exactly this shape:

> **Step 3 of 5** — Tap **Upgrade to Premium** on the main screen.
> Watch for: does the button show a spinner, and does Play's purchase sheet appear?
> Tell me what you saw (and nothing happening is an answer).

Rules that make this work:

- **One physical action per step.** Two actions in one message and you cannot tell which produced the log.
- **Name the control as it is labelled on screen**, not as it is named in code. The user is looking at the phone, not at `MainScreen.kt`.
- **Say what to watch for** — the user sees things logcat never records: how long a spinner spun, whether a dialog flickered, which screen it returned to.
- **Always ask for the observation back**, and take "nothing happened" as data. A dead button with a clean log is itself the finding.
- **Marker, step, marker.** Write the BEGIN marker before you send the instruction, the END marker after the user answers.
- **Never tap for them.** Do not drive the UI with `adb shell input tap` during the walkthrough: it bypasses the real gesture path, and the symptom may live there. `input` belongs in Step 6, to replay something already understood.

### After each step: read the slice with a subagent

Do **not** paste logcat into your own context. Cut the slice between the two markers to its own file, and hand that file to a subagent:

```bash
sed -n '/STEP 3 BEGIN/,/STEP 3 END/p' "$RUN/logcat.txt" > "$RUN/step3.txt"
```

Brief the subagent with: the slice path, `SYMPTOM`/`EXPECTED`, the hypotheses and the tags from Step 2, and the user's observation for that step. Ask it to return, in at most ~15 lines: which hypotheses the slice **supports**, which it **rules out**, any exception/`ActivityManager` kill/response code with the exact log line quoted, and anything it did not expect. Explicitly tell it not to propose fixes.

Run the analysis for step *n* while the user performs step *n+1* — that is what the background agent is for, and it keeps the walkthrough at the user's pace instead of yours.

**Re-plan between steps.** If a slice kills a hypothesis, say so out loud and change the remaining steps. A walkthrough executed to the end after its premise died wastes the user's time and ends in a report with no cause in it.

**If the symptom does not reproduce**, that is a result, not a failure: vary one thing at a time (cold start vs warm, Wi-Fi vs mobile data, first tap after launch vs later, a different account), up to three variations. Still nothing → go to Step 7 and report *not reproducible on this build*, with everything that was tried and what it rules out. Say it plainly. A run that invents a cause it did not observe is worse than one that reports a clean reproduction failure.

---

## Step 6 — Narrow it down

A slice usually gets you to a suspect area, not a line. Close that gap.

- **State, not guesses.** `dumpsys` answers questions logs don't: `"$ADB" shell dumpsys activity services com.backrecorderapp` (is the foreground service actually alive?), `dumpsys package com.backrecorderapp` (granted permissions), `dumpsys deviceidle`, `dumpsys battery`, `dumpsys notification`.
- **Read the app's own files** where the defect is about persisted state: `"$ADB" shell run-as com.backrecorderapp ls -l files/ databases/` — debug builds only, and read-only.
- **Crash or ANR**: `"$ADB" logcat -b crash -d`, and `"$ADB" shell dumpsys dropbox --print` for the system's own record. For an ANR, the trace is in `/data/anr/` (pull via `"$ADB" bugreport "$RUN/bugreport.zip"` when `run-as` can't reach it).
- **Replay, now that you understand it**: `"$ADB" shell am start -n com.backrecorderapp/.MainActivity`, `"$ADB" shell input tap X Y`, `"$ADB" shell input keyevent` — for a deterministic re-run of a sequence the user already performed, to confirm it is reproducible on demand.
- **A screenshot settles UI-state arguments**: `"$ADB" exec-out screencap -p > "$RUN/screen.png"`, then read it.
- **Instrumentation is the last resort**, under the rule at the top of this file: temporary `Log.d` lines, announced to the user, installed with `./gradlew :app:installDebug`, and **reverted before Step 7**. Note in the report that the evidence came from added logging, so the next agent knows it will not find those lines.

Stop narrowing when you can name **the file, the line and the mechanism**, and say which log line proves it. If two hypotheses survive everything the device can tell you, the report says two — ranked, each with its evidence and with what would separate them.

---

## Step 7 — Write the report

Stop the background logcat. `git status --porcelain` must be empty.

Save to `.claude/debug-reports/<issue-or-slug>-<YYYY-MM-DD>.md` in the worktree, so the user and the next agent can open it. Create the folder if needed, and if `.claude/debug-reports/` is not in `.gitignore`, append it once — the reports are run artefacts, not repository content. **Copy the raw `logcat.txt` and the step slices in beside the report**; the scratchpad can be cleared at any time, and the report's evidence should outlive it.

```markdown
# Debug report — <symptom in one line>

**Brief:** <issue #N and title, or the text given> · **Run:** <date>
**Device:** <model>, Android <release> · **Build:** <versionName> (<versionCode>), <debug|Play internal>
**Reproduced:** yes / no / intermittently (<n> of <m> attempts)

## Symptom
What the user sees. What should happen instead.

## Root cause
The mechanism, in prose, from the first wrong thing to the visible symptom. Name `path/File.kt:123`
for each link in the chain.

## Evidence
Numbered. Each item: the quoted log line (or dumpsys output / screenshot), which step produced it,
and what it proves. The chain must be followable without the device.

## Ruled out
Each hypothesis considered and discarded, with the observation that killed it. This is the part that
stops the next agent re-walking ground you covered — do not drop it, even when it is long.

## Unknowns
What the device could not tell you, and what it would take to settle (a build with logging, a second
device, a Play Console check). Say so when a cause is still two candidates wide.

## Reproduction
The exact step sequence that produces it, as performed — including the variations that did and did
not reproduce it.

## Raw material
`logcat.txt`, the per-step slices, screenshots — paths, next to this file.
```

Two things about tone, both of which matter because the reader is usually another agent that will act on this without re-checking:

- **State certainty honestly.** "Confirmed by the log line in Evidence 3" and "most likely, unconfirmed" are different claims; never dress the second as the first.
- **No remedy.** Not in Root cause, not in Unknowns, not in a "Notes" section at the end.

Then, in chat: the file path, a 3–5 line summary (symptom → cause → the one decisive piece of evidence), and the paths to the raw logs. Someone must be able to act on the chat message alone.

---

## Step 8 — Stay available

This skill is usually invoked **by another agent** that will implement the fix, and that agent will come back with questions the report did not anticipate — "was the response code 3 or 5?", "did the service die before or after the flush?", "what did `dumpsys` say about the grant?".

So, at the end of the run:

- **Keep the raw capture.** Do not delete `logcat.txt` or the slices. They are the only way to answer a follow-up without re-running the whole walkthrough.
- **Keep the device connected** unless the user asks otherwise, and say that it is. A follow-up that needs one more `dumpsys` is cheap while connected and a new run once disconnected.
- **Answer from evidence.** For a follow-up, re-read the capture and quote it. Where the capture cannot answer, say so and offer one more device step — do not reason from the source about what the phone *would have* done, and do not slide into proposing the fix.
