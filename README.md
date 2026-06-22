# GlyphBeat

turn the glyph leds on the back of ur nothing phone into an actual music visualizer that reacts to the BEAT. not "audio loud so light bright", nah, it does genuine dsp, ffts and onset detection and the whole nerd pipeline, just so the little lights on the back of ur phone can go absolutely feral to ur music. yeah.

> built and tested on the nothing phone (3a) pro (model index 4). the other nothing models have led mappings in here too but theyre lowkey untested so if ur on a different one and it lights up the wrong zone, thats why, sorry in advance lol.

## what it actually does (the real pitch)

ok so. most "glyph visualizer" apps out there just read the volume and blink. loud = bright. its fine i guess but its not... reacting to anything, its just following the loudness envelope. boring.

this one actually listens. like properly. it takes the audio, windows it, runs a real fft, then does spectral flux onset detection which is a fancy way of saying it watches the spectrum change frame to frame and figures out the exact moment a drum hits, not just when its loud. so a quiet snare in a quiet part still triggers, and a wall of loud sustained noise doesnt just hold every light on forever. it knows the difference between "loud" and "a thing just happened".

theres also a vocal notch in there (300 to 800 hz gets suppressed) so it biases toward drums instead of going crazy on someones voice, adaptive median thresholds so it adjusts to the track, peak picking so dense drum rolls dont just saturate everything, and ioi based bpm estimation cuz why not. honestly its way more dsp than a phone light app has any business having and thats the point.

## the BEAT mode (aka overdrive)

theres one mode and its called BEAT. the personality is still pure overdrive, no smooth fade outs, no gentle decay, just HARD on and off at full brightness, violent, like the lights are getting punched. but now its beat LOCKED, it doesnt chase loudness anymore, it reacts to actual drum HITS.

it splits the sound into 3 bands and each one owns a glyph zone:

- bass / kick goes to the slash
- mid / snare goes to the dot
- treble / hi hat goes to the ring

and the key thing, its exclusive, exactly ONE zone is lit at a time. every detected drum hit gets classified (kick / snare / hat) and flashes its zone, and the moment the next hit lands the light SHIFTS over to that zone. so the single bar just hops around the back of ur phone in time with the drums, kick→slash, snare→dot, hat→ring. if two hits somehow land in the same frame the louder one wins, and hits get latched across frames so a fast one that lands between renders doesnt get dropped.

each hit pops its zone for a short flash (~45ms) then it goes DARK until the next hit. so on a sparse beat you get a clean pop then black, and on a busy one the light just keeps hopping zone to zone. it reacts every single dsp frame with basically zero latency, but only actually pushes a new frame to the glyph when the lit zone changes, so an idle glyph just... stops sending. efficient lil guy.

## the AI part (yes theres an actual neural net now)

ok so heres the new toy. figuring out WHEN a drum hits was always solid (thats the spectral flux onset detection, its good, i left it alone). the weak bit was figuring out WHAT it was, kick vs snare vs hat, that used to be a dumb "compare some band energies and guess" heuristic. now theres a tiny neural net that does it instead. it looks at the *shape* of the spectrum the instant a hit lands and goes "thats a kick" / "thats a snare" / "thats a hat", which is basically what ur ear does.

its a real trained model (a `29→96→48→3` MLP, two hidden layers) but its tiny and runs in pure java in like **~7 microseconds per hit** (measured, and theres a test that fails if it ever regresses), **fully on the phone, no internet, no tensorflow, no downloading some 200mb model**. and it only runs the instant a hit lands, not every frame, so it adds basically zero latency to the lights. fits the whole offline-screen-off vibe of the app perfectly. theres a toggle in the app, **AI MODEL** vs **HEURISTIC**, flip it live and feel the difference, and when AI mode is on it shows u the model's confidence on the last hit.

it reads 29 numbers off the onset frame, 24 loudness-normalized log-frequency bands plus 5 spectral descriptors (centroid, spread, 85% rolloff, flatness, low/high ratio), basically a fingerprint of the spectrum shape, and the net says kick/snare/hat from that.

