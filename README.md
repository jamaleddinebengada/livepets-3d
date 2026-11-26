# LivePets 3D — *Your little friend lives on your screen.*

Native Android (Kotlin) virtual companion: real-time 3D animals that live over the home screen and other apps.

## What's in this beta (MVP)

- **Floating pet engine** — foreground service + `WindowManager` `TYPE_APPLICATION_OVERLAY` windows sized to the pet only, so the rest of the phone stays usable. Persistent notification (Pause / Feed / Stop), pauses when the screen is off, adapts to rotation, avoids the status & navigation bars.
- **Behaviour engine** — walks, runs, jumps, climbs screen edges, clings, sleeps, stretches, yawns, scratches, dances, rolls, chases a butterfly… with randomized timing driven by the pet's needs (energy / hunger / happiness).
- **Interactions** — tap (happy), double-tap (celebrate), drag & throw, long-press (petting + temporary Feed / Play / Sleep / Hide menu). Gravity mode, wall climbing, click-through mode, size & speed, battery saver, up to 3 pets on screen.
- **3D renderer** — OpenGL ES 3.0 on its own thread (transparent `TextureView`), procedural rig with smooth pose blending, **shell-texture fur** (instanced, tapered strands, root occlusion, rim light), procedural eyes with iris, pupil and catch-lights, dragon scales and wing membranes, contact shadow, 4× MSAA.
- **Animals** — Orange Kitten, Golden Puppy, Baby Panda, Fluffy Rabbit, Baby Fox, Baby Dragon. The other 18 from the master spec are declared in `PetSpecies` and shown as "coming soon".
- **App UI** — Home (stats, live 3D stage, Feed / Play / Sleep / Care), My Pets (collection, naming, gender, buddies, overlay controls), Shop (Premium, outfits/collars), Quests (daily reward + quests), Settings.
- **Monetization seams** — `monetization/Monetization.kt` (Billing + Ads interfaces with beta no-op providers). Ads are never shown in the overlay.

## Build

CI builds on every push to `main` (`.github/workflows/android.yml`) and publishes the APK to the `latest` release:
`https://github.com/<owner>/livepets-3d/releases/download/latest/LivePets3D.apk`

Locally: `gradle :app:assembleRelease` (AGP 8.5, Kotlin 1.9, JDK 17, compileSdk 34, minSdk 26).

> `app/livepets-beta.keystore` is a **test-only** signing key so beta builds update in place. Google Play releases must use a private upload key that is not committed.

## Code map

| Path | Role |
|---|---|
| `render/PetRenderer.kt` | rig, poses, animation blending, draw calls |
| `render/Shaders.kt` | fur / eye / scale shaders (GLSL ES 3.00) |
| `render/PetSurfaceView.kt` | EGL thread on a transparent TextureView |
| `overlay/OverlayService.kt` | foreground service, notification, screen state, rotation |
| `overlay/FloatingPet.kt` | per-pet window + behaviour engine + touch |
| `ui/*` | premium UI kit (glyph icons, glass, toggles, sliders) and pages |
| `data/*` | species catalogue, stats, settings, quests |
