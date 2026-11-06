---
name: rebase
description: >-
  Rebase the current feature branch onto the latest base branch, safely. Auto-detects the trunk from
  origin/HEAD (falling back to main), cross-checks it against the branch's open PR base, and rebases
  when the branch is behind — optionally squashing the branch's own commits first. Republishes an
  already-pushed branch with git push --force-with-lease pinned to the pre-rebase remote tip, never
  plain --force. Refuses to touch uncommitted work silently, pauses if the remote branch is ahead of
  local, and on conflicts aborts back to the exact pre-rebase state, resolves with you, or leaves the
  rebase in progress — your choice, never an auto-resolve. With --auto it runs unattended and stops
  instead of asking. Use when the user says "/rebase", "rebase my branch", "bring my branch up to date
  with main", or when /self-review or /work needs the branch synced before reviewing. Not for opening
  or merging a PR.
argument-hint: "[--squash|--no-squash] [--auto]  (base is always auto-detected)"
---

# Rebase onto the latest base branch

Bring a feature branch up to date with the trunk by rebasing it onto a freshly-fetched `origin/<base>`, then republish it if it's already pushed — without the merge-commit noise a `git merge` would leave. This is the Back Recorder port of the standalone rebase skill: the cross-repo PR-argument resolution is gone (there is one repo and one clone here), and an `--auto` mode was added so `/self-review --auto` and `/work` can call it unattended.

**It runs start to finish without a confirmation prompt** — invoking it is the go-ahead. It pauses only where judgement is genuinely needed: a detected-trunk vs PR-base mismatch (Step 1), uncommitted changes (Step 2), a remote branch ahead of local (Step 3), whether to squash (Step 4), or a conflict (Step 5).

**`--auto` mode.** Every pause below resolves to the "auto" branch instead of asking, and each one that isn't safe to decide unattended **stops** the skill with a clear reason for the caller. Auto mode never stashes, never drops remote commits, and never resolves a conflict.

---

## Step 1 — Detect the branch and the base

```bash
git fetch origin
git remote set-head origin -a >/dev/null 2>&1 || true
CURRENT_BRANCH=$(git branch --show-current)
[ -z "$CURRENT_BRANCH" ] && { echo "detached HEAD — check out the branch you want to rebase"; exit 1; }
BASE_BRANCH=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's@^origin/@@')
if [ -z "$BASE_BRANCH" ]; then
  for b in main master develop; do
    git rev-parse --verify --quiet "origin/$b" >/dev/null && { BASE_BRANCH="$b"; break; }
  done
fi
[ -z "$BASE_BRANCH" ] && { echo "no trunk detected — can't auto-detect a base for $CURRENT_BRANCH"; exit 1; }
[ "$CURRENT_BRANCH" = "$BASE_BRANCH" ] && { echo "on the base branch ($BASE_BRANCH) itself — check out a feature branch"; exit 1; }
```

The trunk here is `main`; the detection reads `origin/HEAD` first so it keeps working if that changes.

Auto-detect resolves the repo **trunk**, which isn't always this branch's real base — a branch stacked on another feature branch, or whose PR targets a release branch, integrates elsewhere. Rebasing onto the trunk there replays the wrong commits and then force-pushes them. Cross-check:

```bash
PR_BASES=$(gh pr list --state open --head "$CURRENT_BRANCH" --json baseRefName -q '[.[].baseRefName]|unique|.[]' 2>/dev/null || echo "")
```

Drop every base equal to `BASE_BRANCH`. If nothing is left, continue silently. Otherwise an open PR targets a non-trunk base:

- **Interactive**: `AskUserQuestion` — "`<branch>` has an open PR targeting `<base>`, not the detected trunk `<BASE_BRANCH>`. Which should it rebase onto?" with one option per distinct non-trunk base (verify it still exists on origin before using it), the trunk, and **Stop**.
- **Auto**: **stop** — report the mismatch and let the caller decide. Replaying a stacked branch onto the trunk unattended is how commits get lost.

## Step 2 — Check whether a rebase is needed, and handle uncommitted work

