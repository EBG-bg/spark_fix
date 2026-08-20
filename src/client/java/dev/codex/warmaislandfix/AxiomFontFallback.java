package dev.codex.warmaislandfix;

import imgui.moulberry92.ImFont;
import imgui.moulberry92.ImFontAtlas;
import imgui.moulberry92.ImFontConfig;
import imgui.moulberry92.ImFontGlyphRangesBuilder;
import net.minecraft.locale.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

/** Rebuilds only a failed Chinese Axiom font atlas with bounded resources. */
public final class AxiomFontFallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix/AxiomFonts");
    private static final int MIN_FONT_SIZE = 12;
    private static final int MAX_RECOVERY_FONT_SIZE = 18;
    private static final int MINIMAL_FONT_SIZE = 16;
    private static final String ATLAS_OVERFLOW_ASSERTION = "Out of texture memory";
    private static final ThreadLocal<BuildMonitor> ACTIVE_BUILD = new ThreadLocal<>();

    private static final char[] EXTRA_GLYPHS = {
        '\u2190', '\u2191', '\u2192', '\u2193', '\u2303', '\u2318',
        '\u2387', '\u26A0', '\u2716', '\u2756'
    };
    private static final char[] EMOJI_GLYPHS = {
        '\u2614', '\u2616', '\u2620', '\u2622', '\u2764',
        '\u279E', '\u2B05', '\u2B06', '\u2B07'
    };
    private static final short[] ICON_GLYPHS = {
        (short) 0xE900, (short) 0xE929, 0
    };

    private AxiomFontFallback() {
    }

    public static boolean isChinese(String languageCode) {
        return languageCode != null && languageCode.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /**
     * Dear ImGui 1.92.7 reports individual glyph packing failures through its
     * assertion callback but still returns true from ImFontAtlas.Build().
     */
    public static boolean buildWithoutAtlasOverflow(ImFontAtlas atlas) {
        BuildMonitor monitor = new BuildMonitor();
        ACTIVE_BUILD.set(monitor);
        try {
            return atlas.build() && !monitor.atlasOverflow;
        } finally {
            ACTIVE_BUILD.remove();
        }
    }

    /** Returns true when the assertion belongs to the guarded Axiom font build. */
    public static boolean recordImGuiAssertion(String expression) {
        BuildMonitor monitor = ACTIVE_BUILD.get();
        if (monitor == null || expression == null || !expression.contains(ATLAS_OVERFLOW_ASSERTION)) {
            return false;
        }

        monitor.atlasOverflow = true;
        return true;
    }

    public static RecoveryResult rebuild(
        ImFontAtlas atlas,
        String languageCode,
        int requestedFontSize,
        FontLoader fontLoader
    ) {
        int boundedSize = Math.max(
            MIN_FONT_SIZE,
            Math.min(MAX_RECOVERY_FONT_SIZE, requestedFontSize)
        );

        RecoveryResult translated = buildTranslatedFallback(
            atlas,
            languageCode,
            boundedSize,
            fontLoader
        );
        if (translated != null) {
            return translated;
        }

        LOGGER.warn("The reduced Chinese Axiom font atlas still did not fit; using the minimal UI font fallback.");
        return buildMinimalFallback(atlas, fontLoader);
    }

    private static RecoveryResult buildTranslatedFallback(
        ImFontAtlas atlas,
        String languageCode,
        int fontSize,
        FontLoader fontLoader
    ) {
        atlas.clear();

        ImFontGlyphRangesBuilder rangesBuilder = new ImFontGlyphRangesBuilder();
        rangesBuilder.addRanges(atlas.getGlyphRangesDefault());
        rangesBuilder.addRanges(atlas.getGlyphRangesChineseSimplifiedCommon());
        if (WarmaIslandFixConfig.scanAllTranslations()) {
            addActiveTranslationGlyphs(rangesBuilder);
        }
        for (char glyph : EXTRA_GLYPHS) {
            rangesBuilder.addChar(glyph);
        }
        short[] glyphRanges = rangesBuilder.buildRanges();

        ImFontConfig config = new ImFontConfig();
        try {
            config.setOversampleH(1);
            config.setOversampleV(1);

            config.setName("WarmaIslandFix Inter");
            ImFont regular = atlas.addFontFromMemoryTTF(
                fontLoader.load("inter-medium.ttf"),
                fontSize,
                config,
                glyphRanges
            );

            config.setMergeMode(true);
            config.setName("WarmaIslandFix Chinese");
            String chineseFont = isTraditionalChinese(languageCode)
                ? "notosanstc-medium.ttf"
                : "notosanssc-medium.ttf";
            atlas.addFontFromMemoryTTF(
                fontLoader.load(chineseFont),
                fontSize * 1.25F,
                config,
                glyphRanges
            );

            ImFontGlyphRangesBuilder emojiBuilder = new ImFontGlyphRangesBuilder();
            for (char glyph : EMOJI_GLYPHS) {
                emojiBuilder.addChar(glyph);
            }
            config.setName("WarmaIslandFix Emoji");
            atlas.addFontFromMemoryTTF(
                fontLoader.load("noto-emoji-stripped-bold.ttf"),
                fontSize,
                config,
                emojiBuilder.buildRanges()
            );

            config.setMergeMode(false);
            config.setName("WarmaIslandFix Monocraft");
            ImFont monospace = atlas.addFontFromMemoryTTF(
                fontLoader.load("monocraft.ttf"),
                fontSize,
                config,
                glyphRanges
            );

            config.setName("WarmaIslandFix Icons");
            ImFont icons = atlas.addFontFromMemoryTTF(
                fontLoader.load("axiomicons.ttf"),
                fontSize * 2.0F,
                config,
                ICON_GLYPHS
            );

            if (!buildWithoutAtlasOverflow(atlas)) {
                return null;
            }

            return new RecoveryResult(regular, icons, monospace, fontSize, false);
        } finally {
            config.destroy();
        }
    }

    private static RecoveryResult buildMinimalFallback(
        ImFontAtlas atlas,
        FontLoader fontLoader
    ) {
        atlas.clear();
        short[] defaultRanges = atlas.getGlyphRangesDefault();
        ImFontConfig config = new ImFontConfig();

        try {
            config.setOversampleH(1);
            config.setOversampleV(1);

            config.setName("WarmaIslandFix Minimal Inter");
            ImFont regular = atlas.addFontFromMemoryTTF(
                fontLoader.load("inter-medium.ttf"),
                MINIMAL_FONT_SIZE,
                config,
                defaultRanges
            );

            config.setName("WarmaIslandFix Minimal Monocraft");
            ImFont monospace = atlas.addFontFromMemoryTTF(
                fontLoader.load("monocraft.ttf"),
                MINIMAL_FONT_SIZE,
                config,
                defaultRanges
            );

            config.setName("WarmaIslandFix Minimal Icons");
            ImFont icons = atlas.addFontFromMemoryTTF(
                fontLoader.load("axiomicons.ttf"),
                MINIMAL_FONT_SIZE * 2.0F,
                config,
                ICON_GLYPHS
            );

            if (!buildWithoutAtlasOverflow(atlas)) {
                return null;
            }

            return new RecoveryResult(
                regular,
                icons,
                monospace,
                MINIMAL_FONT_SIZE,
                true
            );
        } finally {
            config.destroy();
        }
    }

    private static boolean isTraditionalChinese(String languageCode) {
        String normalized = languageCode == null
            ? ""
            : languageCode.toLowerCase(Locale.ROOT);
        return normalized.startsWith("zh_tw")
            || normalized.startsWith("zh_hk")
            || normalized.startsWith("zh_mo")
            || normalized.contains("hant");
    }

    private static void addActiveTranslationGlyphs(ImFontGlyphRangesBuilder builder) {
        Object language = Language.getInstance();
        try {
            Field storageField = language.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            Object storage = storageField.get(language);
            if (storage instanceof Map<?, ?> translations) {
                for (Object value : translations.values()) {
                    if (value instanceof String text) {
                        builder.addText(text);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // The common Chinese range remains available even if mappings change.
            LOGGER.debug("Could not add active translation glyphs to the Axiom fallback atlas.", exception);
        }
    }

    @FunctionalInterface
    public interface FontLoader {
        byte[] load(String name);
    }

    public record RecoveryResult(
        ImFont regular,
        ImFont icons,
        ImFont monospace,
        int fontSize,
        boolean minimal
    ) {
    }

    private static final class BuildMonitor {
        private boolean atlasOverflow;
    }
}
