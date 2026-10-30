---
name: add-video
description: Use this skill when adding an advertiser's video to a location, finishing an upload that never completed, or replacing a video's file. Triggers on "add video", "upload video", "new video", "advertiser video", "pending upload", "resume upload", "replace video", "re-upload".
version: 2.0.0
---

# Add an advertiser video

Videos are created with tools, not files. One call registers the video *and* the survey shown after
it, and hands back a URL to upload the file to.

**Never ask the operator how long the video is.** The length is read from the file during the
upload. Asking for it is how a countdown ends up pointing at the wrong second.

## Order of operations

1. `list_locations` — the video belongs to one location.
2. `list_advertisers` — you need the advertiser's id, and a retired advertiser is refused: its
   videos would never be drawn.
3. `create_advertiser_video` — the whole registration, in one call.
4. The file. The tool returns `uploadPageUri` (`ui://upload-video/<videoId>`); on a host that
   renders MCP Apps the page opens itself and does the rest. See *The upload* below.

## `create_advertiser_video`

| Field | |
|---|---|
| `slug` | which location shows it. The survey inherits this |
| `advertiserId` | from `list_advertisers` |
| `title` | one `{locale, value}` entry per locale the location serves |
| `questions` | **at least one.** Asked after the video |
| `description` | optional blurb under the title |
| `minWatchSeconds` | when the survey unlocks. Omit for the whole video |
| `priority` | defaults to 10. Zero means never drawn |

There is no `url`, no `duration` and no `showCountdown`: the storage key is derived from the video's
own id so two uploads cannot overwrite each other, the length is measured at confirm, and the
countdown is always on.

**The questions are mandatory for a reason.** The rotation only serves an advertiser video whose
survey still has a question this guest has not answered — so a video with no questions left to ask
is never shown at all. The flow this replaced created the survey with the literal text `"TODO"` and
trusted the operator to fill it in before deploying; guests read "TODO" as the question. Requiring
the wording here makes that state unreachable.

A question is `{text, answer}` plus optional `required` (default true) and `points` (default 10).
`answer.kind` is one of `Radio`, `Dropdown`, `Checkbox`, `Input`, `Numeric`, `Date`, and the variant
decides which other fields it has. **There is no `Rating`** — the portal renders that case as the
literal string `Rating input - TODO`. Ask for a one-to-five scale as a `Radio` with five options.
Read `skill://add-survey/SKILL.md` for the shapes and for the locale rule nothing audits.

## The upload

`create_advertiser_video` writes the row with `status = pending` and returns a presigned S3 PUT URL
valid for 30 minutes, plus `uploadPageUri`. A pending video is invisible to guests, so an abandoned
upload costs nothing — it is a video waiting to be finished, not a mess to clean up.

The page does the work: the operator picks a file, it PUTs straight to S3 (the bytes never pass
through this server — a video cannot travel over JSON-RPC), reads the duration out of the file, and
calls `confirm_video_upload` itself. That call checks the object is really there, records the length
and flips the status to `active`.

**You only call `confirm_video_upload` yourself in one case:** the page could not read a duration
from the file — some WebM headers carry none — and says so. Then ask the operator for the number of
seconds and pass it as `durationSeconds`. That is the only time the duration is a question for a
human.

If `confirm_video_upload` reports nothing was uploaded, the signature lapsed before the PUT
finished. Call `resume_video_upload`, not `create_advertiser_video` — creating again would make a
second video, a second survey and a second set of questions.

## Finishing an upload later

- `list_pending_uploads` — every video still waiting for its file, oldest first. Omit `slug`: the
  forgotten upload is the one at the location nobody is looking at.
- `resume_video_upload` — signs a fresh URL and opens the page for the file that video is waiting
  for. Creates nothing. Also reopens a replacement that was never confirmed.
- `get_location_readiness` also counts them, per location.

## Replacing a file

`replace_video_file` — a lighter encoding, a corrected cut. It opens the same page for a new file of
an **active or retired** video and keeps its id, so the survey, its answers and every guest's view
history stay. Nothing changes for guests until the page confirms the new file: the video keeps its
status and keeps playing the current one. Confirming makes the new file current, and the response
says `replaced: true`.

Guests already playing the old file finish it. Its object is deleted once the last of them is done;
`filesDeleted` in the response counts the ones that went at once.

If the operator gives up halfway, nothing has changed. `list_videos` shows `replacing: true` until a
new file is confirmed; calling `replace_video_file` or `resume_video_upload` again reopens the same
page. A pending video, still waiting for its first file, is `resume_video_upload`'s.

The file is what every guest downloads over the venue's WiFi before they get online. Hundreds of MB
is a reason to replace it: a 720p H.264 encode of a short spot is a few tens of MB at most.

## `minWatchSeconds`

Omitted means the whole video. A value longer than the file is **lowered to the file's length** at
confirm rather than refused, and the response says so in `minWatchAdjustedFrom` — by then the file
is already in S3, and a survey that unlocks after the video ends never unlocks.

## Priority is a weight, not an order

The rotation draws at random, weighted twice: the advertiser's priority against the sum over active
advertisers, then this video's priority against the sum over that advertiser's active videos. So
priority 20 against a 10 means roughly twice as often, not first. Zero means never.

`list_videos` shows what a location actually plays, with each video's status, length and weight.

## What no tool does

There is no tool to retire a video, and none to change its title, `minWatchSeconds` or `priority`
after creation. `set_advertiser_active` is the only lever, and it takes **every** one of that
advertiser's videos out of rotation at once.

## Related

- `skill://add-promo/SKILL.md` — the venue's own videos, which have no advertiser
- `skill://add-advertiser/SKILL.md` — the advertiser has to exist and be active first
- `skill://add-survey/SKILL.md` — question shapes, and adding to a video's survey afterwards
