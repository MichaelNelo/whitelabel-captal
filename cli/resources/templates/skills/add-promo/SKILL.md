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
- Promo videos have no advertiser and no surveys
- They are typically shown with lower priority than advertiser videos
- The filename (without .yaml) becomes the promo slug
