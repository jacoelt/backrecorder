---
name: self-review
description: >-
  Iteratively review the current branch in rounds: a fan-out of focused reviewer agents tuned to
  this repo's Kotlin/Android stack (always-on + gated by what the diff touches + optional dynamic
  risk-hypothesis agents), plus CI and CodeRabbit when a PR exists, a rebase onto the latest base
  branch when the branch is behind, and a periodic drift check. Aggregates findings, decides which
  fixes are worth applying, applies and commits them, and loops until clean. In interactive mode the
  user picks the fixes; with --auto a dedicated triage agent picks them and the loop runs hands-off,
  which is how /work invokes it. Use when the user says "self review", "review my branch in rounds",
  "review my branch and fix what you find", "keep reviewing until clean", or "/self-review".
argument-hint: "[max-rounds] [--auto]  (default: 5 rounds, interactive)"
---

# Self-Review Loop (Back Recorder)

**Command**: `/self-review [max-rounds] [--auto]`

This is the Back Recorder port of the original cross-repo self-review skill. The machinery is the same; the reviewer scopes, gating signals and prompts are tuned to **this** repo — Kotlin, Jetpack Compose/M3, Hilt (KSP), Room, DataStore, WorkManager, Play Billing, Google Drive, one `:app` module — and the Go/Bazel/proto-specific scopes of the original are gone. `CLAUDE.md` at the repo root is the architecture contract every reviewer is pointed at.

Each round gathers findings from several sources:

| Source                  | When             | Timeout | Spawn                                                        |
| ----------------------- | ---------------- | ------- | ------------------------------------------------------------ |
| Focused reviewer agents | always           | —       | tiered fan-out of `general-purpose` subagents (Step 2a)      |
| CI checks on the PR     | when a PR exists | 15 min  | `gh pr checks --json` poll                                   |
| CodeRabbit on the PR    | PR + bot active  | 5 min   | `@coderabbitai review` comment + poll (auto-skips when absent) |

Reviewer agents are spawned **fresh each round** so none of them sees the previous round's findings, fixes, or rationale — only the diff and a one-paragraph "PR intent" summary the orchestrator builds in Step 2a. Each agent has a **narrow scope** so it can go deep instead of wide. Two scopes (**correctness**, **intent-and-consistency**) run every round; seven more are gated on what the diff actually touches; the orchestrator may then generate 0–3 **dynamic risk-hypothesis** agents. Every 3 rounds (when a PR exists), a **drift-check agent** audits the loop against the PR's stated purpose.

**Two modes.**

- **Interactive** (default) — the loop reports each round's findings and the user picks which to apply.
- **`--auto`** — a dedicated **triage agent** (`prompts/triage.md`, Opus) reads the round's findings, verifies them against the code, and decides `APPLY` / `SKIP` / `ESCALATE` for each one; the orchestrator applies what it approved. No `AskUserQuestion` is ever raised in this mode: every decision point below resolves the way the "auto" column says. This is how `/work` runs the loop. The triage agent's standing bias is **against** growing the diff — see `prompts/triage.md`.

**Why the reviewer prompts duplicate boilerplate.** Intentional — a spawned agent receives only its own prompt text, so no shared base file can load at run time. The HTML comment atop each `prompts/reviewer-*.md` flags the sync requirement.

**Section index:** Step 0 Model guard · Parameters · Step 1 Setup (+ rebase-if-behind) · Step 2 Gather findings (2.0 push · 2a fan-out · 2b CI + CodeRabbit · 2c aggregate) · Step 3 CLEAN vs ISSUES · Step 4 Choose fixes (user, or triage agent in auto) · Step 5 Apply fixes · Step 6 Drift check · Step 7 Round limit · Step 8 Final report · Safety rules.

---

## Step 0: Model guard

**Before anything else, confirm this session is running on Opus.** The orchestrator carries the reasoning-heavy work every round — distilling intent, generating hypotheses, deduping findings, and applying every fix — and the loop deliberately pins only the mechanical reviewer scopes to Sonnet.