```bash
BEHIND=$(git rev-list --count "HEAD..origin/$BASE_BRANCH")
```

- `BEHIND = 0` → report `> <branch> is already up to date with origin/<base> — nothing to rebase.` and stop. Nothing has changed.
- `BEHIND > 0` → continue.

```bash
git status --porcelain
```

Empty → Step 3. Non-empty → never stash silently:

- **Interactive**: `AskUserQuestion` — **Stop** *(Recommended)* (commit or stash yourself, then re-run) or **Stash & restore** (rebase with `--autostash`; warn that a conflicting re-apply leaves markers in the tree with the stash preserved). Record as `AUTOSTASH=yes|no`.
- **Auto**: **stop** with the dirty paths listed. `AUTOSTASH` is never `yes` in auto mode.

## Step 3 — Capture the remote state

Read the remote tip **before** any rewriting — it decides whether republishing needs a force-push and pins the lease:

```bash
REMOTE_SHA=$(git rev-parse --verify --quiet "origin/$CURRENT_BRANCH" 2>/dev/null || echo "")
[ -n "$REMOTE_SHA" ] && REMOTE_EXISTS=yes || REMOTE_EXISTS=no
REMOTE_ONLY=0
[ "$REMOTE_EXISTS" = yes ] && REMOTE_ONLY=$(git rev-list --count "HEAD..$REMOTE_SHA")
```

`--force-with-lease` protects against a push landing *after* this moment, but not against commits already on `origin/<branch>` that local `HEAD` lacks — the rebase replays only local commits, so those would be silently dropped and the lease wouldn't catch it. If `REMOTE_ONLY > 0`, show them (`git log --oneline "HEAD..$REMOTE_SHA"`) and:

