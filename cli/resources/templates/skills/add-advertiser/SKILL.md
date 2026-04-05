---
name: add-advertiser
description: Use this skill when adding advertisers. Triggers on "add advertiser", "create advertiser", "new advertiser", "advertiser yaml".
version: 1.0.0
---

# Add an advertiser

## Overview
Advertisers are shared across all locations. Each advertiser is a YAML file in `shared/advertisers/`.

## Steps

1. Create a YAML file named with the advertiser slug (kebab-case):
   ```
   shared/advertisers/<slug>.yaml
   ```

2. Define the advertiser:
   ```yaml
   name: "Advertiser Name"
   priority: 10              # higher = shown first
   ```

3. Deploy: `captal shared push`

## Notes
- The filename (without .yaml) becomes the advertiser slug used to reference it in video configs
- Priority determines the order advertisers' videos are shown (higher priority first)
- After adding an advertiser, you can add videos for it in each location
