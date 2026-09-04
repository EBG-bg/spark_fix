package dev.codex.spark_fix;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu integration is kept in the client source set because Mod Menu is optional. */
public final class SparkFixModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SparkFixConfigScreen::new;
    }
}