- **Interactive**: ask — **Stop** *(Recommended)* (integrate them first, e.g. `git pull --rebase`) or **Rebase anyway** (you know they're unwanted).
- **Auto**: **stop**. Dropping commits unattended is never acceptable.

## Step 4 — Squash the branch's own commits (optional, before the rebase)

Squashing first means the rebase replays exactly one commit, so there's at most one conflict to resolve instead of one per commit — and it's conflict-free in itself, since the branch still sits on its original merge-base.

```bash
PRE_REBASE_SHA=$(git rev-parse HEAD)    # survives the resets that clobber ORIG_HEAD
SQUASH_BASE=$(git merge-base "origin/$BASE_BRANCH" HEAD)
OWN_COMMITS=$(git rev-list --count "$SQUASH_BASE..HEAD")
```

Set `SQUASH=no` when `AUTOSTASH=yes` (history rewriting and WIP juggling don't mix) — and say so if the user explicitly asked to squash. Otherwise honor an explicit `--squash` / `--no-squash` token or the user's phrasing. Failing that:

- `OWN_COMMITS <= 1` → `SQUASH=no`, don't ask.
- `OWN_COMMITS > 1`, interactive → ask: **Keep separate** *(Recommended)* or **Squash into one**.
- `OWN_COMMITS > 1`, auto → `SQUASH=no` unless `--squash` was passed. Collapsing someone's history unasked is not an unattended decision.

**To squash** (clean tree only): capture the subjects first, since the reset empties the range.

```bash
SQUASH_SUBJECTS=$(git log --reverse --format='- %s' "$SQUASH_BASE..HEAD")
SQUASHED_COUNT="$OWN_COMMITS"
git reset --soft "$SQUASH_BASE"
git commit -m "<subject>" -m "<body>"
```

Never open an editor. The subject is the PR title when a PR exists, otherwise a concise summary; the body is `SQUASH_SUBJECTS`. In interactive mode show the message for approval first; in auto mode use it directly. Nothing is pushed yet, so if this is interrupted between the reset and the commit, `git reset --soft "$PRE_REBASE_SHA"` restores the branch exactly as found.

## Step 5 — Rebase

```bash
git rebase origin/$BASE_BRANCH          # append --autostash when AUTOSTASH=yes
```

- **Success** → *After the rebase completes*, below.
- **Conflicts** → never auto-resolve, never blindly abort. Show `git diff --name-only --diff-filter=U`, then:
  - **Interactive**: ask — **Abort** *(Recommended)* (`git rebase --abort`, restoring the exact pre-rebase state; with `--autostash` it also restores the stash; if Step 4 squashed, note the abort restores the *squashed* commit and offer `git reset --soft "$PRE_REBASE_SHA"`), **Resolve with help** (per file: show the conflict, propose a resolution, `git add` only approved paths, then `git rebase --continue` — never `--skip`, never `--continue` over unresolved markers), or **Leave in progress** (stop without aborting and exit before Steps 6–7).
  - **Auto**: `git rebase --abort`, then **stop** and report the conflicting paths. The caller decides.

**After the rebase completes**: when `AUTOSTASH=yes`, the stash re-apply can conflict even though the rebase succeeded:

```bash
git ls-files --unmerged
```

Non-empty → set `AUTOSTASH_CONFLICT=yes`, tell the user their WIP needs resolving, and carry it into the report. The rebased commits are independent of this, so continue to Step 6.

## Step 6 — Republish (only if the branch was already pushed)

Skip entirely when `REMOTE_EXISTS=no`. Otherwise this is the **only** force-push in the skill — always `--force-with-lease`, never plain `--force`, always an explicit refspec, pinned to the `REMOTE_SHA` captured in Step 3:

```bash
git push --force-with-lease="$CURRENT_BRANCH:$REMOTE_SHA" origin HEAD:"$CURRENT_BRANCH"
```

If the lease is rejected — someone pushed since Step 1's fetch — **stop** and surface it. The rebase already succeeded locally, so the branch is fine; the divergence is reconciled by hand. **Never** fall back to plain `--force`.

## Step 7 — Report

```bash
REPLAYED=$(git rev-list --count "origin/$BASE_BRANCH..HEAD")
```

```
✅ Rebased <branch> onto origin/<base>
   <BEHIND> base commit(s) integrated · <REPLAYED> of your commit(s) replayed
   <one of:>
   ✓ Republished to origin/<branch> (--force-with-lease)
   • Local only — branch isn't published, nothing to push
```

When `SQUASH=yes`, phrase the second line as `<SQUASHED_COUNT> commit(s) squashed into 1`. When `AUTOSTASH_CONFLICT=yes`, add:

```
   ⚠ Autostash re-apply conflicted — your stashed changes left conflict markers in the working tree
     (commit history is correct; the stash entry is preserved).
```

If integrating the base pulled in a dependency change, say so. Diff against `PRE_REBASE_SHA` — **not** `ORIG_HEAD`, which both the squash and the rebase overwrite:

```bash
git diff --name-only "$PRE_REBASE_SHA" HEAD
```

If it lists `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, or a `*.gradle.kts`, note that the next build will re-sync Gradle and may be slow.

---

## Safety rules

- **Rebase onto `origin/<base>`, never a stale local base.** Step 1's fetch refreshes it; the rebase and the behind-count both read it.
- **One force-push, and only the safe kind.** Step 6's `--force-with-lease` pinned to the pre-rebase remote SHA. If the lease is rejected, stop — never escalate to `--force`.
- **Never touch uncommitted work silently.** A dirty tree pauses (or stops, in auto mode); there is no hidden `git stash`. Autostash re-apply conflicts are surfaced, never swallowed.
- **Never auto-resolve conflicts.** The default is abort-to-pristine. Assisted resolution is fully user-driven — never `git rebase --skip`, never `--continue` over unresolved markers.
- **Never drop remote-only commits.** Step 3's guard stops first, in both modes.
- **Squashing is opt-in and collapses only the branch's own commits** — never base commits — before the rebase.
- **Auto mode stops rather than guesses.** Non-trunk PR base, dirty tree, remote ahead, or a conflict all end the run with a reason; it never stashes, squashes unasked, or resolves conflicts.
- This skill doesn't open, merge or close PRs, and doesn't delete branches.

$ARGUMENTS
