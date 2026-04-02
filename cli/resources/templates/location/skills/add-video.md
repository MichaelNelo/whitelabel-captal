# Add an advertiser video

## Overview
Advertiser videos live in `videos/<video-slug>/` within a location directory. Each video has a `video.yaml` for metadata and an optional `surveys/` subdirectory.

## Prerequisites
- The advertiser must exist in `/etc/captal/shared/advertisers/<slug>.yaml`
- Location initialized with `captal init <slug>`

## Steps

### Option A: Using the CLI (uploads to S3 automatically)
```bash
captal video <location-slug> <advertiser-slug> /path/to/video.mp4
```
This creates the video directory with `video.yaml` and a survey template.

### Option B: Manually
1. Create the video directory:
   ```
   videos/<advertiser>-<name>/
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

4. Deploy: `captal push <location-slug>`

## Naming convention
Video directory name should be `<advertiser>-<descriptive-name>` in kebab-case.