Read your own model identity from the system prompt:

- **Opus family** → proceed to Step 1.
- **Identified as non-Opus** → **STOP** and reply exactly:

  > ⚠️ /self-review needs Opus — run `/model opus`, then re-invoke.

  In `--auto` mode, stop the same way and report the reason to the caller; `/work` treats it as a hard failure.
- **Model can't be identified** → in interactive mode, say so and ask (`AskUserQuestion`: **Stop** *(Recommended)* / **Continue anyway**). In `--auto` mode, log one line and continue.

---

## Parameters

- `max-rounds` (optional integer, default 5) — review iterations before the round-limit check. Parsed from the first whitespace-delimited token of `$ARGUMENTS` that isn't a flag; falls back to 5.
- `--auto` — run hands-off with the triage agent deciding fixes (see Step 4). Default is interactive.

---

## Step 1: Setup

Run from a feature branch:

```bash
git fetch origin
git remote set-head origin -a >/dev/null 2>&1 || true
CURRENT_BRANCH=$(git branch --show-current)
[ -z "$CURRENT_BRANCH" ] && { echo "detached HEAD — check out the feature branch first"; exit 1; }
BASE_BRANCH=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's@^origin/@@')
[ -z "$BASE_BRANCH" ] && git rev-parse --verify --quiet origin/main >/dev/null && BASE_BRANCH=main
[ -z "$BASE_BRANCH" ] && { echo "no trunk detected — ask for the base branch"; exit 1; }
[ "$CURRENT_BRANCH" = "$BASE_BRANCH" ] && { echo "on the trunk ($BASE_BRANCH) itself — check out a feature branch"; exit 1; }
```

The trunk here is `main`; the detection above still reads `origin/HEAD` first so the skill keeps working if that ever changes.

**Rebase onto the latest base branch (if behind).** Review the code as it will actually merge — a stale base hides conflicts and lets a review pass on a combination that never lands.

```bash
BEHIND=$(git rev-list --count "HEAD..origin/$BASE_BRANCH")
```

- `BEHIND = 0` → log `> up to date with origin/<BASE_BRANCH>` and continue.
- `BEHIND > 0` → **dirty tree first**: if `git status --porcelain` is non-empty, never stash silently. Interactive: ask **Skip rebase** / **Stop**. Auto: **stop the loop** and report the dirty tree — in a `/work` run the tree should always be clean, so a dirty tree means something upstream went wrong.
  Then rebase. Interactive: confirm first (**Rebase** *(Recommended)* / **Skip rebase** / **Stop**), since the user invoked *review*, not *rebase*, and republishing a pushed branch needs a force-push. Auto: rebase without asking — `/work` owns the branch and invoking it is the go-ahead.
  Either way, delegate the mechanics to the **`rebase` skill** (`Skill` tool, `skill: "rebase"`, args `--no-squash --auto` in auto mode). Never duplicate them here.

  On return: rebased cleanly → continue. Aborted or declined → treat as skip and note the base is stale. Left in progress → **stop**; a half-applied rebase can't be reviewed.

Then compute the review range:

```bash
MERGE_BASE=$(git merge-base "origin/$BASE_BRANCH" HEAD)
CHANGED_FILES=$(git diff --name-only "$MERGE_BASE"..HEAD)
```

No changed files → stop; there's nothing to review.

Initialize `TOTAL_ROUNDS=1`, `TOTAL_FIXES=0`, `ROUND_LIMIT=max-rounds`. `TOTAL_ROUNDS` is cumulative and never resets.

Detect the PR and the external signals:

```bash
PR_NUMBER=$(gh pr view --json number --jq .number 2>/dev/null || echo "")
REPO_FULL=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || echo "")
IS_DRAFT=$(gh pr view --json isDraft --jq .isDraft 2>/dev/null || echo false)
HAS_CODERABBIT=""
{ git cat-file -e "HEAD:.coderabbit.yaml" 2>/dev/null || git cat-file -e "HEAD:.coderabbit.yml" 2>/dev/null; } && HAS_CODERABBIT=true
if [ -z "$HAS_CODERABBIT" ] && [ -n "$PR_NUMBER" ]; then
  gh api "repos/$REPO_FULL/issues/$PR_NUMBER/comments" \
    --jq 'any(.[]; .user.login | test("coderabbit";"i"))' 2>/dev/null | grep -qx true && HAS_CODERABBIT=true
fi
```

