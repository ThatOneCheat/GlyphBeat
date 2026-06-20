package com.glyphvisualizer;

import com.nothing.ketchum.GlyphFrame;
import com.nothing.ketchum.GlyphManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Hardware abstraction layer. Maps the beat visualizer onto the three logical glyph
 * channels for the current device:
 *   Channel C = the main ring, Channel A = the slash, Channel B = the accent dot.
 *
 * The single render method emits exactly one frame from already-computed intensities.
 * All temporal logic (onset latching, hold windows) lives in {@link VisualizerService};
 * this class is intentionally stateless apart from the per-model channel maps.
 */
public class GlyphController {

    private final GlyphManager glyphManager;
    private int[] channelA = new int[0];
    private int[] channelB = new int[0];
    private int[] channelC = new int[0];

    // Per-LED brightness for buildChannel(channel, value). The Nothing SDK accepts 0..4095;
    // 4000 is effectively full with a little headroom. Drum zones snap on at full (no fade); level()
    // gamma-corrects only if a partial intensity is ever passed.
    private static final int MAX_BRIGHTNESS = 4000;
    private static final double BRIGHTNESS_GAMMA = 2.2;

    public GlyphController(GlyphManager glyphManager) {
        this.glyphManager = glyphManager;
        setModel(4); // Default to Phone (3a)
    }

    public void setModel(int model) {
        List<Integer> a = new ArrayList<>();
        List<Integer> b = new ArrayList<>();
        List<Integer> c = new ArrayList<>();

        switch (model) {
            case 0: // Phone (1)
                a.add(0); // A1
                b.add(1); // B1
                for (int i = 2; i <= 5; i++) c.add(i); // C1-C4
                for (int i = 7; i <= 14; i++) c.add(i); // D1
                c.add(6); // E1
                break;
            case 1: // Phone (2)
                a.add(0); a.add(1); // A1, A2
                b.add(2); // B1
                for (int i = 3; i <= 18; i++) c.add(i); // C1
                for (int i = 19; i <= 23; i++) c.add(i); // C2-C6
                for (int i = 25; i <= 32; i++) c.add(i); // D1
                c.add(24); // E1
                break;
            case 2: // Phone (2a)
            case 3: // Phone (2a)+
                a.add(25); // A
                b.add(24); // B
                for (int i = 0; i <= 23; i++) c.add(i); // C1-C24
                break;
            case 4: // Phone (3a) Pro
                for (int i = 20; i <= 30; i++) a.add(i); // A1-A11
                for (int i = 31; i <= 35; i++) b.add(i); // B1-B5
                for (int i = 0; i <= 19; i++) c.add(i); // C1-C20
                break;
            case 5: // Phone (4a)
                for (int i = 0; i <= 5; i++) a.add(i); // A1-A6
                break;
        }

        channelA = toArray(a);
        channelB = toArray(b);
        channelC = toArray(c);
    }

    private int[] toArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /**
     * Beat visualizer. Each drum element lights its whole zone with a bold, hard-edged flash
     * (no fade): kick -> ring (C), snare -> slash (A), hi-hat -> dot (B). The service drives one
     * at a time, so the light jumps across the whole glyph in rhythm. Pass &lt;= 0 to leave a zone dark.
     */
    public void animateDrums(double kickLevel, double snareLevel, double hatLevel) {
        if (kickLevel <= 0 && snareLevel <= 0 && hatLevel <= 0) {
            glyphManager.turnOff();
            return;
        }

        GlyphFrame.Builder builder = glyphManager.getGlyphFrameBuilder();
        if (kickLevel > 0 && channelC.length > 0)  { int b = level(kickLevel);  for (int led : channelC) builder.buildChannel(led, b); }
        if (snareLevel > 0 && channelA.length > 0) { int b = level(snareLevel); for (int led : channelA) builder.buildChannel(led, b); }
        if (hatLevel > 0 && channelB.length > 0)   { int b = level(hatLevel);   for (int led : channelB) builder.buildChannel(led, b); }
        glyphManager.toggle(builder.build());
    }

    /**
     * "BEAT" (overdrive) renderer ported in feel from the previous GlyphVisualizer: each band flashes
     * its whole zone hard on/off (no fade) and multiple zones can be lit at once. Mapping (shuffled):
     * bass/kick -> slash (A), mid/snare -> dot (B), treble/hi-hat -> ring (C). The service sets each
     * flag from normalized band energy OR a drum onset every render.
     */
    public void flashBeat(boolean flashBass, boolean flashMid, boolean flashTreble) {
        if (!flashBass && !flashMid && !flashTreble) {
            glyphManager.turnOff();
            return;
        }

        GlyphFrame.Builder builder = glyphManager.getGlyphFrameBuilder();
        if (flashBass   && channelA.length > 0) { for (int led : channelA) builder.buildChannel(led, MAX_BRIGHTNESS); } // kick  -> slash
        if (flashMid    && channelB.length > 0) { for (int led : channelB) builder.buildChannel(led, MAX_BRIGHTNESS); } // snare -> dot
        if (flashTreble && channelC.length > 0) { for (int led : channelC) builder.buildChannel(led, MAX_BRIGHTNESS); } // hat   -> ring
        glyphManager.toggle(builder.build());
    }

    // Maps a 0..1 intensity to a gamma-corrected SDK brightness (drum hits pass 1.0 = full snap).
    private int level(double intensity) {
        if (intensity <= 0) return 0;
        if (intensity >= 1) return MAX_BRIGHTNESS;
        return (int) Math.round(Math.pow(intensity, BRIGHTNESS_GAMMA) * MAX_BRIGHTNESS);
    }

    public void turnOff() {
        glyphManager.turnOff();
    }
}
