# ProCam

A camera app that finishes pictures the way flagship phones do, with an
**iPhone Style**, a **Galaxy Style** and a **Natural** style, plus pro controls,
multi-frame HDR and Night mode, portrait blur, cinematic video, slow motion and
time-lapse. Plain Android, no external libraries. Works on Android 5.0 and newer.

## What is real and what is not

A phone's sensor and lens decide most of picture quality, and no app can change
that. What an app *can* do - and what the flagship phones do on top of their
hardware - is software: merge several frames, lift shadows, protect highlights,
sharpen, remove noise, grade colour. ProCam does those things for real, so your
pictures come out noticeably better than the stock camera on an old phone, but a
Vivo Y91i will not become an iPhone 16 Pro. Features that need hardware your
phone does not have are hidden or switched off automatically (Settings > This
phone shows what yours supports).

## Photo modes

| Mode | What it does |
|---|---|
| **Photo** | Full finishing pass: local tone mapping (opens shadows, recovers bright areas), clarity, sharpening, noise reduction, colour style + look. HDR (3-shot exposure blend) and Burst (8 shots) switches in the top bar. |
| **Portrait** | Finds faces, keeps the subject sharp, blurs the background. Effects: Natural, Studio, Stage, Stage Mono, High-key Mono. Blur strength in Settings. This is *simulated* depth (face-based), not a depth sensor, so hair edges are approximate. |
| **Night** | Takes 6 longer exposures, aligns them (hand shake), averages them (less noise), then brightens with shadow lift and denoise. Hold still for about 2 s. |
| **Pro** | Manual ISO, shutter speed, focus distance, white balance, EV, and RAW (DNG) saving - each shown only if your camera supports it. |

Extras: tap to focus, long-press to lock AE/AF, drag up/down for exposure, pinch
zoom, lens/zoom chips (0.5x / 1x / 2x ... plus separate lenses when the phone
exposes them), flash (off/auto/on/torch), self-timer, aspect ratios 4:3 / 16:9 / 1:1,
live histogram, level line, grid (3x3 / golden / diagonals), front camera with
skin smoothing and mirror options, volume-key shutter, GPS tag, watermark,
EXIF (ISO, shutter, aperture, focal length), scene hint when it is too dark.

**Looks** (live in the viewfinder and applied to the saved picture):
iPhone Style: Standard, Vivid, Vivid Warm/Cool, Dramatic, Dramatic Warm/Cool, Mono, Silvertone, Noir, Document.
Galaxy Style: Original, Vivid, Soft, Warm, Cool, Cinematic, Fade, Retro, B&W, Noir, Document.

## Video modes

| Mode | What it does |
|---|---|
| **Video** | Up to 4K (if the phone can), 24/30/60 fps, H.264 or HEVC, stabilisation, stereo AAC audio, pause/resume. |
| **Cinematic** | 24 fps with a film tone curve (lifted blacks, soft S-curve, gentle highlight roll-off) applied inside the camera's own image pipeline, so it is baked into the video with no quality loss. A 2.39:1 frame guide is drawn on the viewfinder. Choose "Flat (log-like)" in Settings for footage to colour-grade later. |
| **Slow-mo** | Uses the phone's 120/240 fps high-speed mode and stretches the timestamps (no re-encoding) so it plays in slow motion everywhere. Only on phones whose camera offers it. |
| **Time-lapse** | One frame every 2 / 5 / 10 / 30 seconds. |

Not included (they need depth sensors, special hardware or a very large amount of
extra engineering): real depth-map portrait, Live Photos, Single Take, panorama,
photo-while-recording, 8K, ProRes, Dolby Vision. Cinematic video does not have
the iPhone's fake focus blur, because that needs a depth map per frame.

## Speed on old phones

Settings > Photo processing: **Full** (best), **Fast** (lighter, for weak
phones) or **Off** (straight from the sensor). Settings > Photo size lets you cap
megapixels. If a picture is too big for memory the app saves it unprocessed
instead of crashing.

## Build the APK

1. Unzip `ProCam.zip`.
2. Create a new GitHub repository and upload everything inside the `ProCam`
   folder to the repository root. Check that `.github/workflows/build.yml` is
   there (phone uploads sometimes skip hidden folders).
3. Actions tab > let it run, or **Run workflow**.
4. Download the **ProCam-app** artifact, unzip, install `app-debug.apk`.
5. First start: allow Camera, Microphone and Storage. Pictures and videos are
   saved in `DCIM/ProCam`.

Build setup is the same as your working System Tuner / Safe Charge projects:
Android Gradle Plugin 8.5.0, Gradle 8.7, JDK 17, compileSdk 34, no dependencies.
