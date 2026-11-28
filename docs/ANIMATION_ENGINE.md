# LivePets animation engine (v0.11.0)

## v0.11.0 — Advanced living pet behaviour

### Movement
- **Whole-screen floor.** Depth `z` now spans the whole visible screen (0 = bottom edge, 1 = top). The animal walks, runs and roams everywhere.
- **Perspective and speed.** Perspective is mild (−22 % at the top). Depth speed is converted so the on-screen speed stays stride-synced.
- **Hop.** A ballistic jump to another spot (left/right/up/down). It turns first with the turn clip, maps the air frames onto the flight time, and lands exactly on the spot. Jumps never go above the screen top.
- **Drop.** The animal lands on the floor a little below the point where it is released.
- **Climbing** starts only when the animal is already at the wall, facing it, with room above. There is no teleporting.

### Moods
CALM, HAPPY, CURIOUS, TIRED and PLAYFUL. They are driven by:
- needs;
- events (petted/fed → happy, played → playful);
- random spells.

Moods change the behaviour weights.

### Touch
- **Stroking.** Hold, then move the finger slowly over the pet: the petting reaction continues and hearts appear. The pet is not picked up.
- **Follow my finger.** This is a quick-menu button. A transparent full-screen layer exists only while it is in use: it disappears when the finger lifts, or after 8 s untouched. The pet walks or runs after the finger anywhere on the screen.
- **Touches during a jump** are no longer lost. The reaction plays right after landing.

### Sound settings
- **Animal voice volume** is separate from effects and music.
- **Quiet hours** can be edited (From / To).

### Performance
Engine pets redraw only when the frame, cross-fade, scale or rotation changes (clips are 10 fps). The overlay window is re-laid out only when the pet actually moves.

### Tests
New tests:
- 12 simulated minutes: screen coverage, never off-screen, no teleport, every clip change cross-faded, moods;
- hop landing on target;
- finger follow;
- stroking.

## v0.10.0

## v0.10.0

- **8 directions with real artwork.** The 3/4 front-right and back-right walks move along both axes at their own stride. They are mirrored for front-left and back-left.
- **Turn clip.** Direction changes play the turn clip at 1.5× (0.8 s), mirrored for left → right.
- **Roaming.** A new autonomous "roam" behaviour walks diagonally into the scene and back out.
- **Panting** is triggered after zoomies.
- **Footstep sync frames** are set for every walk and run clip.

The husky is the reference animal. It is the first animal on the new engine. The other animals stay on the previous renderer until the husky has been validated on a real device.

## Why the husky moved unnaturally (v0.8.x)

| Problem | Cause in the old code | Fix in v0.9.0 |
|---|---|---|
| Paws slid on the ground | `FloatingPet.walkTo` moved the window at a fixed 72 / 190 dp/s. The clip played at its own frame rate, so the two were never matched. | Every locomotion clip declares its ground speed (`groundSpeed`, in sprite px/s). Position and clip time advance from the same playback rate. The planted paw speed equals the body speed at every size, speed setting and acceleration. |
| Instant 180° flips | `ctl.facing = sign(target - x)` mirrored the sprite in one frame. | The animal brakes, turns through its front view (`walk_front`, 0.32 s), then accelerates the other way. All clip changes cross-fade over 160 ms. |
| "Walking away" only shrank in place | AWAY/TOWARD scaled the image without moving it. | Depth `z` is a real coordinate. Walking away moves the animal up the screen and shrinks it with perspective (−32 % at the back), using the back-view clips. Coming back uses the front-view clips. |
| Jump clip restarted at the apex | JUMP→FALL changed the clip serial, so the clip restarted. | The jump clip's `takeoff / air / land` frame ranges are mapped onto the real ballistic flight: crouch on the ground, air frames spread over the flight time, then landing frames on impact. |
| Every animal did the same things | One shared behaviour list. Missing clips fell back to `idle` (dance, scratch, wave, stretch… all looked identical). | `AnimalBrain` only chooses behaviours whose clips exist for that animal. Choices are weighted by species personality and needs, with cooldowns and a penalty for repeating the same behaviour. |

## Files

- `anim/engine/ClipLibrary.kt`: 8-direction model (`Dir`), clip metadata, manifest parser and direction resolution:
  - exact direction first;
  - a mirrored side view is allowed;
  - a diagonal uses its dominant view;
  - a front or back view is never used for a sideways walk.

  It also produces the missing-animation report.
- `anim/engine/AnimalEngine.kt`: locomotion (acceleration, braking, turning, depth), stride-synced playback, cross-fades, jump/fall/land physics, climbing, drag and release, touch reactions, and the follow-finger API. It is pure Kotlin, so it runs on a plain JVM.
- `anim/engine/AnimalBrain.kt`: needs (energy, happiness, hunger), species `Profile`s (husky, kitten, puppy, penguin, panda, fox, lion, elephant), weighted choice, cooldowns, obstacle-aware target picking and a comfortable rest spot.
- `render/AnimalAssetManager.kt`: loads `assets/sprites/<id>/manifest.json`. Sheets are decoded on a background thread into a 64 MB LRU cache.
- `render/SpritePet.kt`, `SpritePetView` engine mode: draws exactly the engine pose, cross-fades, mirrors, applies depth scale, wall rotation and the contact shadow.
- `audio/AnimalSoundManager.kt` and `assets/sounds/manifest.json`: plays licensed recordings only, with the licence manifest, variations, cooldowns, quiet hours, call detection and "don't talk over music".
- `overlay/FloatingPet.kt` and `ui/PetStage.kt` use the engine whenever an animal has a manifest. On the in-app stage, the animal walks or runs after your finger.

## Manifest

`assets/sprites/husky/manifest.json` maps each clip to:
- `action`: walk, run, jump, idle…
- `dir`: right / left / front / back / front_left …
- `groundSpeed` (side clips) or `depthSpeed` (front/back clips)
- `phases`, for example jump takeoff/air/land.

### Calibration

`groundSpeed` values were set by inspecting the paw contact frames of each sheet. They are estimates.

If a clip still slides on the phone, change only that number:
- higher = the body moves faster for the same steps;
- lower = slower.

### Adding another animal

1. Add its sheets and a `manifest.json`.
2. It switches to the engine automatically.

## Assets

Husky sheets were converted from PNG to WebP (q94, lossless alpha):
- size went from 22.9 MB to 4.8 MB;
- mean colour difference is 1.6/255 on opaque pixels.

## Tests

`app/src/test/.../AnimalEngineTest.kt` runs in CI (`testDebugUnitTest`) before every build. It checks:
- direction resolution;
- no sideways walk shows a front/back view;
- turning through the front view;
- no foot sliding (travel ÷ clip time = stride, within 4 %);
- depth movement;
- jump phase order;
- behaviour variety and needs;
- touch, drag, fall, landing, nap and wake.
