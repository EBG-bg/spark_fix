package dev.codex.spark_fix.mixin.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.codex.spark_fix.PrinterConfigPersistence;
import dev.codex.spark_fix.PrinterConfigIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;
import java.util.List;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.config.Configs", remap = false)
abstract class PrinterConfigSaveMixin {
    @WrapOperation(
            method = "load()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/data/json/JsonUtils;parseJsonFile(Ljava/nio/file/Path;)Lcom/google/gson/JsonElement;"
            ),
            require = 0,
            remap = false
    )
    private JsonElement sparkFix$selectPrinterSettings(Path file, Operation<JsonElement> original) {
        JsonElement parsed = original.call(file);
        if (file.getFileName() == null || !file.getFileName().toString().equals("litematica-printer.json")) return parsed;
        var profile = PrinterConfigIdentity.forConfigClass(getClass());
        // Optional.map would replace a null failure result with the foreign parsed config.
        return profile.isPresent() ? PrinterConfigPersistence.load(parsed, file, profile.get()) : parsed;
    }

    @WrapOperation(
            method = "load()V",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/config/ConfigUtils;readConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"),
            require = 0,
            remap = false
    )
    private void sparkFix$loadHiddenOptions(JsonObject root, String section, List<?> options, Operation<Void> original) {
        original.call(root, section, PrinterConfigIdentity.completeOptions(getClass(), options));
    }

    @WrapOperation(
            method = "save()V",
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/config/ConfigUtils;writeConfigBase(Lcom/google/gson/JsonObject;Ljava/lang/String;Ljava/util/List;)V"),
            require = 0,
            remap = false
    )
    private void sparkFix$saveHiddenOptions(JsonObject root, String section, List<?> options, Operation<Void> original) {
        original.call(root, section, PrinterConfigIdentity.completeOptions(getClass(), options));
    }

    @WrapOperation(
            method = "save()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/data/json/JsonUtils;writeJsonToFile(Lcom/google/gson/JsonElement;Ljava/nio/file/Path;)Z"
            ),
            require = 0,
            remap = false
    )
    private boolean sparkFix$preserveOtherPrinterSettings(JsonElement current, Path file, Operation<Boolean> original) {
        if (file.getFileName() == null || !file.getFileName().toString().equals("litematica-printer.json")) {
            return original.call(current, file);
        }
        return PrinterConfigIdentity.forConfigClass(getClass())
                .map(profile -> PrinterConfigPersistence.save(current, file, profile))
                .orElseGet(() -> original.call(current, file));
    }
}
