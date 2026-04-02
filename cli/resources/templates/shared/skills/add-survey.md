# Add an identification survey

## Overview
Identification surveys (email, profiling, location) are shared across all locations. They live in `/etc/captal/shared/surveys/`.

## Steps

1. Create a new YAML file in `surveys/`:
   ```
   surveys/<category>.yaml
   ```

2. Define the survey with category and questions:
   ```yaml
   category: <category>   # must be: email, profiling, or location
   questions:
     - type: input         # input, radio, checkbox, dropdown, rating, numeric, date
       points: 10
       required: true
       text:
         es: "Pregunta en español"
         en: "Question in English"
       # Optional fields:
       # placeholder: { es: "...", en: "..." }
       # hierarchyLevel: "state"    # for dropdown with hierarchy
       # options:                    # required for radio, checkbox, dropdown
       #   - text: { es: "...", en: "..." }
       # rules:                      # for input validation
       #   - type: email
       #   - type: max_length
       #     value: 100
   ```

3. Deploy: `captal shared push`

## Question types
- `input` — free text, supports rules (email, max_length, pattern)
- `radio` — single choice, requires 2+ options
- `checkbox` — multiple choice, requires 2+ options
- `dropdown` — single choice dropdown, requires 2+ options
- `rating` — numeric rating
- `numeric` — numeric input
- `date` — date picker