how it got trained: it learns from **real drum recordings**. the [IDMT-SMT-Drums](https://zenodo.org/records/7544164) dataset is 95 real drum loops (44.1khz, same as the engine) where every kick/snare/hat onset is hand-annotated, AND it ships isolated per-instrument stems. so `tool/prepare_real_dataset.py` windows the *isolated* stem at each annotated onset to get perfectly clean, unambiguously labeled examples (plus realistic in-mix windows), snapping each window to the actual transient peak and gating out silent/bleed junk. then `tool/train_real.py` trains the net on a **GPU via pytorch** (trained this on an rtx 3060), grounded with a dose of the old synthetic augmentation mixed into the train set so it stays robust to phone-mic nasties the clean studio dataset doesnt have. it exports the exact same java weight file + golden parity tests, so nothing about the on-phone side changes.

> real talk on accuracy: **~97% on a held-out test set of drum loops the model has never seen** (the split is by whole loop, so no augmented copy of a hit ever leaks from train into test, this is the honest generalization number). and the thing it used to suck at, telling a snare from a hi-hat, is basically fixed now (snare F1 ~0.97), because real snares and real hats actually look different in a way my synthetic ones didnt fully capture. theres still a pure-synthetic trainer (`tool/train_drum_classifier.py`) if u dont want to grab the dataset, but the real-data one is the good one.

> the timing and threshold numbers in here are tuned by pure feel over way too many hours. theyre kinda sacred. if u start nudging thresholds randomly it stops feeling good real fast, u been warned.

## mic vs system audio

theres a toggle in the app, MIC or SYSTEM. no auto switching, u pick.

- **MIC** listens thru the microphone. works for literally anything playing out loud in the room, a speaker, ur laptop, someone elses phone, whatever.
- **SYSTEM** taps the device audio directly using androids `audiofx.Visualizer` on the global output session (session 0).

and heres the genuinely cool part. SYSTEM mode still sees apps like **spotify even when they block normal audio capture**. spotify sets `ALLOW_CAPTURE_BY_NONE` so the normal mediaprojection route just hands u silence, but the legacy global visualizer doesnt care, it still gets the waveform. verified on the actual 3a pro. no screen record popup, no projection grant, it just works. the tradeoff is the visualizer waveform is only 8 bit so its lower quality, but for beat detection thats totally fine.

(fun lore, mediaprojection used to be in here and running it first actually POISONS the visualizer into reading silence, so i ripped it out entirely. dont mix the two, learned that the hard way.)

## it keeps running with the screen off

the whole engine lives in a foreground service. so u put the phone face down, screen off, music going, and the glyphs keep dancing the entire time. thats kinda the whole point, the back of the phone IS the show, u shouldnt have to keep the app open staring at it.

## running it

u need:

- flutter 3.41+ and dart 3.2+
- a physical nothing phone. on literally anything else the glyph sdk just no-ops, the ui still works but no lights come on
- android sdk stuff, minSdk and targetSdk 34, compileSdk 36, java 17
- the nothing glyph sdk `.aar` (see below, its NOT in this repo)

**grab the glyph sdk first.** i dont commit nothings sdk to this repo cuz its their proprietary binary and not mine to redistribute. so before u build, download the `glyph-sdk.aar` (aka the GlyphMatrix sdk) from nothings official [Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) and drop it here:

```
android/app/libs/glyph-sdk.aar
```

then ur good:

```bash
flutter pub get          # grab deps
flutter run --release    # run it on ur phone
flutter analyze          # lint if u care
flutter test             # run the tests
```

> RUN IT IN RELEASE. im not even joking. the beat engine is heavy native math and debug mode inflates the dsp cost like 3x, which makes the lights drift out of sync and the whole thing feels mushy and wrong. on release its smooth, native analysis is around 710 microseconds a frame which is like 6% of the frame budget, basically free. always profile on release or u will be debugging a problem that doesnt exist.

building an apk:

```bash
flutter build apk --release
```

## how its built (the actual architecture)

its two halves talking across a platform channel boundary, and they do very different jobs.

the **flutter side** (`lib/`) is purely the control panel and a live diagnostics dashboard. it does ZERO dsp. it just starts and stops the engine, saves ur settings, and draws whatever telemetry the native side streams back up.

the **native android side** (`android/.../com/glyphvisualizer/`) does everything that actually matters. audio capture, fft, onset detection, lighting the leds. all of it runs inside the foreground service so it survives the ui being closed.

they talk over two channels:

- a **method channel** (`com.glyphvisualizer/control`) for commands going down, start, stop, setSensitivity, setModel, setCaptureMode, isRunning, getDeviceInfo
- an **event channel** (`com.glyphvisualizer/events`) streaming per frame telemetry back up, spectrum magnitudes, band energies, onset flag, bpm, capture source, all the diagnostics

also a cute detail, theres THREE different clock rates in here on purpose and theyre all independent:

- dsp analysis runs ~172 frames/sec on mic (256 sample hop, 75% overlap) or ~48 hz on the visualizer poll
- the glyph render runs on every dsp frame now (no throttle) so the lights are as instant as the analysis, but it only actually pushes to the hardware when the lit zone changes
- the flutter events are adaptive, ~120 hz cap while the ui is on screen so the dashboard keeps up, dropping to ~30 hz when u background the app so it stops wasting battery drawing a dashboard nobody is looking at

### the native files (where the magic is)

- **`VisualizerService.java`** is the conductor. the foreground service that owns the whole pipeline, capture to frame assembly to handing frames to the dsp thread to driving the glyph render. the `updateGlyphs` overdrive renderer lives here, this is the heart.
- **`BeatDetector.java`** is the brain. complex domain spectral flux with phase prediction, adaptive median + MAD thresholds over a rolling history, peak picking onset detection, the vocal notch, per band onset flags, bpm estimation. heavily optimized for zero per frame allocation (pre allocated scratch buffers everywhere). if u wanna poke at the actual beat detection, this is the file. i tuned it for genuinely too long.
- **`FFT.java`** is a radix-2 fft with precomputed twiddle and bit reversal tables. one gotcha, its `magnitude()` returns SQUARED magnitudes (no sqrt) for speed, so anything reading it has to account for that.
- **`AudioCapture.java`** is the mic path. it negotiates, tries a couple sample rates and a few audio sources preferring the unprocessed signal, reports what it actually got, and surfaces hard read failures so the service can recover instead of just dying.
- **`SystemVisualizerCapture.java`** is the system audio path, the global visualizer on session 0, the one that beats spotifys capture block. polls `getWaveForm()` on its own thread instead of using the rate limited callback so it gets ~48 hz instead of the ~20 hz callback cap.
- **`GlyphController.java`** is the hardware layer over the nothing `ketchum` sdk. maps logical channels A/B/C to the physical led indices per device model, and does the gamma corrected brightness (gamma 2.2, maxes at 4000 of the sdks 0 to 4095). `flashBeat` is the multi zone overdrive lighting call.
- **`DrumClassifier.java`** is the AI bit, the tiny MLP that types each onset as kick/snare/hat from the spectral shape. `DrumClassifierModel.java` next to it is the generated weights (dont hand edit it, its made by the trainer). zero-alloc forward pass, pure java. trained on real drums by `tool/train_real.py` (`tool/prepare_real_dataset.py` preps the data, `tool/train_drum_classifier.py` is the synthetic-only fallback + shared feature code), parity locked by `DrumClassifierTest.java`.
- **`MainActivity.kt`** is the flutter entry point on the android side, wires up the method and event channels and holds the global event sink that bridges the activity and the service.

### the flutter files (the pretty part)

- **`main.dart`** boots the app, just the MaterialApp shell.
- **`screens/home_screen.dart`** is the main control panel. start/stop, model selector, sensitivity slider, the live spectrum and beat ring, stats, the diagnostics overlay, and the MIC/SYSTEM toggle. settings persist with shared_preferences.
- **`screens/calibration_screen.dart`** is a threshold and noise floor tuning view fed by the live event stream, for when u wanna actually see what the detector is doing.
- **`services/glyph_visualizer_service.dart`** is the dart side of the bridge, wraps both platform channels into a clean api the ui can call.
- **`models/audio_frame_data.dart`** is the shape of every telemetry frame coming up from native (magnitudes, band energies, onset, beat type, bpm, flux, thresholds, noise floor, all of it). if u add a field to the native payload u gotta update this too or it just wont parse.
- **`models/glyph_device_info.dart`** is the little model for detected model / supported models / device name.
- **`widgets/beat_ring.dart`** is the onset pulse animation, the ring that punches on every beat.
- **`widgets/spectrum_painter.dart`** is the live spectrum custom painter, pre allocates all its Paint objects so it doesnt allocate every frame, smart.
- **`widgets/glyph_zones.dart`** is the on screen mirror of the rear glyph zones so u can see whats lighting up without flipping the phone over.

## heads up / honesty corner

- the `NothingKey` in the manifest is set to `"test"`, thats the dev placeholder key, fine for messing around but its not a prod key
- only the 3a pro is properly tested, other models have led mappings but i genuinely cant promise theyre right
- the dsp and timing constants are precious, tuned by feel, please dont drive by "improve" them
- theres an older `animateDrums` renderer still sitting in `GlyphController` from a previous build, its not wired up anymore, ignore it, the current beat-locked `flashBeat` renderer replaced it

thats basically the whole thing. a music visualizer for the BACK of ur phone that actually listens instead of just blinking at volume. vibe coded on and off but the math under it is very real fr.

## license

my code is **MIT**, do whatever, just keep the notice and dont sue me, full text in [LICENSE](LICENSE).

one important thing tho, the MIT license only covers the code i wrote. it does NOT cover the **nothing glyph sdk** (`glyph-sdk.aar`, the `com.nothing.ketchum` package). that sdk is copyright Nothing Technology Limited, im not redistributing it (its gitignored, u download it urself from nothings official kit), and ur use of it is under nothings terms not mine. just so were clear and nobody yells at anybody.
