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

> ⚠️ **Crítico — editar o borrar el survey.yaml por defecto antes de `locations push`.**
> El CLI siempre escribe `surveys/survey.yaml` con `text/options` igual a `"TODO"`. Si llega así a producción, los usuarios verán literalmente "TODO" como pregunta y "TODO"/"TODO" como opciones. Las dos formas de evitarlo:
>
> - **Editar** con el contenido real (preferido — las respuestas existentes se preservan porque los IDs de question/option son determinísticos por posición).
> - **Borrar** el archivo si no querés survey asociada a este video. Nota: hoy el `softDelete` de `video-survey:` no está implementado, así que si ya pusheaste una survey y después borrás el YAML, las preguntas viejas siguen activas en la DB. Para limpiar requiere SQL manual.

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
   # productCampaignId: "summer-2026" # optional admin identifier for the product/campaign
                                      # this video promotes (beyond the advertiser brand).
                                      # Persisted on the advertiser_videos row for reporting;
                                      # NOT exposed to the SPA.
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