`HAS_PR=true` when `PR_NUMBER` is non-empty. CodeRabbit is **not** installed on this repo today, so `HAS_CODERABBIT` will normally be false and that whole signal silently drops out; the detection stays so it lights up by itself if the bot is ever added.

Brief the user once:

```
Self-review starting on <branch> — <N> file(s) changed vs origin/<BASE_BRANCH>.
Mode: <interactive | auto (triage agent decides fixes)>. Max rounds: <max-rounds>.
External signals: <CI on PR #N | CI + CodeRabbit on PR #N | none (no PR)>.
Fixes are committed each round — and pushed to the PR when one exists.
```

There is **no `/simplify` pre-pass** in this port. In auto mode an unprompted quality pass is exactly the kind of diff growth the triage agent exists to prevent; run `/simplify` yourself before invoking the loop if you want one.

---

## Step 2: Gather review findings

**Runtime order when `HAS_PR=true`:**

1. `git push` (Step 2.0) — so external signals run against fresh HEAD.
2. One message that spawns all reviewer agents (foreground) **and** launches the CI poll (and CodeRabbit poll when active) with `run_in_background: true`.
3. Await the agent results and the poll notifications, then aggregate (2c).

When `HAS_PR=false`, step 1 and the polls drop out.

### 2.0 — Push first (only when `HAS_PR=true`)

Decide what's unpushed from the **remote branch**, not `@{upstream}`:

```bash
git fetch origin "$CURRENT_BRANCH" 2>/dev/null
UNPUSHED=$(git rev-list --count "origin/$CURRENT_BRANCH..HEAD" 2>/dev/null || echo MISSING)
```

- `0` → nothing to push.
- `>0` or `MISSING` → interactive: confirm before publishing (**Push & review** / **Review without pushing** / **Stop**), honoring a prior "don't push" while HEAD is unchanged. Auto: push without asking — the PR already exists and `/work` owns the branch.

Always use an explicit refspec; re-fetch immediately before pushing:

```bash
git fetch origin "$CURRENT_BRANCH" 2>/dev/null
git push origin HEAD:"$CURRENT_BRANCH"
```

If the push fails, surface the error and stop the loop — don't continue with stale signals.

### 2a — Build context, gate the fan-out, spawn agents

#### 2a.i — Context, intent, gating signals (main session, no agents yet)

1. **Changed files**: `git diff --name-only $MERGE_BASE..HEAD` and `git diff --stat $MERGE_BASE..HEAD`.

2. **PR intent** — when `HAS_PR=true`: `gh pr view $PR_NUMBER --json title,body,baseRefName,headRefName`. Distil into a **1-paragraph summary** (≤ 4 sentences). If the body references a GitHub issue (`Closes #N`), fold in `gh issue view N --json title,body,labels`. When `HAS_PR=false`, derive intent from `git log $MERGE_BASE..HEAD --format=%B`. Stash as `INTENT_SUMMARY`.

