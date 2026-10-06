---
name: add-promo
description: Use this skill when adding promotional videos (not tied to advertisers). Triggers on "add promo", "upload promo", "new promo", "promo video", "promotional video".
version: 1.0.0
---

# Add a promo video

## Overview
Promo videos are promotional content not tied to any advertiser. They live in `locations/<slug>/promo/`.

## Steps

### Option A: Using the CLI
```bash
captal video add-promo <location-slug> /path/to/video.mp4
```

### Option B: Manually
1. Create a YAML file in `locations/<slug>/promo/`:
   ```
   locations/<slug>/promo/<video-slug>.yaml
   ```

2. Define the promo:
   ```yaml
   url: "https://..."
   duration: 10
   minWatch: 3
   showCountdown: false
   priority: 1                    # lower priority than ads
   title:
     es: "Titulo en español"
     en: "Title in English"
   # description:
   #   es: "..."
   #   en: "..."
   ```

3. Deploy: `captal locations push <location-slug>`

## Notes
- Promo videos have no advertiser: they belong to the venue itself.
- **A promo may have a survey.** This file said otherwise, and that was wrong — a promo's survey is
  served and answered by exactly the same path as an advertiser's. What differs is the *selection*:
  an advertiser's video is only drawn while its survey still has a question this guest has not
  answered, so one with nothing left to ask is never shown at all. A promo is shown either way.
  That is why `create_advertiser_video` requires questions and `create_promo_video` takes them
  optionally — pass them to create the survey with the video, or call `create_survey` with the
  promo's id afterwards.
- They are typically shown with lower priority than advertiser videos
- The filename (without .yaml) becomes the promo slug
