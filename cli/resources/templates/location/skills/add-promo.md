# Add a promo video

## Overview
Promo videos are promotional content not tied to any advertiser. They live in `promo/` within the location directory.

## Steps

### Option A: Using the CLI
```bash
captal video <location-slug> --promo /path/to/video.mp4
```

### Option B: Manually
1. Create a YAML file in `promo/`:
   ```
   promo/<video-slug>.yaml
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

3. Deploy: `captal push <location-slug>`

## Notes
- Promo videos have no advertiser and no surveys
- They are typically shown with lower priority than advertiser videos
- The filename (without .yaml) becomes the promo slug
