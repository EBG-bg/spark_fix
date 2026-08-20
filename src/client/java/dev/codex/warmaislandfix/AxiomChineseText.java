package dev.codex.warmaislandfix;

import net.minecraft.client.Minecraft;

/** Supplies the few Chinese labels that Axiom 5.5.0 still constructs literally. */
public final class AxiomChineseText {
    private AxiomChineseText() {
    }

    public static String forCurrentLanguage(String original, String simplifiedChinese) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null
                && "zh_cn".equalsIgnoreCase(minecraft.getLanguageManager().getSelected())) {
            return simplifiedChinese;
        }
        return original;
    }
}
