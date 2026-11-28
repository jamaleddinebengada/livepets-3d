# Missing assets — Husky (reference animal), v0.10.0

Added in v0.10.0 from your GIFs:
- `walk_front_right` ×2 and `walk_back_right` ×2, mirrored for the left diagonals;
- `turn` (right → front → left; mirrored for left → right).

All 8 walking directions now have real artwork.

Each item below is **not implemented**, because there is no artwork or recording for it. The engine does not fake these with stretched images or beeps.

To add one, drop the file in place. If it is a new clip, add its line to `manifest.json`.

## Format for every animation clip

- Transparent background (PNG/GIF/WebP source). The same husky, the same art style and the same size as the existing clips.
- Frame size 360×340. The paws touch the ground at y = 332 in every frame. This keeps the ground line stable.
- Side views are drawn **facing right**. The engine mirrors them for left, except `walk_left`, which already exists.
- Loops must loop seamlessly: the last frame flows into the first.
- 10–16 frames at 80–100 ms per frame.
- Send them as GIF/WebP/video like before. I convert them to sheets.

| File to add (assets/sprites/husky/) | What it must show | Used for |
|---|---|---|
| `walk_slow` | Slow, relaxed side walk | Calm wandering |
| `sprint` | Full-speed side gallop | Zoomies |
| `sit` | Sitting down, then a sitting idle loop | Resting, waiting |
| `lie_down` | Lying down on the belly, awake | Resting |
| `wakeup` | Waking up from the bed sleep: lifts head, opens eyes | After sleeping (now a slow cross-fade) |
| `stretch` | Front-legs-down stretch | Waking up, idle |
| `scratch` | Scratching behind the ear with a back paw | Idle behaviour |
| `groom` | Licking a paw | Idle behaviour |
| `shake` | Whole-body shake | After playing or landing |
| `bark` | Side view barking (mouth open, short) | Tap reaction, alerts |
| `curious` | Head tilt / sniffing the ground | Exploring |
| `excited` | Bouncing on the front paws, tail wagging fast | Before playing |
| `roll` | Rolling over onto the back | Happy behaviour |
| `tail_wag` | Standing, tail wagging, looking at the viewer | Petting, greeting |
| `touch_reaction` | Looks toward the viewer, ears move, happy face | Tap and long press (now uses `happy`) |

**Head turning toward the touch point** needs either a rigged GLB model or a set of head-angle frames. A flat animation cannot rotate its head on its own.

## Sounds (assets/sounds/husky/) — real recordings with a commercial licence

Each file is played only if it is listed in `assets/sounds/manifest.json` with a licence, source and author.

Format: OGG or WAV, mono, 44.1 kHz. Trim the silence and keep peaks around −3 dB. Ideally 3+ variations of each.

| File | Sound |
|---|---|
| `howl_1..3.ogg` | Husky howl (2–4 s) |
| `bark_1..3.ogg` | Short husky/puppy bark |
| `whine_1..3.ogg` | Puppy whine (asking for food, sad) |
| `content_1..2.ogg` | Happy grumble / soft "woo" when petted |
| `pant_1.ogg` | Panting after running (loopable) |
| `sleep_1.ogg` | Soft sleeping breath / snore |
| `step_1..4.ogg` | Soft paw steps on a floor |
| `land_1.ogg` | Soft landing thump |
| `drink_1.ogg`, `eat_1.ogg` | Lapping water, crunching kibble |

Until these arrive, the animals make **no vocal sounds**. The old built-in voices were synthesized, so they are no longer used as animal voices. Eating, drinking and landing use the existing neutral effects.

## Other animals

The kitten, puppy, penguin, fox, British cat, red panda and lion still use the previous renderer. Each needs the same clip set as the husky, plus a `manifest.json`, before it moves to the new engine.

Species-specific clips the brain is ready to use:

| Animal | Clips |
|---|---|
| Kitten | `groom`, `stretch`, `purr` |
| Penguin | `slide`, `flap` |
| Panda | `roll`, `eat` |
| Fox | `curious` |
| Lion | `stretch`, `roar` |
| Elephant | `ears`, `trunk` |
