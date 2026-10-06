---
name: add-promo
description: Use this skill when adding a promotional video of the venue's own, not tied to any advertiser. Triggers on "add promo", "upload promo", "new promo", "promo video", "promotional video".
version: 2.0.0
---

# Add a promo video

A promo is the venue's own content, so the visitor is not sent onward with nothing played. Created
with `create_promo_video`, which registers the row and hands back a URL to upload the file to —
exactly as `create_advertiser_video` does.

**The upload half is identical.** Presigned PUT, `ui://upload-video/<videoId>`, the page reads the
duration from the file and calls `confirm_video_upload`, `list_pending_uploads` and
`resume_video_upload` to finish one later. Read `skill://add-video/SKILL.md` for it rather than
repeating it here. And as there: never ask the operator how long the video is.

## `create_promo_video`

| Field | |
|---|---|
| `slug` | which location shows it. A promo belongs to one location, like any other video |
| `title` | one `{locale, value}` entry per locale |
| `questions` | **optional**, unlike an advertiser's video |
| `description` | optional blurb under the title |
| `minWatchSeconds` | how long before the guest may move on. Omit for the whole video |
| `priority` | a position, not a weight. Defaults to 10 |
| `noRepeatSeconds` | seconds before the same guest may see it again. Omit for no limit |

## Three differences from an advertiser's video

**No advertiser.** The ad query joins `advertisers` and filters on active ones; the promo query does
not touch that table, so `set_advertiser_active` cannot take a promo out of rotation. Nor can
anything else — there is no tool to retire a video. An active promo stays in the sequence; the only
thing you can still change is which questions follow it, with `set_question_active`.

**Its survey is optional.** Not because a promo cannot have one — it can, and that survey is served
and answered by exactly the same path. What differs is the *selection*: the advertiser query
requires through an `EXISTS` that a question remain unanswered, so an advertiser video with nothing
left to ask is never served; a promo plays either way. That is why questions are required there and
optional here, and a promo that only plays is a valid thing to want. An earlier version of this file
claimed promos have no surveys, and that belief was built into the read path, the tools and a test
before anyone checked it.

Pass `questions` to create the survey with the video — the response carries `surveyId`, absent when
you passed none. To add to it afterwards, or to give a promo its first survey, call `create_survey`
with the promo's `videoId` and then `create_question`. See `skill://add-survey/SKILL.md`.

**Not drawn at random.** Promos are served in ascending `(priority, createdAt)`, with the session's
last promo as the cursor, so `priority` is a place in a sequence rather than a weight in a lottery.
`noRepeatSeconds` only means anything here, because only promos rotate.

## Related

- `skill://add-video/SKILL.md` — advertiser videos, and the upload flow in full
- `skill://add-survey/SKILL.md` — question shapes; there is no `Rating`
