# Forest game artwork

Original AI-generated assets created on 2026-10-30 with the built-in image-generation tool. The supplied design screenshots were visual references for the warm, cute animal-world mood, not copied image layers. No Figma file was modified.

## Delivered files

| File | Dimensions | Alpha | Bytes |
|---|---:|---|---:|
| backdrop.webp | 1536 × 1024 | No | 161,124 |
| animal-rabbit.webp | 512 × 512 | Yes | 30,712 |
| animal-fox.webp | 512 × 512 | Yes | 44,718 |
| animal-panda.webp | 512 × 512 | Yes | 52,832 |
| animal-bear.webp | 512 × 512 | Yes | 45,674 |

Total image payload: 335,060 bytes, before HTTP overhead. The originals are retained separately; the source ZIP includes the optimized delivery assets. Portraits are mapped to seats by the UI, not by any user identity or trait.

## Art direction and use

Soft, rounded 2.5D forest scenery with warm wood, lanterns, a quiet clearing and peach dusk light. Four animals share mint neckerchiefs and front-facing expressions. Use `object-fit: contain` for the portraits to avoid clipping ears. Keep the playing surface and text on readable panels rather than directly on detailed scenery. Decorations do not communicate game rules or hide game state.

All visual creation and the background style revision used image generation. Delivery processing only resized the portraits to 512 pixels and encoded WebP (portrait quality 90, alpha quality 100; backdrop quality 86). No retouching, cutout painting, cropping, or changes to game dependencies were performed. Original generated alpha was retained through resizing and encoding.

The final portraits were decoded and visually checked composited over cream (`#fff8eb`) and mint (`#d5ece1`). No colored background bars or visible matte halo were found. All four image corners are fully transparent. Transparent-image viewers may display unused RGB values in transparent pixels incorrectly; the composited checks are the relevant visual evidence.

`asset-manifest.json` records final dimensions, hashes and source-image hashes. `generation-prompts.json` contains the exact prompt set. The first, more realistic background draft was discarded in favor of the softer toy-like final scenery.