3. **Gating signals** — compute these yourself from the file list and the diff (cheap `grep` / path inspection, no agent). When uncertain, default to **true**:

   | Signal               | True when                                                                                                                                                                                                 |
   | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `TOUCHES_CODE`       | Any `.kt` / `.kts` / `.java` file changed (excluding pure comment lines)                                                                                                                                  |
   | `TOUCHES_BEHAVIOR`   | Any behavior-changing line in `.kt` / `.java` (not pure docs, comments, whitespace, or imports-only edits)                                                                                                 |
   | `TOUCHES_RUNTIME`    | Any Service, Activity, Application, BroadcastReceiver, Worker, composable, `AndroidManifest.xml`, coroutine/flow/`Mutex` code, notification code, or permission handling changed — **default true whenever `TOUCHES_BEHAVIOR` is true and the change isn't confined to a pure-logic helper** |
   | `TOUCHES_BOUNDARIES` | Anything touching auth/OAuth/`DriveAuthManager`, billing/`SubscriptionGate`, permissions, exported components, `PendingIntent`s, file paths or storage, logging of user data, `proguard-rules.pro`, `.gitignore`, or any workflow file |
   | `TOUCHES_PERSISTENCE`| Any Room entity/DAO/database/migration, DataStore or preference key, on-disk segment layout or file naming, Drive folder layout, notification channel id, or `version.properties` changed                   |
   | `TOUCHES_BUILD`      | Any `*.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/**`, `gradlew*`, `proguard-rules.pro`, `AndroidManifest.xml`, `.github/workflows/**`, or `.gitattributes` changed      |
   | `TOUCHES_REFACTOR`   | **Cumulative across branch commits**: deletions > 50 lines on any single file, OR > 3 files touched in the same directory (commands below)                                                                 |
   | `TOUCHES_COMMENTS`   | The diff adds any `+` comment or KDoc line (`//`, `/*`, `*`, `///`) in a source file                                                                                                                      |

   `TOUCHES_REFACTOR` must look at per-commit churn, not the net diff — a branch that adds 100 lines then walks 60 back has a net of +40 but 60 lines of deletion-churn, which is exactly where stubs and orphans accumulate:

   ```bash
   git log --numstat --format= $MERGE_BASE..HEAD \
     | awk 'NF==3 && $1 != "-" {del[$3] += $2} END {for (f in del) if (del[f] > 50) print f, del[f]}'
   git log --name-only --format= $MERGE_BASE..HEAD | sort -u \
     | awk -F/ 'NF>1 {OFS="/"; NF--; print}' | sort | uniq -c | awk '$1 > 3'
   ```

   Either producing output flips it true.

   ```bash
   git diff "$MERGE_BASE"..HEAD -- '*.kt' '*.kts' '*.java' | command grep -E '^\+[[:space:]]*(//|/\*|\*|///)'
   ```

   Any output → `TOUCHES_COMMENTS=true`.

#### 2a.ii — Decide which agents to spawn

| Scope                          | When                   | Model  | Prompt                                 |
| ------------------------------ | ---------------------- | ------ | -------------------------------------- |
| **correctness**                | always                 | Opus   | `prompts/reviewer-correctness.md`      |
| **intent-and-consistency**     | always                 | Sonnet | `prompts/reviewer-intent.md`           |
| android runtime                | `TOUCHES_RUNTIME`      | Opus   | `prompts/reviewer-android.md`          |
| security & privacy             | `TOUCHES_BOUNDARIES`   | Opus   | `prompts/reviewer-security.md`         |
| data & upgrade compatibility   | `TOUCHES_PERSISTENCE`  | Opus   | `prompts/reviewer-compat.md`           |
| build hygiene                  | `TOUCHES_BUILD`        | Sonnet | `prompts/reviewer-build.md`            |
| tests                          | `TOUCHES_BEHAVIOR`     | Sonnet | `prompts/reviewer-tests.md`            |
| conventions                    | `TOUCHES_CODE`         | Sonnet | `prompts/reviewer-conventions.md`      |
| dead code                      | `TOUCHES_REFACTOR`     | Sonnet | `prompts/reviewer-dead-code.md`        |
| comment length & relevance     | `TOUCHES_COMMENTS`     | Sonnet | `prompts/reviewer-comment-quality.md`  |

**Model per scope — this table is the single source of truth.** Pass `model` **explicitly** at spawn; nothing inherits silently. Checklist-driven scopes (intent, build, tests, conventions, dead code, comments) run on **Sonnet**: they match the diff against a known spec or symbol set. The adversarial scopes — correctness, android runtime, security, compatibility, the dynamic hypothesis agents, the drift-check agent and the **triage** agent — stay on **Opus**, because a missed crash, leak, migration or entitlement bug ships to a user's phone and can't be rolled back. Every agent in a round runs in parallel, so the round's wall-clock floor is set by the Opus scopes anyway.

