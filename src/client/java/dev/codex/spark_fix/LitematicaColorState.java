package dev.codex.spark_fix;

import java.util.Locale;

/** Draft color in native AARRGGBB order, retaining hue while saturation or brightness is zero. */
final class LitematicaColorState {
    enum Channel { H, S, V, R, G, B, A }
    private int argb;
    private double hue;
    private double saturation;
    private double brightness;

    LitematicaColorState(int argb) { setArgb(argb); }
    int argb() { return argb; }
    String hex() { return String.format(Locale.ROOT, "#%08X", argb); }

    void setArgb(int argb) {
        this.argb = argb;
        double r = (argb >> 16 & 255) / 255.0, g = (argb >> 8 & 255) / 255.0, b = (argb & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), delta = max - min;
        brightness = max;
        if (max > 0) saturation = delta / max;
        if (delta > 0) {
            hue = 60 * (max == r ? (g - b) / delta : max == g ? (b - r) / delta + 2 : (r - g) / delta + 4);
            if (hue < 0) hue += 360;
        }
    }

    static double maximum(Channel channel) {
        return switch (channel) { case H -> 360; case S, V -> 100; default -> 255; };
    }

    double get(Channel channel) {
        return switch (channel) {
            case H -> hue; case S -> saturation * 100; case V -> brightness * 100;
            case R -> argb >> 16 & 255; case G -> argb >> 8 & 255; case B -> argb & 255; case A -> argb >>> 24;
        };
    }

    void set(Channel channel, double value) {
        if (!Double.isFinite(value) || value < 0 || value > maximum(channel)) throw new IllegalArgumentException("Invalid color channel");
        switch (channel) {
            case H -> { hue = value; updateHsv(); }
            case S -> { saturation = value / 100; updateHsv(); }
            case V -> { brightness = value / 100; updateHsv(); }
            case A -> argb = argb & 0xFFFFFF | (int) Math.round(value) << 24;
            default -> {
                int shift = channel == Channel.R ? 16 : channel == Channel.G ? 8 : 0;
                setArgb(argb & ~(255 << shift) | (int) Math.round(value) << shift);
            }
        }
    }

    void setSv(double saturation, double brightness) {
        this.saturation = Math.clamp(saturation, 0, 1);
        this.brightness = Math.clamp(brightness, 0, 1);
        updateHsv();
    }

    private void updateHsv() { argb = argb & 0xFF000000 | rgb(hue, saturation, brightness); }

    static int rgb(double hue, double saturation, double brightness) {
        double h = (hue % 360) / 60, c = brightness * saturation;
        double x = c * (1 - Math.abs(h % 2 - 1)), m = brightness - c;
        double r, g, b;
        if (h < 1) { r = c; g = x; b = 0; }
        else if (h < 2) { r = x; g = c; b = 0; }
        else if (h < 3) { r = 0; g = c; b = x; }
        else if (h < 4) { r = 0; g = x; b = c; }
        else if (h < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return (int) Math.round((r + m) * 255) << 16 | (int) Math.round((g + m) * 255) << 8 | (int) Math.round((b + m) * 255);
    }

    boolean parseHex(String text) {
        String value = text.strip().replaceFirst("^#", "");
        if (!value.matches("(?i)([0-9a-f]{6}|[0-9a-f]{8})")) return false;
        setArgb((int) Long.parseLong(value, 16) | (value.length() == 6 ? 0xFF000000 : 0));
        return true;
    }
}
