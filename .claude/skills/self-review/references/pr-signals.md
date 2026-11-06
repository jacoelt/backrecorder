# PR external signals — CI + CodeRabbit

Loaded by `SKILL.md` **only when `HAS_PR=true`** — Step 2b (poll CI, and CodeRabbit when it's active) and Step 5 (resolve addressed CodeRabbit threads). Runtime values from the orchestrator: `$PR_NUMBER`, `$REPO_FULL` (`jacoelt/backrecorder`), `$HAS_CODERABBIT`, `$IS_DRAFT`, `$TOTAL_ROUNDS`.

**Timeout constants** (use the same values everywhere):

| Signal     | Poll interval | Timeout | Why                                                                                      |
| ---------- | ------------- | ------- | ---------------------------------------------------------------------------------------- |
| CI         | 15 s          | 15 min  | `ci.yml` runs unit tests **and** a minified release build on a cold-ish Gradle cache; 5–10 min is normal, so don't call it stale early. |
| CodeRabbit | 20 s          | 5 min   | Only runs when the bot is installed on the repo; typical full review is 1–3 min.         |

**Launching the polls.** Once Step 2.0's push has succeeded (or was skipped because nothing needed pushing), launch the CI poll — and the CodeRabbit poll when `HAS_CODERABBIT=true` — as `run_in_background: true` commands in the same message as the Step 2a.iv agent spawn. Wait for the notifications before aggregating. Never block the foreground on either.

## CI status (timeout: 15 min)

1. Poll the check rollup for the PR's HEAD every **15 s for up to 15 min** (60 iterations max), in a background command:

   ```bash
   gh pr checks "$PR_NUMBER" --json name,state,bucket,link
   ```

   `gh pr checks` collapses each check into a `bucket` — one of `pass` / `fail` / `pending` / `skipping` / `cancel`. There is **no** `conclusion` field. A check is "done" when its bucket is anything other than `pending`; the rollup is conclusive once no check is `pending`. Exit the loop early when it is.

2. Partition the rollup:
   - **Failing** = `bucket in {fail, cancel}`
   - **Passing** = `bucket == pass`
   - **Skipped** = `bucket == skipping`
   - **Stale** = `bucket == pending` when the timeout fires

3. For each **failing** check, pull the error context. The `link` field points at the job; extract the run id and read the failing log:

   ```bash
   RUN_ID=$(echo "$CHECK_LINK" | grep -oE 'runs/[0-9]+' | grep -oE '[0-9]+')
   gh run view "$RUN_ID" --log-failed 2>&1 | tail -80
   ```

   On this repo the failures worth recognising on sight:

   | Log signature | What it means | Fix direction |
   | ------------- | ------------- | ------------- |
   | `gradlew is committed as mode 100644` | The executable bit was lost (Git on Windows drops it whenever `core.fileMode` is unset) | `git update-index --chmod=+x gradlew` and commit |
   | `Execution failed for task ':app:testDebugUnitTest'` | A unit test failed — the report artifact is uploaded on failure | Read the failing test from the log, fix the code or the test |
   | `Execution failed for task ':app:minifyReleaseWithR8'` / `Missing class` | R8 stripped or couldn't resolve something the Drive client or Hilt resolves reflectively | Add the keep rule to `app/proguard-rules.pro` |
   | `Execution failed for task ':app:kspDebugKotlin'` | A Hilt/KSP graph error — usually a missing binding or a scope mismatch | Fix the module or the injection site |
   | `VERSION_NAME ... semantic versioning` | `version.properties` holds a non-semver name; the build fails at configuration time | Correct `VERSION_NAME` |

   Convert each failing check into the reviewer-agent issue shape — `[major] <check-name> — Problem / Fix`:
   - **Problem**: 1–2 sentences distilled from the log, naming the task and the first real error line.
   - **Fix**: imperative, derived from the error.
   - **Severity**: `major` for anything that blocks the merge; `minor` only for a demonstrably non-blocking check.

4. **Stale** checks produce one aggregated `[minor]` entry: `"CI did not converge within 15 minutes — N check(s) still pending"`, naming them.

5. **Passing / skipped** checks produce no issues.

**Empty rollup.** If the rollup comes back with **zero** checks, don't treat it as green — the workflow may not exist on the base branch yet (CI was added late to this repo), the PR may have touched no triggering paths, or the run may not have been created. Warn once:

```
⚠ No CI checks reported on PR #<PR_NUMBER>. Nothing is verifying this branch in the pipeline —
  check that .github/workflows/ci.yml exists on <base_branch> and that the run was created.
```

Treat it as a `[minor]` finding, not a pass, and say so in the final report. If the rollup endpoint itself errors (auth, network), surface a one-line note and continue — best-effort, never block the loop.

## CodeRabbit review (only when `HAS_CODERABBIT=true`; timeout: 5 min)

Skip this entire section when `HAS_CODERABBIT=false` — triggering `@coderabbitai review` on a repo without the bot posts a stray public comment on a public repo and then burns the full 5-minute poll for a reply that never comes.

**Trigger only on a HEAD that hasn't been reviewed yet.** Keep the triggered SHA as `LAST_CR_SHA` next to the Step 2.0 / Step 5 push. Before triggering, re-read HEAD and compare — don't rely on recalled state:

```bash
HEAD_SHA=$(git rev-parse HEAD)
[ "$HEAD_SHA" = "$LAST_CR_SHA" ] && echo "HEAD unchanged since last CodeRabbit trigger — reuse prior findings, skip"
```

If they match, reuse the prior round's CodeRabbit findings and skip. Otherwise set `LAST_CR_SHA="$HEAD_SHA"` and continue.

1. Snapshot the interaction counts (baseline for detecting a fresh review **or** a fresh skip):

   ```bash
   BASE_REVIEWS=$(gh api repos/$REPO_FULL/pulls/$PR_NUMBER/reviews --jq '[.[] | select(.user.login | test("coderabbit"; "i"))] | length')
   BASE_REVIEW_COMMENTS=$(gh api repos/$REPO_FULL/pulls/$PR_NUMBER/comments --jq '[.[] | select(.user.login | test("coderabbit"; "i"))] | length')
   # Every *completed* run (clean or not) leaves an issue comment carrying `summarize by coderabbit.ai`;
   # a *skip* carries both that marker and `skip review by coderabbit.ai`. A clean review leaves no formal
   # review and no inline comments, so the summary marker — not the counts — is the reliable "finished" signal.
   BASE_SUMMARIES=$(gh api repos/$REPO_FULL/issues/$PR_NUMBER/comments --jq '[.[] | select(.user.login|test("coderabbit";"i")) | select(.body|test("summarize by coderabbit.ai"))] | length')
   BASE_SKIPS=$(gh api repos/$REPO_FULL/issues/$PR_NUMBER/comments --jq '[.[] | select(.user.login|test("coderabbit";"i")) | select(.body|test("skip review by coderabbit.ai"))] | length')
   ```

2. Trigger: `gh pr comment "$PR_NUMBER" --body "@coderabbitai review"`

3. Poll every **20 s for up to 5 min**, re-reading the same four counts each iteration. Exit on the first match, **skip tested first** (a skip increments both skips and summaries):
   - **skipped** — `SKIPS_NOW > BASE_SKIPS` → step 3a.
   - **reviewed** — `SUMMARIES_NOW > BASE_SUMMARIES || REVIEWS_NOW > BASE_REVIEWS || RCOMMENTS_NOW > BASE_REVIEW_COMMENTS` → step 4.

   **Edit-in-place caveat:** on a second or later review of the same PR, CodeRabbit usually edits its walkthrough comment in place, so the summary count doesn't grow. A re-review *with* findings still trips the review/inline counts; only a clean re-review reaches the timeout. Acceptable — the timeout is best-effort.

3a. **CodeRabbit skipped.** Surface it and continue the round without its findings. Do **not** mark the PR ready to force a review:

   ```
   ⚠ CodeRabbit skipped PR #<PR_NUMBER> — "<quoted skip reason>". Continuing without its findings this round.
   ```

   Treat it exactly like a timeout for the rest of the loop, and don't re-trigger on the same HEAD.

4. On the reviewed path, fetch the review body and the inline comments:

   ```bash
   gh api repos/$REPO_FULL/pulls/$PR_NUMBER/reviews --jq '.[] | select(.user.login | test("coderabbit"; "i"))' | tail -n 1
   gh api repos/$REPO_FULL/pulls/$PR_NUMBER/comments --jq '.[] | select(.user.login | test("coderabbit"; "i"))'
   ```

5. Convert each actionable item into the reviewer-agent issue shape — `[<severity>] <path:line> — Problem / Fix`. Map CodeRabbit's severities (🔴 Critical / 🟠 Major / 🟡 Minor / 🧹 Nitpick) to `critical | major | minor | minor`. **Keep each comment's REST `id`** — thread resolution below needs it.

On timeout, note `CodeRabbit timed out for round <TOTAL_ROUNDS> — proceeding without its findings` and continue.

## Resolve addressed CodeRabbit threads (Step 5)

For every fix applied this round that came from CodeRabbit, resolve its review thread — **only** those actually addressed. Map the REST comment id to the GraphQL thread node id, then resolve:

```bash
gh api graphql -f query='
  query($owner:String!, $repo:String!, $pr:Int!) {
    repository(owner:$owner, name:$repo) {
      pullRequest(number:$pr) {
        reviewThreads(first:100) {
          pageInfo { endCursor hasNextPage }
          nodes { id isResolved comments(first:1) { nodes { databaseId } } }
        }
      }
    }
  }' -F owner="${REPO_FULL%/*}" -F repo="${REPO_FULL#*/}" -F pr="$PR_NUMBER" \
  --jq '.data.repository.pullRequest.reviewThreads.nodes[]
        | {threadId: .id, commentId: .comments.nodes[0].databaseId, resolved: .isResolved}'

gh api graphql -f query='
  mutation($threadId:ID!) {
    resolveReviewThread(input:{threadId:$threadId}) { thread { id isResolved } }
  }' -F threadId="<thread node id>"
```

Threads tied to findings the triage agent skipped stay **open**, with a one-line reply saying why they were skipped, so the trail survives. A resolve failure logs a warning and never aborts the loop.