Log one line per enabled scope naming its gating signal (`> running compat: Room entity changed`), and log the scopes gated **off** so it's visible what wasn't checked.

#### 2a.iii — Generate 0–3 dynamic risk hypotheses (optional)

Inspect the diff + `INTENT_SUMMARY` and decide whether the change introduces **specific, investigable** risks the fixed scopes don't cleanly cover. Good examples for this app:

- "If the process is killed between the flush deferred completing and the segment being written, does the next launch see a half-written MP4 it can't extract?"
- "Does the new sync entry point pass `SubscriptionGate` on the app-launch resume path as well as the settings toggle?"
- "Can a `grantGeneration` change land between the folder-id lookup and the upload, so segments go to the previous account's folder?"
- "Does the end-handle clamp still reach the currently-recording minute after this change to `bufferEndTime`?"

Rules: **hard cap at 3**; one hypothesis per agent; skip the phase entirely for small or doc-only diffs or when you can't articulate something both specific and investigable; use `prompts/reviewer-hypothesis.md` with `<hypothesis>` / `<investigation_hints>` substituted; spawn each on **Opus**. Log each one (`> hypothesis-1: <question>`).

#### 2a.iv — Spawn in a single message

For each selected agent: read its prompt file, substitute `<cwd>` (absolute worktree path), `<base_branch>`, `<intent_summary>` (and the hypothesis fields), then pass the result as the agent's prompt with `subagent_type: "general-purpose"` and the `model` from the 2a.ii table.

**Spawn all selected agents in one message** so they run concurrently; they are foreground, so the turn blocks until every one returns. Launch Step 2b's polls (`run_in_background: true`) **in that same message**. Don't fold Step 2.0's push into it.

### 2b — External PR signals (only when `HAS_PR=true`)

**Load `references/pr-signals.md`** for the poll/parse commands, the timeout table, this repo's known CI failure signatures, and the CodeRabbit thread-resolution mutation. The essentials: poll `gh pr checks` up to 15 min and convert failures into `[major] [ci]` issues; trigger and poll CodeRabbit only when `HAS_CODERABBIT=true`; both are best-effort — on timeout, note one line and continue. An **empty** CI rollup is a `[minor]` finding, never a pass.

### 2c — Aggregate

Combine every source into one list, tagging each entry `[reviewer:<scope>]`, `[reviewer:hypothesis-<N>]` (carrying its `VERDICT`), `[ci]`, or `[coderabbit]`. De-duplicate when sources report the same file:line with substantially the same problem — keep the most specific entry and note all sources. Fan-out **will** overlap; budget for it.

If every spawned agent returns `STATUS: CLEAN` and CI + CodeRabbit report nothing actionable, the round is CLEAN. Scopes that weren't spawned don't count either way.

---

## Step 3: Decide CLEAN vs ISSUES

| Aggregated state            | Action                                            |
| --------------------------- | ------------------------------------------------- |
| No actionable issues        | Jump to Step 8 (Final report) with status `CLEAN` |
| Any actionable issues       | Continue to Step 4                                |

Parsing each agent's reply:

| First line       | Meaning                                                                          |
| ---------------- | -------------------------------------------------------------------------------- |
| `STATUS: CLEAN`  | Nothing from this scope                                                           |
| `STATUS: ISSUES` | Parse the issue list                                                              |
| anything else    | Surface the raw output verbatim and treat as `ISSUES` for that scope             |

Hypothesis agents prepend `VERDICT:` / `HYPOTHESIS:` / `EVIDENCE:` before the status block. A `VERDICT: no` + `STATUS: CLEAN` contributes nothing.

If the **same** scope returns a malformed response twice in a row across rounds, stop the loop and report. One malformed scope in one round is fine — surface it, treat as `ISSUES`, continue.

---

## Step 4: Choose which fixes to apply

Print the round summary either way:

