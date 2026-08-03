package net.minecraft.client.resources.language;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.client.resources.metadata.language.LanguageMetadataSection;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class LanguageManager implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LanguageInfo DEFAULT_LANGUAGE = new LanguageInfo("US", "English", false);
    private Map<String, LanguageInfo> languages = ImmutableMap.of("en_us", DEFAULT_LANGUAGE);
    private String currentCode;
    private final Consumer<ClientLanguage> reloadCallback;

    public LanguageManager(String languageCode, Consumer<ClientLanguage> reloadCallback) {
        this.currentCode = languageCode;
        this.reloadCallback = reloadCallback;
    }

    private static Map<String, LanguageInfo> extractLanguages(Stream<PackResources> resourcePacks) {
        Map<String, LanguageInfo> result = Maps.newHashMap();
        resourcePacks.forEach(resourcePack -> {
            try {
                LanguageMetadataSection languageMetadataSection = resourcePack.getMetadataSection(LanguageMetadataSection.TYPE);
                if (languageMetadataSection != null) {
                    languageMetadataSection.languages().forEach(result::putIfAbsent);
                }
            } catch (Exception e) {
                LOGGER.warn("Unable to parse language metadata section of resourcepack: {}", resourcePack.packId(), e);
            }
        });
        return ImmutableMap.copyOf(result);
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        this.apply(this.prepare(resourceManager));
    }

    /** Parses language packs off-thread and atomically publishes the new language on the game thread. */
    public CompletableFuture<Void> allcraftReload(ResourceManager resourceManager, Executor taskExecutor, Executor reloadExecutor) {
        return CompletableFuture.supplyAsync(() -> this.prepare(resourceManager), taskExecutor)
            .thenAcceptAsync(this::apply, reloadExecutor);
    }

    private LanguageManager.PreparedLanguage prepare(ResourceManager resourceManager) {
        Map<String, LanguageInfo> availableLanguages = extractLanguages(resourceManager.listPacks());
        List<String> languageStack = new ArrayList<>(2);
        boolean defaultRightToLeft = DEFAULT_LANGUAGE.bidirectional();
        languageStack.add("en_us");
        if (!this.currentCode.equals("en_us")) {
            LanguageInfo currentLanguage = availableLanguages.get(this.currentCode);
            if (currentLanguage != null) {
                languageStack.add(this.currentCode);
                defaultRightToLeft = currentLanguage.bidirectional();
            }
        }
        ClientLanguage locale = ClientLanguage.loadFrom(resourceManager, languageStack, defaultRightToLeft);
        return new LanguageManager.PreparedLanguage(availableLanguages, locale);
    }

    private void apply(LanguageManager.PreparedLanguage prepared) {
        this.languages = prepared.languages;
        Language.inject(prepared.locale);
        this.reloadCallback.accept(prepared.locale);
    }

    public void setSelected(String code) {
        this.currentCode = code;
    }

    public String getSelected() {
        return this.currentCode;
    }

    public SortedMap<String, LanguageInfo> getLanguages() {
        return new TreeMap<>(this.languages);
    }

    public @Nullable LanguageInfo getLanguage(String code) {
        return this.languages.get(code);
    }

    private record PreparedLanguage(Map<String, LanguageInfo> languages, ClientLanguage locale) {
    }
}
