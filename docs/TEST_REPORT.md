# Test report — LivePets 3D v0.9.0 (build 912)

## A. Automated tests (run on every CI build, `testDebugUnitTest`): 34/34 passed

They run the real engine code from the app on a JVM, using the real husky manifest and sheets.

### Directions
- Right uses a side view.
- Left uses the native left clip.
- Away uses the back view; toward uses the front view.
- The 4 diagonals fall back to a compatible view.
- Run-left mirrors the side run.
- Across a scripted route (walk R/L, depth, run, jump), no sideways step ever showed a front/back view or faced backwards: 0 violations.

### Turning
A change of direction brakes and turns through the front view.

### Foot sliding
Measured travel ÷ clip time equals the stride:

| Clip | Travel (px/s) | Stride (px/s) |
|---|---|---|
| walk3 | 135.0 | 135 |
| walk_left | 120.0 | 120 |
| run | 300.0 | 300 |

The other walk clips use the same mechanism.

### Depth
- away: depth 0.86
- toward: 0.34
- toward_run: 0.75

### Jump
- Frames play takeoff 0–3 → air 4–11 → land 12–15, in order.
- Air frames advance with the flight.
- The pet really leaves the ground.

### Brain
- 400 decisions: no behaviour above 35 %; 11 immediate repeats.
- Husky personality: zoomies, play and howl all appear.
- Tired husky: 62 % sleep or rest.
- Hungry husky asks for food.
- No behaviour is ever chosen without artwork.

### Touch
- Tap → happy + hearts.
- Drag holds the airborne pose.
- Release → falls with gravity and lands.
- Landing frames play after the fall.
- Nap → bed sleep.
- Tap wakes it with a cross-fade.

### Build checks
- Signed with the same certificate (SHA-256 394ae784…c286).
- versionCode 912 / versionName 0.9.0.
- 20 husky WebP sheets and the manifest are inside the APK and the AAB.

## B. Engine previews
The previews were rendered from the engine's frame-by-frame output (`trace` → frames drawn with the real sprite sheets). This is the engine logic, but the drawing step is a desktop re-implementation of the Android renderer, not a phone screen recording.

## C. Real-device tests: NOT done
No Android device or emulator is available in this environment. Nothing below has been confirmed on a phone yet:
- overlay behaviour;
- Samsung performance and frame rate;
- touch;
- sound focus;
- subscriptions.

Please check on the Samsung:
1. Walking left and right: do the paws slide? If yes, tell me which direction and whether it's too fast or too slow. Each clip has one number to adjust.
2. Turning around.
3. Walking away and back.
4. Jumping and landing.
5. Dragging and dropping.
6. Climbing.
7. Sleep in the bed.
8. Feed / play from the quick menu.
9. The in-app stage: drag your finger and the husky walks after it.
10. Purchases still unlocked.