```
Round <TOTAL_ROUNDS>/<ROUND_LIMIT> — <count> issue(s)
Spawned: <scopes that ran>   Skipped (gated off): <scopes that didn't>
Sources: <per-scope counts>, <I> ci<, <C> coderabbit>
Hypotheses: <each one's "VERDICT: <v>  <question>"> (or "none")
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. [<severity>] [<source>] <path:line>
   Problem: <problem>
   Fix:     <fix>
...
```

### 4a — Interactive mode (default)

`AskUserQuestion` with `multiSelect: true`, one option per issue plus **Skip all**. Selected ≥ 1 → Step 5. Nothing or **Skip all** → ask **Run another review anyway** / **Stop here**; the latter exits to Step 8 as `STOPPED BY USER`.

### 4b — Auto mode (`--auto`)

Spawn **one** triage agent — `subagent_type: "general-purpose"`, `model: "opus"`, prompt `prompts/triage.md` — substituting `<cwd>`, `<base_branch>`, `<intent_summary>`, `<issue_context>` (the GitHub issue body + labels, or "none" when there isn't one), `<round>` / `<round_limit>`, and pasting the aggregated findings list.

It returns a `TRIAGE:` header line and `APPLY` / `SKIP` / `ESCALATE` / `FOLLOW_UPS` sections. Then:

1. **Print its verdict verbatim** — the user is not in the loop, so the transcript is the only record of what was decided and why.
2. **Sanity-check it yourself** before applying. You are still the orchestrator: if an `APPLY` entry proposes something you can see is wrong, out of scope, or larger than the finding warrants, drop it and say so in one line. If the agent returned a malformed verdict, retry it once; on a second failure, stop the loop and report (don't fall back to applying everything).
3. `APPLY` entries (after your check) → Step 5.
4. `ESCALATE` entries → **stop the loop** and exit to Step 8 with status `ESCALATED`, listing them. Fixes already applied in earlier rounds stay. `/work` surfaces this to the user and does not merge.
5. No `APPLY` entries and no `ESCALATE` → the round changed nothing. Treat the loop as converged, exit to Step 8 with status `CLEAN (triage skipped all <N> finding(s))`, and carry the skip reasons into the report.
6. Carry `FOLLOW_UPS` through to the final report so `/work` can file them as issues.

---

## Step 5: Apply the chosen fixes

Apply each fix yourself with Edit/Write/Bash. Do **not** delegate to a subagent — the fix is the orchestrator's responsibility, and in auto mode the triage agent's `How:` line is the spec.

- Stage only the files you touched: `git add <specific paths>` — never `git add -A`.
- Apply the **smallest** change that fixes the finding. Do not take the opportunity to restructure.
- If a fix turns out to be ambiguous: interactive, ask; auto, skip it and record why in the report.
- If applying it reveals the finding was wrong, say so and skip it — don't fabricate a change.
- Re-run the unit tests locally when a fix touches behavior (`./gradlew :app:testDebugUnitTest`). A fix that breaks the build or the tests is reverted, not pushed: in auto mode, revert that single fix, record it, and continue with the rest.

Commit all of the round's fixes in **one** commit:

```bash
git commit -m "$(cat <<'EOF'
fix(review): address self-review round <TOTAL_ROUNDS>

- <short bullet per fix>

<attribution trailer>
EOF
)"
```

Use the conventional-commit type/scope the repo already uses (`fix(cloud):`, `build:`, `feat(ui):` …) when one is obvious. `<attribution trailer>` is whatever co-authorship line this session is instructed to use on commits.

When `HAS_PR=true`, push with an explicit refspec — never a bare `git push`, never a force-push:

```bash
git push origin HEAD:"$CURRENT_BRANCH"
```

If the push is rejected, surface it and stop the loop.

When `HAS_CODERABBIT=true`, resolve the threads for CodeRabbit findings you actually applied (see `references/pr-signals.md`); leave skipped ones open with a one-line reply. Increment `TOTAL_FIXES` by the number actually applied.

---

## Step 6: Drift check (every 3 rounds)

Skip when `TOTAL_ROUNDS % 3 != 0` or when `HAS_PR=false`.

1. `gh pr view $PR_NUMBER --json title,body`
2. `git log --oneline $MERGE_BASE..HEAD` and `git diff --stat origin/$BASE_BRANCH...HEAD`
3. Spawn a fresh drift-check agent (`general-purpose`, `model: "opus"`) with `prompts/drift-check.md`, substituting `<PR_NUMBER>`, `<REPO_FULL>`, `<TOTAL_ROUNDS>`, `<base_branch>` and pasting the context into the marked slots.
4. Wait for it (foreground).
5. Branch on the verdict:
   - `STATUS: ON_TRACK` → continue silently to Step 7.
   - `STATUS: DRIFTING` → print the report verbatim. Interactive: ask **Continue anyway** / **Stop here** / **Reset direction**. **Auto: stop the loop** and exit to Step 8 with status `STOPPED (drift)`, keeping the fixes already applied and carrying the drift report into the final report. A loop that has wandered off the issue is exactly the case where a human should look before anything merges, and `/work` will not merge on this status.
   - Malformed verdict → surface it; interactive asks, auto continues to Step 7 and notes it.

---

## Step 7: Round-limit handling

- `TOTAL_ROUNDS < ROUND_LIMIT` → increment and loop to Step 2.
- `TOTAL_ROUNDS >= ROUND_LIMIT` → interactive: ask **Run `<max-rounds>` more rounds** (extend: `ROUND_LIMIT += max-rounds`) / **Stop here**. Auto: **do not extend** — exit to Step 8 with status `MAX ROUNDS REACHED`. Hitting the cap in auto mode means the loop isn't converging, and silently buying more rounds is how a hands-off run turns into an unbounded bill.

`TOTAL_ROUNDS` never resets; only `ROUND_LIMIT` grows.

---

## Step 8: Final report

```
Self-review complete
━━━━━━━━━━━━━━━━━━━━
Status:  <CLEAN | ESCALATED | STOPPED (drift) | STOPPED BY USER | MAX ROUNDS REACHED>
Rounds:  <total>
Fixes:   <TOTAL_FIXES> applied<, N skipped by triage>
```

List any commits the loop created (short hash + subject). In auto mode, also list: each `ESCALATE` entry, anything skipped because applying it failed, and the accumulated `FOLLOW_UPS`. `/work` reads this report to decide whether to go on to the PR/merge/release steps — only `CLEAN` is a green light.

---

## Safety rules

- All reviewer, hypothesis, drift-check and triage subagents are **read-only**. Their prompts say so; never hand results back to a subagent to apply.
- Never `git add -A` or `git add .` — always stage specific paths.
- Never force-push **except** the rebase-sync in Step 1, which is delegated to the `rebase` skill (`--force-with-lease`, pinned to the pre-rebase remote SHA). The loop's own pushes are never forced. Never `--amend` a pushed commit, never `--no-verify`.
- Never change a PR's draft status (`gh pr ready` / `--undo`) — that's the caller's decision.
- If a chosen fix fails (build break, test failure, conflict): interactive, stop and ask; auto, revert that one fix, record it, and continue. Never leave broken changes staged or pushed.
- If a reviewer agent times out or returns nothing, retry that scope once; on a second failure stop and report. Other scopes' findings stay usable — never gate a round on the slowest scope.
- Dynamic hypothesis agents are capped at **3** per round.
- **Respect the gating signals.** A scope the signals didn't enable isn't spawned, however tempting — the gating is what keeps cost proportional to risk.
- In auto mode the triage agent decides, but the orchestrator still has a veto on any `APPLY` it can see is wrong — and never the reverse: never apply something triage marked `SKIP`, and never overrule an `ESCALATE`.
- Auto mode never extends the round limit, never resolves a drift verdict on its own, and never merges, releases or touches anything outside the branch — `/work` owns those steps.
- Do not loop silently: every round's findings, decisions and fixes are printed before anything is written.

$ARGUMENTS
