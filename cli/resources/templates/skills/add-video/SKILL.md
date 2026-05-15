---
name: add-video
description: Use this skill when adding advertiser videos to a location. Triggers on "add video", "upload video", "new video", "video yaml", "advertiser video".
version: 1.0.0
---

# Add an advertiser video

## Overview
Advertiser videos live in `locations/<slug>/videos/<video-slug>/`. Each video has a `video.yaml` for metadata and an optional `surveys/` subdirectory.

## Prerequisites
- The advertiser must exist in `shared/advertisers/<slug>.yaml`
- Location initialized with `captal locations add <slug>`

## Steps

### Option A: Using the CLI (uploads to S3 automatically)
```bash
captal video add <location-slug> <advertiser-slug> /path/to/video.mp4
```
This creates the video directory with `video.yaml` and a `surveys/survey.yaml` placeholder.

> ⚠️ **Critical — edit or delete the default survey.yaml before `locations push`.**
> The CLI always writes `surveys/survey.yaml` with `text/options` set to `"TODO"`. If it reaches production like that, guests see a literal "TODO" as the question and "TODO"/"TODO" as the options. The two ways to avoid it:
>
> - **Edit** it with the real content (preferred — existing answers are kept because question/option ids are deterministic by position).
> - **Delete** the file if this video should have no survey. Note: `softDelete` for `video-survey:` is not implemented, so if a survey was already pushed and the YAML is deleted afterwards, the old questions stay active in the DB. Cleaning them up takes manual SQL.

### Option B: Manually
1. Create the video directory:
   ```
   locations/<slug>/videos/<advertiser>-<name>/
   ├── video.yaml
   └── surveys/
       └── <survey-name>.yaml    # optional
   ```

2. Define `video.yaml`:
   ```yaml
   advertiser: "<advertiser-slug>"    # must match a shared advertiser
   url: "https://..."
   duration: 15                       # seconds
   minWatch: 5                        # minimum seconds to watch
   showCountdown: true
   priority: 10                       # higher = shown first
   title:
     es: "Titulo en español"
     en: "Title in English"
   # description:                     # optional
   #   es: "..."
   #   en: "..."
   ```

3. Optionally add surveys in `surveys/`:
   ```yaml
   name: "Survey name"
   questions:
     - type: radio
       points: 10
       required: true
       text:
         es: "Pregunta"
         en: "Question"
       options:
         - text: { es: "Opción 1", en: "Option 1" }
         - text: { es: "Opción 2", en: "Option 2" }
   ```

4. Deploy: `captal locations push <location-slug>`

## Naming convention
Video directory name should be `<advertiser>-<descriptive-name>` in kebab-case.
