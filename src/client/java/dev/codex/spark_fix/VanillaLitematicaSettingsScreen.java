package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Dependency-free, responsive card based fallback for dynamic MaLiLib settings. */
class VanillaLitematicaSettingsScreen extends Screen {
    private static final int CARD_GAP = 6;
    private static final int MAX_COLUMNS = 5;
    private static final int SECTION_HEIGHT = 26;
    private static final long SETTLE_NANOS = 180_000_000L;
    private static final long SCROLL_NANOS = 180_000_000L;

    private final Screen parent;
    private final List<LitematicaConfigDiscovery.DiscoveredGroup> groups;
    private final List<Card> cards = new ArrayList<>();
    private final List<String> discoveredKeys;
    private List<Option> cachedOptions;
    private final Map<String, String> optionSearchText = new LinkedHashMap<>();
    private final Map<String, String> originalOptionNames = new LinkedHashMap<>();
    private final Map<String, Component> optionComments = new LinkedHashMap<>();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<EmptyState> emptyStates = new ArrayList<>();
    private final List<GroupDivider> groupDividers = new ArrayList<>();
    private final Map<Object, LitematicaCategories.Category> categoryByOption = new java.util.IdentityHashMap<>();
    private final Map<String, String> moduleNames = new LinkedHashMap<>();
    private final LitematicaRenderLayerSettings renderLayers;
    private final List<String> favoriteOrder = new ArrayList<>();
    private final List<String> settingsOrder = new ArrayList<>();
    private final Map<String, List<String>> litematicaAliases = new LinkedHashMap<>();
    private final Map<String, List<String>> litematicaNames = new LinkedHashMap<>();
    private final Set<String> appliedAliasPresets = new LinkedHashSet<>();
    private final LitematicaCustomAliases customAliases;
    private final Map<String, EditBox> valueInputs = new LinkedHashMap<>();
    private final Map<String, LitematicaNumericWidget> numericInputs = new LinkedHashMap<>();
    private final Map<String, List<String>> layoutColumns = new LinkedHashMap<>();
    private final Map<String, List<String>> filteredColumns = new LinkedHashMap<>();
    private final Set<String> hiddenModules = new LinkedHashSet<>();
    private final Map<String, String> moduleByGroup = new LinkedHashMap<>();
    private final List<LitematicaModuleButton> moduleButtons = new ArrayList<>();
    private List<LitematicaCategories.Category> categories = List.of();
    private final List<Button> addonCategoryButtons = new ArrayList<>();
    private LitematicaModuleButton addonMenuOwner;
    private ScreenRectangle addonMenuBounds;
    private LitematicaCategoryBar favoritesCategories;
    private LitematicaCategoryBar allCategories;
    private String favoritesCategory = "";
    private String allCategory = "";
    private EditBox favoritesSearchBox;
    private EditBox allSettingsSearchBox;
    private StringWidget favoritesHeading;
    private StringWidget othersHeading;
    private LitematicaCompactnessSlider compactnessSlider;
    private ScrollSensitivitySlider scrollSensitivitySlider;
    private AbstractWidget layoutResetButton;
    private int titleWidthLimit;
    private SettingsScrollBar scrollBar;
    private String favoritesQuery = "";
    private String allSettingsQuery = "";
    private int scroll;
    private double scrollPosition;
    private double scrollStart;
    private double scrollTarget;
    private long scrollStartedAt;
    private int panelLeft;
    private int panelWidth;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private int favoritesHeaderY;
    private int othersHeaderY;
    private String draggingKey;
    private boolean draggingFavorite;
    private double dragMouseX;
    private double dragMouseY;
    private double dragOffsetX;
    private double dragOffsetY;
    private DropTarget dropTarget;
    private float dragLift;
    private long dragStartedAt;
    private boolean layoutDirty;
    private boolean savedOnClose;
    private boolean openingChildScreen;
    private String infoTooltipKey;
    private int infoTooltipOffset;

    VanillaLitematicaSettingsScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.litematica_section"));
        this.parent = parent;
        var discovered = LitematicaConfigDiscovery.discover();
        renderLayers = discovered.stream().anyMatch(group -> group.id().equals("litematica"))
                ? LitematicaRenderLayerSettings.load(() -> {
                    numericInputs.keySet().removeIf(key -> key.contains("::spark_fix.render_layer."));
                    layoutDirty = true;
                }) : null;
        this.groups = renderLayers == null ? discovered : renderLayers.attach(discovered);
        discoveredKeys = discoverOptions().stream().map(Option::key).toList();
        favoriteOrder.addAll(SparkFixConfig.litematicaFavorites(discoveredKeys));
        settingsOrder.addAll(SparkFixConfig.litematicaSettingsOrder(discoveredKeys));
        litematicaAliases.putAll(SparkFixConfig.litematicaAliases(discoveredKeys));
        litematicaNames.putAll(SparkFixConfig.litematicaNames(discoveredKeys));
        appliedAliasPresets.addAll(SparkFixConfig.litematicaAliasPresetsApplied());
        Map<String, List<String>> mainNames = aliasPrimaryNames();
        Map<String, List<String>> presetAliases = LitematicaAliasPresets.apply(
                discoveredKeys, litematicaAliases, appliedAliasPresets, mainNames);
        customAliases = LitematicaCustomAliases.load(discoveredKeys, litematicaAliases, presetAliases, mainNames);
        SparkFixConfig.litematicaColumns(discoveredKeys).forEach((layout, keys) ->
                layoutColumns.put(layout, new ArrayList<>(keys)));
    }

    private Map<String, List<String>> aliasPrimaryNames() {
        Map<String, List<String>> mainNames = new LinkedHashMap<>();
        for (Option option : discoverOptions()) {
            List<String> names = new ArrayList<>(litematicaNames.getOrDefault(option.key(), List.of()));
            names.add(option.name());
            names.add(originalOptionNames.get(option.key()));
            mainNames.put(option.key(), names);
        }
        return mainNames;
    }

    @Override
    protected void init() {
        cachedOptions = null;
        clearAddonMenu();
        categories = LitematicaCategories.discover(groups);
        categoryByOption.clear();
        for (var category : categories) {
            for (Object option : category.options()) categoryByOption.putIfAbsent(option, category);
        }
        sortNewCategories();
        joinSettingsGroups();
        draggingKey = null;
        dropTarget = null;
        savedOnClose = false;
        openingChildScreen = false;
        clearWidgets();
        cards.clear();
        contentWidgets.clear();
        panelWidth = Math.max(1, width - 32);
        panelLeft = (width - panelWidth) / 2;
        contentBottom = height;
        int slidersY = 30 + addModuleFilters();
        contentTop = slidersY + 22;
        int controlsWidth = panelWidth - 32;
        boolean narrowHeader = controlsWidth < 408;
        int slidersWidth = narrowHeader ? controlsWidth : controlsWidth - 90;
        int columnsWidth = (slidersWidth - 6) / 2;
        compactnessSlider = new LitematicaCompactnessSlider(panelLeft + 16, slidersY, columnsWidth,
                this::rebuildLayout);
        addRenderableWidget(compactnessSlider);
        scrollSensitivitySlider = new ScrollSensitivitySlider(panelLeft + 22 + columnsWidth, slidersY,
                slidersWidth - columnsWidth - 6, ScrollSensitivitySlider.Scope.LITEMATICA);
        scrollSensitivitySlider.setHeight(20);
        addRenderableWidget(scrollSensitivitySlider);
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> closeScreen())
                .bounds(panelLeft + panelWidth - (narrowHeader ? 60 : 100), narrowHeader ? 12 : slidersY,
                        narrowHeader ? 44 : 84, narrowHeader ? 16 : 20).build());
        int resetRight = panelLeft + panelWidth - 16 - (narrowHeader ? 48 : 0);
        Component resetLabel = Component.translatable("config.spark_fix.litematica_layout_reset");
        Component resetHint = Component.translatable("config.spark_fix.litematica_layout_reset_hint");
        int resetWidth = font.width(resetLabel) + 12;
        if (resetWidth <= resetRight - (width + font.width(title)) / 2 - 6) {
            layoutResetButton = Button.builder(resetLabel, ignored -> resetSettingsLayout())
                    .bounds(resetRight - resetWidth, 12, resetWidth, 16).build();
        } else {
            layoutResetButton = new SettingsIconButton(resetRight - 18, 11, 18, SettingsIconButton.Icon.RESET,
                    resetLabel, this::resetSettingsLayout, true);
        }
        layoutResetButton.setTooltip(Tooltip.create(resetHint));
        addRenderableWidget(layoutResetButton);
        titleWidthLimit = Math.max(1, 2 * (layoutResetButton.getX() - width / 2 - 6));
        scrollBar = addRenderableWidget(new SettingsScrollBar());
        rebuildLayout();
    }

    private int addModuleFilters() {
        moduleButtons.clear();
        moduleByGroup.clear();
        moduleNames.clear();
        var modules = LitematicaConfigDiscovery.modules(groups);
        hiddenModules.clear();
        hiddenModules.addAll(SparkFixConfig.litematicaHiddenModules(modules.stream()
                .map(LitematicaConfigDiscovery.DiscoveredModule::id).toList()));
        int step = LitematicaModuleButton.SIZE + 4;
        int firstRowSlots = Math.max(0, ((width - font.width(title)) / 2 - 8) / step);
        int fullRowSlots = Math.max(1, (width - 4) / step);
        for (int i = 0; i < modules.size(); i++) {
            var module = modules.get(i);
            for (String group : module.groupIds()) {
                moduleByGroup.put(group, module.id());
                moduleNames.put(group, module.name());
            }
            int overflow = i - firstRowSlots;
            int x = 4 + (overflow < 0 ? i : overflow % fullRowSlots) * step;
            int y = 4 + (overflow < 0 ? 0 : 1 + overflow / fullRowSlots) * step;
            moduleButtons.add(addRenderableWidget(new LitematicaModuleButton(x, y, module,
                    !hiddenModules.contains(module.id()), shown -> {
                        if (shown) hiddenModules.remove(module.id());
                        else hiddenModules.add(module.id());
                        if (!shown) {
                            if (module.groupIds().stream().anyMatch(group -> favoritesCategory.startsWith(group + ":"))) favoritesCategory = "";
                            if (module.groupIds().stream().anyMatch(group -> allCategory.startsWith(group + ":"))) allCategory = "";
                        }
                        clearAddonMenu();
                        SparkFixConfig.setLitematicaHiddenModules(new ArrayList<>(hiddenModules));
                        filteredColumns.clear();
                        rebuildLayout();
                    })));
        }
        int overflowCount = Math.max(0, modules.size() - firstRowSlots);
        return ((overflowCount + fullRowSlots - 1) / fullRowSlots) * step;
    }

    private void sortNewCategories() {
        Set<String> grouped = new LinkedHashSet<>(SparkFixConfig.litematicaCategoryOrdered());
        Set<String> availableGroups = new LinkedHashSet<>();
        categories.forEach(category -> availableGroups.add(category.group()));
        if (grouped.containsAll(availableGroups)) return;
        sortSettingsByCategory();
        layoutColumns.keySet().removeIf(key -> key.startsWith("settings."));
        grouped.addAll(availableGroups);
        SparkFixConfig.setLitematicaCategoryOrdered(new ArrayList<>(grouped));
    }

    private void sortSettingsByCategory() {
        Map<Object, Integer> ranks = new java.util.IdentityHashMap<>();
        List<LitematicaCategories.Category> orderedCategories = new ArrayList<>(categories);
        orderedCategories.sort(Comparator.comparingInt(category -> category.group().equals("litematica") ? 0 : 1));
        for (int i = 0; i < orderedCategories.size(); i++) {
            for (Object option : orderedCategories.get(i).options()) ranks.putIfAbsent(option, i);
        }
        Map<String, Integer> keys = new LinkedHashMap<>();
        for (Option option : discoverOptions()) keys.put(option.key(), ranks.getOrDefault(option.object(), Integer.MAX_VALUE));
        settingsOrder.sort(Comparator.comparingInt(key -> keys.getOrDefault(key, Integer.MAX_VALUE)));
    }

    private void resetSettingsLayout() {
        setFocused(null);
        settingsOrder.clear();
        settingsOrder.addAll(discoverOptions().stream().map(Option::key).toList());
        sortSettingsByCategory();
        joinSettingsGroups();
        layoutColumns.keySet().removeIf(key -> key.startsWith("settings."));
        filteredColumns.keySet().removeIf(key -> key.startsWith("settings."));
        rebuildLayout();
    }

    private List<LitematicaCategoryBar.Choice> categoryChoices(boolean favorite) {
        List<LitematicaCategoryBar.Choice> choices = new ArrayList<>();
        choices.add(new LitematicaCategoryBar.Choice("", Component.translatable("config.spark_fix.litematica_category_all")));
        for (var category : categories) {
            if (category.group().equals("litematica") && !hiddenModules.contains(moduleByGroup.getOrDefault(category.group(), category.group()))) {
                choices.add(new LitematicaCategoryBar.Choice(category.id(), category.label()));
            }
        }
        String selected = favorite ? favoritesCategory : allCategory;
        if (!selected.isEmpty() && choices.stream().noneMatch(choice -> choice.id().equals(selected))) {
            categories.stream().filter(category -> category.id().equals(selected)).findFirst().ifPresent(category ->
                    choices.add(new LitematicaCategoryBar.Choice(category.id(), category.label())));
            if (selected.endsWith(":ALL")) choices.add(new LitematicaCategoryBar.Choice(selected,
                    Component.literal(selected.substring(0, selected.length() - 4))));
        }
        return choices;
    }

    private String groupingKey(Option option) {
        var category = categoryByOption.get(option.object());
        return category == null ? option.group() + ":OTHER" : category.id();
    }

    /** Repair legacy cross-category drops without discarding the order inside each category. */
    private void joinSettingsGroups() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Option option : ordered(discoverOptions(), settingsOrder)) {
            grouped.computeIfAbsent(groupingKey(option), ignored -> new ArrayList<>()).add(option.key());
        }
        settingsOrder.clear();
        for (List<String> keys : grouped.values()) settingsOrder.addAll(keys);
    }

    private void selectCategory(boolean favorite, String id) {
        if (favorite) favoritesCategory = id;
        else allCategory = id;
        setFocused(favorite ? favoritesCategories : allCategories);
        filteredColumns.keySet().removeIf(key -> key.startsWith(favorite ? "favorites." : "settings."));
        clearAddonMenu();
        layoutDirty = true;
    }

    private void clearAddonMenu() {
        for (Button button : addonCategoryButtons) removeWidget(button);
        addonCategoryButtons.clear();
        if (addonMenuOwner != null) addonMenuOwner.setCategoryMenuOpen(false);
        addonMenuOwner = null;
        addonMenuBounds = null;
    }

    private void updateAddonMenu(double mouseX, double mouseY) {
        if (draggingKey != null || minecraft.gui.screen() != this) { clearAddonMenu(); return; }
        for (LitematicaModuleButton button : moduleButtons) {
            if (!button.shown() || button.module().groupIds().contains("litematica") || !button.isMouseOver(mouseX, mouseY)) continue;
            if (addonMenuOwner == button) return;
            clearAddonMenu();
            List<LitematicaCategoryBar.Choice> choices = new ArrayList<>();
            choices.add(new LitematicaCategoryBar.Choice(button.module().groupIds().getFirst() + ":ALL",
                    Component.translatable("config.spark_fix.litematica_category_all")));
            for (var category : categories) if (button.module().groupIds().contains(category.group())) {
                choices.add(new LitematicaCategoryBar.Choice(category.id(), category.label()));
            }
            if (choices.size() < 2) return;
            int menuWidth = Math.min(180, Math.max(90, choices.stream().mapToInt(choice -> font.width(choice.label()) + 18).max().orElse(90)));
            int x = Math.min(button.getX(), width - menuWidth - 8);
            int y = button.getBottom() + 2;
            addonMenuOwner = button;
            addonMenuOwner.setCategoryMenuOpen(true);
            for (var choice : choices) {
                Button entry = Button.builder(choice.label(), ignored -> {
                    selectCategory(false, choice.id());
                    if (SparkFixConfig.litematicaFilterFavorites()) selectCategory(true, choice.id());
                }).bounds(x + 4, y + 4 + addonCategoryButtons.size() * 23, menuWidth, 20).build();
                entry.active = !choice.id().equals(allCategory);
                addonCategoryButtons.add(addWidget(entry));
            }
            addonMenuBounds = new ScreenRectangle(x, y, menuWidth + 8, choices.size() * 23 + 5);
            return;
        }
        if (addonMenuBounds != null && mouseX >= addonMenuBounds.left() && mouseX < addonMenuBounds.right()
                && mouseY >= addonMenuBounds.top() - 3 && mouseY < addonMenuBounds.bottom()) return;
        clearAddonMenu();
    }

    @Override public void mouseMoved(double x, double y) { updateAddonMenu(x, y); super.mouseMoved(x, y); }

    private void rebuildLayout() {
        layoutDirty = false;
        AbstractWidget focusedValue = getFocused() instanceof ScaledEditor scaled ? scaled.editor : null;
        boolean focusFavorites = favoritesSearchBox != null && getFocused() == favoritesSearchBox;
        boolean focusAll = allSettingsSearchBox != null && getFocused() == allSettingsSearchBox;
        boolean focusFavoriteCategory = favoritesCategories != null && getFocused() == favoritesCategories;
        boolean focusAllCategory = allCategories != null && getFocused() == allCategories;
        boolean keepSearchPinned = ((focusAll || focusAllCategory) && othersPinned())
                || ((focusFavorites || focusFavoriteCategory) && favoritesPinned());
        int searchCursor = focusFavorites ? favoritesSearchBox.getCursorPosition()
                : focusAll ? allSettingsSearchBox.getCursorPosition() : 0;
        if (contentWidgets.contains(getFocused())) setFocused(null);
        for (AbstractWidget widget : contentWidgets) removeWidget(widget);
        contentWidgets.clear();
        cards.clear();
        emptyStates.clear();
        groupDividers.clear();
        favoritesSearchBox = null;
        allSettingsSearchBox = null;
        favoritesHeading = null;
        othersHeading = null;
        favoritesCategories = null;
        allCategories = null;
        int y = contentTop - scroll;
        Set<String> favorites = new HashSet<>(favoriteOrder);
        y = section(Component.translatable("config.spark_fix.litematica_favorites"), y, true);
        List<Option> matching = options(favoritesQuery, true);
        y = layoutRegion(ordered(matching.stream().filter(option -> favorites.contains(option.key())).toList(), favoriteOrder), true, y);
        y += 12;
        y = section(Component.translatable("config.spark_fix.litematica_all_settings"), y, false);
        matching = options(allSettingsQuery, false);
        y = layoutRegion(ordered(matching.stream().filter(option -> !favorites.contains(option.key())).toList(), settingsOrder), false, y);
        contentHeight = Math.max(0, y - contentTop + scroll);
        if (keepSearchPinned) {
            int sectionScroll = (focusAll || focusAllCategory ? othersHeaderY : favoritesHeaderY) - contentTop + scroll;
            contentHeight = Math.max(contentHeight, sectionScroll + contentBottom - contentTop);
            scrollTo(sectionScroll);
        } else scrollTo(scroll);
        EditBox focusedSearch = focusFavorites ? favoritesSearchBox : focusAll ? allSettingsSearchBox : null;
        if (focusedSearch != null && focusedSearch.visible) {
            setFocused(focusedSearch);
            focusedSearch.moveCursorTo(searchCursor, false);
        } else if (focusAllCategory || focusFavoriteCategory) {
            setFocused(focusAllCategory ? allCategories : favoritesCategories);
        } else if (focusedValue != null) {
            for (AbstractWidget widget : contentWidgets) {
                if (widget instanceof ScaledEditor scaled && scaled.editor == focusedValue) {
                    setFocused(scaled);
                    break;
                }
            }
        }
        hideDraggedControls();
    }

    private static List<Option> ordered(List<Option> visible, List<String> order) {
        Map<String, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < order.size(); i++) indices.put(order.get(i), i);
        List<Option> result = new ArrayList<>(visible);
        result.sort(Comparator.comparingInt(option -> indices.getOrDefault(option.key(), Integer.MAX_VALUE)));
        return result;
    }

    private boolean filtersModules(boolean favorite) {
        return !hiddenModules.isEmpty() && (!favorite || SparkFixConfig.litematicaFilterFavorites());
    }

    private boolean regionFiltered(boolean favorite) {
        return filtersModules(favorite) || !(favorite ? favoritesQuery : allSettingsQuery).isBlank()
                || !(favorite ? favoritesCategory : allCategory).isBlank();
    }

    private List<Option> options(String query, boolean favorite) {
        List<Option> available = discoverOptions().stream().filter(option ->
                !(option.object() instanceof LitematicaRenderLayerSettings.Setting setting) || setting.visible()).toList();
        if (filtersModules(favorite)) available = available.stream().filter(option ->
                !hiddenModules.contains(moduleByGroup.getOrDefault(option.group(), option.group()))).toList();
        String selectedCategory = favorite ? favoritesCategory : allCategory;
        if (!selectedCategory.isEmpty()) {
            var category = categories.stream().filter(entry -> entry.id().equals(selectedCategory)).findFirst().orElse(null);
            if (category != null) available = available.stream().filter(option -> category.options().contains(option.object())).toList();
            else if (selectedCategory.endsWith(":ALL")) {
                String group = selectedCategory.substring(0, selectedCategory.length() - 4);
                available = available.stream().filter(option -> option.group().equals(group)).toList();
            }
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isBlank()) return available;
        return available.stream().filter(option -> optionSearchText.get(option.key()).contains(normalizedQuery)).toList();
    }

    private List<Option> discoverOptions() {
        if (cachedOptions != null) return cachedOptions;
        List<Option> result = new ArrayList<>();
        optionSearchText.clear();
        originalOptionNames.clear();
        optionComments.clear();
        for (var group : groups) {
            int index = 0;
            for (Object object : group.options()) {
                String name = LitematicaConfigDiscovery.name(object);
                if (name.isBlank()) name = object.getClass().getSimpleName();
                Component commentComponent = LitematicaConfigDiscovery.commentComponent(object);
                String comment = commentComponent.getString();
                String stableName = LitematicaConfigDiscovery.stableName(object);
                if (stableName.isBlank()) stableName = name;
                String key = group.id() + "::" + index++ + "::" + stableName;
                List<String> customNames = litematicaNames.get(key);
                String displayName = customNames == null || customNames.isEmpty() ? name : customNames.get(0);
                result.add(new Option(key, group.id(), object, displayName, comment));
                originalOptionNames.put(key, name);
                optionComments.put(key, commentComponent);
                optionSearchText.put(key, (displayName + " " + name + " " + stableName + " "
                        + String.join(" ", litematicaNames.getOrDefault(key, List.of())) + " "
                        + String.join(" ", litematicaAliases.getOrDefault(key, List.of())) + " "
                        + comment + " " + group.id()).toLowerCase(Locale.ROOT));
            }
        }
        cachedOptions = List.copyOf(result);
        return cachedOptions;
    }

    private int section(Component label, int y, boolean favorites) {
        if (favorites) favoritesHeaderY = y;
        else othersHeaderY = y;
        boolean hasCategories = !categories.isEmpty();
        int headingWidth = Math.min(font.width(label), Math.max(24, hasCategories ? panelWidth / 4 : panelWidth - 126));
        StringWidget heading = new StringWidget(panelLeft + 18, y + 2, headingWidth, 20, label, font);
        heading.setMaxWidth(headingWidth);
        if (favorites) favoritesHeading = heading;
        else othersHeading = heading;
        contentWidgets.add(heading);
        addWidget(heading);
        int searchX = panelLeft + 18 + headingWidth + 10;
        int remainingWidth = panelLeft + panelWidth - 18 - searchX;
        int searchWidth = hasCategories ? Math.min(150, Math.max(60, remainingWidth / 3)) : remainingWidth;
        EditBox search = new EditBox(font, searchX, y + 2, searchWidth, 20,
                Component.translatable("config.spark_fix.litematica_search")) {
            @Override public boolean isMouseOver(double mouseX, double mouseY) {
                return mouseY >= contentTop && mouseY < contentBottom && super.isMouseOver(mouseX, mouseY);
            }
        };
        search.setMaxLength(128);
        search.setHint(Component.translatable("config.spark_fix.litematica_search"));
        search.setValue(favorites ? favoritesQuery : allSettingsQuery);
        search.setResponder(value -> {
            if (favorites) favoritesQuery = value;
            else allSettingsQuery = value;
            filteredColumns.keySet().removeIf(key -> key.startsWith(favorites ? "favorites." : "settings."));
            layoutDirty = true;
        });
        if (favorites) favoritesSearchBox = search;
        else allSettingsSearchBox = search;
        contentWidgets.add(search);
        addWidget(search);
        if (hasCategories) {
            var bar = new LitematicaCategoryBar(searchX + searchWidth + 6, y + 2, Math.max(24, remainingWidth - searchWidth - 6),
                    categoryChoices(favorites), favorites ? favoritesCategory : allCategory, font,
                    () -> new ScreenRectangle(panelLeft + 12, contentTop, panelWidth - 24, contentBottom - contentTop),
                    id -> selectCategory(favorites, id));
            if (favorites) favoritesCategories = bar;
            else allCategories = bar;
            contentWidgets.add(bar);
            addWidget(bar);
        }
        return y + SECTION_HEIGHT;
    }

    private int layoutRegion(List<Option> options, boolean favorite, int y) {
        if (options.isEmpty()) {
            String key = (favorite ? favoritesQuery : allSettingsQuery).isBlank()
                    && (favorite ? favoritesCategory : allCategory).isBlank()
                    ? filtersModules(favorite) && (!favorite || !favoriteOrder.isEmpty())
                            ? "config.spark_fix.litematica_modules_hidden"
                            : favorite ? "config.spark_fix.litematica_favorites_empty"
                            : "config.spark_fix.litematica_others_empty"
                    : "config.spark_fix.litematica_no_matches";
            emptyStates.add(new EmptyState(Component.translatable(key), y + 2));
            return y + font.lineHeight + 6;
        }
        int compactness = (int) Math.round(SparkFixConfig.litematicaCompactness());
        int availableWidth = panelWidth - 32;
        int gap = Math.max(4, CARD_GAP - compactness / 2);
        // Each level explicitly selects one through five columns. This keeps
        // level five deterministic instead of depending on panel width.
        int columns = Math.min(MAX_COLUMNS, Math.min(Math.max(1, compactness), options.size()));
        // Fill the complete row. The final column receives the remainder so
        // wide screens do not leave a large unused strip on the right.
        int cardWidth = Math.max(1, (availableWidth - gap * (columns - 1)) / columns);
        int left = panelLeft + 16;
        int[] columnBottom = new int[columns];
        java.util.Arrays.fill(columnBottom, y);
        Map<String, List<String>> placements = regionFiltered(favorite) ? filteredColumns : layoutColumns;
        Map<String, Integer> assigned = new LinkedHashMap<>();
        for (String grouping : favorite ? List.of("") : options.stream().map(this::groupingKey).distinct().toList()) {
            for (int column = 0; column < columns; column++) {
                for (String key : placements.getOrDefault(columnKey(favorite, columns, column, grouping), List.of())) {
                    assigned.putIfAbsent(key, column);
                }
            }
        }
        String previousGroup = null;
        GroupDivider divider = null;
        for (Option option : options) {
            var category = categoryByOption.get(option.object());
            String grouping = groupingKey(option);
            if (!favorite && !grouping.equals(previousGroup)) {
                int top = java.util.Arrays.stream(columnBottom).max().orElse(y);
                if (divider != null) divider.bottom = top;
                Component label = Component.literal(moduleNames.getOrDefault(option.group(), option.group()));
                if (category != null) label = label.copy().append(" · ").append(category.label());
                var lines = font.split(label, Math.max(1, availableWidth - 16));
                int headingHeight = Math.max(1, lines.size()) * font.lineHeight + 10;
                divider = new GroupDivider(grouping, lines, top, headingHeight);
                groupDividers.add(divider);
                java.util.Arrays.fill(columnBottom, top + headingHeight);
                previousGroup = grouping;
            }
            Integer column = assigned.get(option.key());
            if (column == null) {
                column = 0;
                for (int i = 1; i < columns; i++) {
                    if (columnBottom[i] < columnBottom[column]) column = i;
                }
                placements.computeIfAbsent(columnKey(favorite, columns, column, grouping), ignored -> new ArrayList<>())
                        .add(option.key());
            }
            int columnWidth = column == columns - 1 ? availableWidth - column * (cardWidth + gap) : cardWidth;
            Card card = measureCard(option, left + column * (cardWidth + gap), columnBottom[column],
                    columnWidth, favorite, 1.0f);
            card.column = column;
            card.columns = columns;
            cards.add(card);
            if ((card.y + card.height > contentTop && card.y < contentBottom)
                    || card.option.key().equals(draggingKey)) addEditor(card);
            addAliasButton(card);
            addInfoButton(card);
            columnBottom[column] += card.height + gap;
        }
        int bottom = java.util.Arrays.stream(columnBottom).max().orElse(y);
        if (divider != null) divider.bottom = bottom;
        return bottom - gap;
    }

    private static String columnKey(boolean favorite, int columns, int column, String grouping) {
        // Legacy page-wide positions mix neighboring categories. Keep those records intact,
        // but initialize independent category layouts from the retained order on first use.
        return (favorite ? "favorites." : "settings.") + columns + "." + column
                + (favorite ? "" : ".group." + grouping);
    }

    private void assignColumn(Map<String, List<String>> placements, Card card, int column) {
        for (int index = 0; index < card.columns; index++) {
            List<String> keys = placements.get(columnKey(card.favorite, card.columns, index, groupingKey(card.option)));
            if (keys != null) keys.remove(card.option.key());
        }
        placements.computeIfAbsent(columnKey(card.favorite, card.columns, column, groupingKey(card.option)), ignored -> new ArrayList<>())
                .add(card.option.key());
    }

    private Card measureCard(Option option, int x, int y, int width, boolean favorite, float scale) {
        int logicalWidth = Math.max(1, (int) Math.round(width / scale));
        int textWidth = Math.max(1, logicalWidth - 24);
        boolean narrow = logicalWidth < 116;
        int titleWidth = titleTextWidth(logicalWidth, favorite);
        var lines = font.split(Component.literal(option.name()), titleWidth);
        int lineCount = Math.max(1, lines.size());
        int textHeight = lineCount * font.lineHeight;
        int aliasX = narrow ? 12 : 12 + (lines.isEmpty() ? 0 : font.width(lines.getLast())) + 5;
        int aliasY = narrow ? 14 + textHeight + 3 : 12 + (lineCount - 1) * font.lineHeight;
        boolean stackInfo = aliasX + 40 > logicalWidth - (favorite ? 16 : 8);
        int infoX = stackInfo ? aliasX : aliasX + 22;
        int infoY = stackInfo ? aliasY + 22 : aliasY;
        boolean hasEditor = LitematicaConfigDiscovery.isBoolean(option.object())
                || LitematicaConfigDiscovery.isOptionList(option.object())
                || option.object() instanceof LitematicaRenderLayerSettings.PositionAction
                || LitematicaConfigDiscovery.editable(option.object());
        boolean hasHotkey = LitematicaConfigDiscovery.hasHotkey(option.object());
        int toolsWidth = hasHotkey ? 42 : hasValueReset(option.object()) ? 18 : 0;
        int headerToolsX = toolsWidth > 0 && infoX + 20 + toolsWidth <= logicalWidth - (favorite ? 16 : 12)
                ? infoX + 20 : -1;
        boolean toolsInHeader = headerToolsX >= 0;
        int editorHeight = hasHotkey ? (toolsInHeader ? 20 : LitematicaHotkeyWidget.heightFor(textWidth))
                + (LitematicaConfigDiscovery.isBoolean(option.object()) ? 24 : 0)
                : LitematicaConfigDiscovery.isNumeric(option.object())
                        ? LitematicaNumericWidget.heightFor(valueEditorWidth(option.object(), logicalWidth, toolsInHeader))
                : hasEditor ? 20 : 0;
        if (!toolsInHeader && hasValueReset(option.object()) && textWidth < 58
                && !(LitematicaConfigDiscovery.isNumeric(option.object()) && textWidth >= 40)) editorHeight += 20;
        int editorY = Math.max(14 + textHeight, infoY + (hasHotkey && toolsInHeader ? 19 : 18)) + 4;
        int logicalHeight = Math.max(44, editorY + editorHeight + 3);
        return new Card(option, x, y, width, (int) Math.ceil(logicalHeight * scale), favorite,
                titleWidth, List.copyOf(lines), aliasX, aliasY, infoX, infoY, headerToolsX,
                logicalWidth, logicalHeight, editorY, scale);
    }

    private static boolean hasValueReset(Object option) {
        return !LitematicaConfigDiscovery.hasHotkey(option) && LitematicaConfigDiscovery.canReset(option);
    }

    private static int valueEditorWidth(Object option, int cardWidth, boolean toolsInHeader) {
        int width = Math.max(1, cardWidth - 24);
        return !toolsInHeader && hasValueReset(option) && width >= 58 ? width - 22 : width;
    }

    private void addEditor(Card card) {
        Object option = card.option.object();
        boolean hasHotkey = LitematicaConfigDiscovery.hasHotkey(option);
        if (hasHotkey && !LitematicaConfigDiscovery.isBoolean(option)) {
            addHotkeyEditor(card, card.editorY);
            return;
        }
        AbstractWidget editor;
        int editorX;
        boolean toolsInHeader = card.headerToolsX >= 0;
        int editorWidth = valueEditorWidth(option, card.logicalWidth, toolsInHeader);
        boolean inlineReset = !toolsInHeader && hasValueReset(option) && card.logicalWidth - 24 >= 58;
        if (option instanceof LitematicaRenderLayerSettings.PositionAction action) {
            editor = Button.builder(Component.translatable("config.spark_fix.litematica_layer_here_button"),
                    button -> action.run()).bounds(0, 0, Math.max(1, card.logicalWidth - 24), 20).build();
            editor.active = action.enabled();
            editorX = 12;
        } else if (LitematicaConfigDiscovery.isBoolean(option)) {
            int buttonWidth = Math.min(62, editorWidth);
            editor = Button.builder(booleanLabel(option), button -> {
                LitematicaConfigDiscovery.setValue(option, Boolean.toString(!LitematicaConfigDiscovery.booleanValue(option)));
                button.setMessage(booleanLabel(option));
            }).bounds(0, 0, buttonWidth, 20).build();
            editorX = (card.logicalWidth - buttonWidth - (inlineReset ? 22 : 0)) / 2;
        } else if (LitematicaConfigDiscovery.isOptionList(option)) {
            editor = new LitematicaOptionListButton(option, editorWidth);
            editorX = 12;
        } else if (LitematicaConfigDiscovery.isStringList(option)) {
            editor = new LitematicaStringListButton(font, option, editorWidth,
                    () -> openChildScreen(new LitematicaStringListEditorScreen(
                            this, option, card.option.group(), card.option.name())));
            editorX = 12;
        } else if (LitematicaConfigDiscovery.isColor(option)) {
            editor = new LitematicaColorButton(font, option, editorWidth,
                    () -> openChildScreen(new LitematicaColorEditorScreen(this, option, card.option.name())));
            editorX = 12;
        } else if (LitematicaConfigDiscovery.isNumeric(option)) {
            var numeric = numericInputs.computeIfAbsent(card.option.key(), ignored -> new LitematicaNumericWidget(
                    font, option, card.option.name(), editorWidth));
            numeric.setWidth(editorWidth);
            editor = numeric;
            editorX = 12;
        } else if (LitematicaConfigDiscovery.editable(option)) {
            EditBox input = valueInputs.get(card.option.key());
            if (input == null) {
                input = new EditBox(font, 0, 0, editorWidth, 20,
                        Component.literal(card.option.name()));
                input.setMaxLength(2048);
                input.setValue(LitematicaConfigDiscovery.value(option));
                input.setResponder(value -> LitematicaConfigDiscovery.setValue(option, value));
                valueInputs.put(card.option.key(), input);
            } else {
                input.setWidth(editorWidth);
                input.setMessage(Component.literal(card.option.name()));
                input.setCursorPosition(input.getCursorPosition());
            }
            editor = input;
            editorX = 12;
        } else return;
        ScaledEditor scaled = new ScaledEditor(card, editor, card.x + Math.round(editorX * card.scale),
                card.y + Math.round(card.editorY * card.scale), card.scale);
        card.controls.add(scaled);
        contentWidgets.add(scaled);
        addWidget(scaled);
        if (hasValueReset(option)) {
            int resetX = inlineReset ? editorX + editor.getWidth() + 4 : (card.logicalWidth - 18) / 2;
            int resetY = inlineReset ? card.editorY + 1 : card.editorY + editor.getHeight() + 2;
            if (toolsInHeader) {
                resetX = card.headerToolsX;
                resetY = card.infoY;
            } else if (!inlineReset && LitematicaConfigDiscovery.isNumeric(option) && card.logicalWidth - 24 >= 40) {
                resetX = card.logicalWidth - 30;
                resetY = card.editorY + 24;
            }
            var reset = new SettingsIconButton(0, 0, 18, SettingsIconButton.Icon.RESET,
                    Component.translatable("config.spark_fix.litematica_option_reset", card.option.name()), () -> {
                        if (!LitematicaConfigDiscovery.resetToDefault(option)) return;
                        valueInputs.remove(card.option.key());
                        numericInputs.remove(card.option.key());
                        layoutDirty = true;
                    }, true) {
                @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int x, int y) { }
            };
            reset.active = LitematicaConfigDiscovery.isModified(option);
            card.resetControl = new ScaledEditor(card, reset, card.x + Math.round(resetX * card.scale),
                    card.y + Math.round(resetY * card.scale), card.scale);
            card.controls.add(card.resetControl);
            contentWidgets.add(card.resetControl);
            addWidget(card.resetControl);
        }
        if (hasHotkey) addHotkeyEditor(card, card.editorY + 24);
    }

    private void addHotkeyEditor(Card card, int editorY) {
        AbstractWidget editor = LitematicaHotkeyWidget.create(card.option.object(),
                card.option.name(), Math.max(1, card.logicalWidth - 24), card.headerToolsX >= 0,
                this, this::openChildScreen, this::refreshHotkeys,
                () -> new ScreenRectangle(panelLeft + 12, cardsViewportTop(), panelWidth - 24,
                        Math.max(0, contentBottom - cardsViewportTop())));
        ScaledEditor scaled = new ScaledEditor(card, editor, card.x + Math.round(12 * card.scale),
                card.y + Math.round(editorY * card.scale), card.scale);
        card.controls.add(scaled);
        contentWidgets.add(scaled);
        addWidget(scaled);
        if (editor instanceof LitematicaHotkeyWidget hotkey && hotkey.tools() != null) {
            ScaledEditor tools = new ScaledEditor(card, hotkey.tools(), card.x + Math.round(card.headerToolsX * card.scale),
                    card.y + Math.round((card.infoY - 1) * card.scale), card.scale);
            card.controls.add(tools);
            contentWidgets.add(tools);
            addWidget(tools);
        }
    }

    private void refreshHotkeys() {
        for (AbstractWidget widget : contentWidgets) {
            if (widget instanceof ScaledEditor scaled) {
                if (scaled.editor instanceof LitematicaHotkeyWidget hotkey) hotkey.refreshDisplay();
                else if (scaled.editor instanceof Button && LitematicaConfigDiscovery.isBoolean(scaled.card.option.object())) {
                    scaled.editor.setMessage(booleanLabel(scaled.card.option.object()));
                }
            }
        }
    }

    private static Component booleanLabel(Object option) {
        // MaLiLib's translations include the original yes/no colours, including
        // any language/resource-pack overrides installed by the player.
        return Component.literal(I18n.get(LitematicaConfigDiscovery.booleanValue(option)
                ? "malilib.gui.button.true" : "malilib.gui.button.false"));
    }

    private void addAliasButton(Card card) {
        card.aliasButton = addCardIcon(card, card.aliasX, card.aliasY, SettingsIconButton.Icon.ADD,
                Component.translatable("config.spark_fix.litematica_alias_edit"),
                () -> openAliasEditor(card.option));
    }

    private void addInfoButton(Card card) {
        card.infoButton = addCardIcon(card, card.infoX, card.infoY, SettingsIconButton.Icon.INFO,
                Component.translatable("config.spark_fix.litematica_option_info", card.option.name()), () -> { });
        Component comment = optionComments.get(card.option.key()).copy();
        if (comment.getString().isBlank()) comment = Component.translatable("config.spark_fix.litematica_option_no_comment");
        card.infoComment = comment;
        card.infoButton.setTooltip(Tooltip.create(comment));
    }

    private List<FormattedCharSequence> infoLines(Card card) {
        int maxWidth = Math.max(1, width - 36);
        int wrapWidth = Math.min(320, maxWidth);
        int visibleLines = infoVisibleLines();
        List<FormattedCharSequence> lines = font.split(card.infoComment, wrapWidth);
        while (lines.size() > visibleLines && wrapWidth < maxWidth) {
            wrapWidth = Math.min(maxWidth, wrapWidth + 24);
            lines = font.split(card.infoComment, wrapWidth);
        }
        return lines;
    }

    private int infoVisibleLines() {
        return Math.max(2, (height - 56) / (font.lineHeight + 2));
    }

    private void showInfoTooltip(GuiGraphicsExtractor graphics, Card card, int mouseX, int mouseY) {
        if (!card.option.key().equals(infoTooltipKey)) {
            infoTooltipKey = card.option.key();
            infoTooltipOffset = 0;
        }
        List<FormattedCharSequence> lines = infoLines(card);
        int visibleLines = infoVisibleLines();
        if (lines.size() <= visibleLines) {
            graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
            return;
        }
        int pageSize = visibleLines - 1;
        infoTooltipOffset = Math.clamp(infoTooltipOffset, 0, lines.size() - pageSize);
        int end = Math.min(lines.size(), infoTooltipOffset + pageSize);
        List<FormattedCharSequence> page = new ArrayList<>(lines.subList(infoTooltipOffset, end));
        page.add(Component.literal((infoTooltipOffset > 0 ? "↑ " : "") + (infoTooltipOffset + 1)
                + "-" + end + "/" + lines.size() + (end < lines.size() ? " ↓" : "")).getVisualOrderText());
        graphics.setTooltipForNextFrame(font, page, mouseX, mouseY);
    }

    private AbstractWidget addCardIcon(Card card, int iconX, int iconY, SettingsIconButton.Icon icon,
                                       Component label, Runnable action) {
        int x = card.x + Math.round(iconX * card.scale);
        int y = card.y + Math.round(iconY * card.scale);
        AbstractWidget button = new SettingsIconButton(x, y, 18, icon, label, action, true) {
            @Override public boolean isMouseOver(double mouseX, double mouseY) {
                return mouseY >= cardsViewportTop() && mouseY < contentBottom
                        && super.isMouseOver(card.x + (mouseX - card.x) / card.displayScale,
                                card.y + (mouseY - card.y) / card.displayScale);
            }

            @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
                if (draggingKey == null && card.motion == null && addonCategoryButtons.isEmpty()
                        && minecraft.gui.screen() == VanillaLitematicaSettingsScreen.this
                        && (isMouseOver(mouseX, mouseY) || isFocused() && getY() >= cardsViewportTop() && getBottom() <= contentBottom)) {
                    if (icon == SettingsIconButton.Icon.INFO) showInfoTooltip(graphics, card, mouseX, mouseY);
                    else super.extractTooltipForNextRenderPass(graphics, mouseX, mouseY);
                }
            }

            @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                               float partialTick) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(card.x * (1 - card.displayScale), card.y * (1 - card.displayScale));
                graphics.pose().scale(card.displayScale, card.displayScale);
                super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
                graphics.pose().popMatrix();
            }
        };
        contentWidgets.add(button);
        addWidget(button);
        return button;
    }

    private void openAliasEditor(Option option) {
        if (customAliases.refreshDeletedFile(litematicaAliases)) layoutDirty = true;
        List<String> names = new ArrayList<>(litematicaNames.getOrDefault(option.key(), List.of(option.name())));
        if (names.isEmpty()) names.add(option.name());
        List<String> aliases = new ArrayList<>(litematicaAliases.getOrDefault(option.key(), List.of()));
        LitematicaAliasEditorScreen editor = new LitematicaAliasEditorScreen(this, option, names, aliases, updatedNames -> {
            customAliases.recordEdit(option.key(), aliases, updatedNames.aliases());
            litematicaNames.put(option.key(), updatedNames.mainNames());
            if (updatedNames.aliases().isEmpty()) litematicaAliases.remove(option.key());
            else litematicaAliases.put(option.key(), updatedNames.aliases());
            customAliases.refreshDeletedFile(litematicaAliases);
            cachedOptions = null;
            layoutDirty = true;
        });
        openChildScreen(editor);
    }

    private void openChildScreen(Screen editor) {
        scrollTo(scroll);
        setFocused(null);
        openingChildScreen = true;
        try {
            minecraft.setScreenAndShow(editor);
        } finally {
            openingChildScreen = false;
        }
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - (contentBottom - contentTop)), scroll));
    }

    private boolean othersPinned() {
        return allSettingsSearchBox != null && othersHeaderY <= contentTop;
    }

    private boolean favoritesPinned() {
        return favoritesSearchBox != null && favoritesHeaderY <= contentTop && othersHeaderY > contentTop;
    }

    private int favoritesDisplayY() {
        return Math.min(Math.max(contentTop, favoritesHeaderY), othersHeaderY - SECTION_HEIGHT);
    }

    private int cardsViewportTop() {
        if (othersPinned()) return contentTop + SECTION_HEIGHT;
        return favoritesPinned() ? Math.max(contentTop, favoritesDisplayY() + SECTION_HEIGHT) : contentTop;
    }

    @Override
    public void tick() {
        super.tick();
        if (layoutDirty) rebuildLayout();
        updateScrollAnimation(System.nanoTime());
        updateCardMotions();
        if (draggingKey != null && isDragging()) {
            int edgeScroll = dragMouseY < cardsViewportTop() + 16 ? -8 : dragMouseY > contentBottom - 16 ? 8 : 0;
            if (edgeScroll != 0) {
                smoothScrollBy(edgeScroll);
            }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (layoutDirty) rebuildLayout();
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // The bottom corners extend beyond the viewport: the usable list reaches
        // the screen edge instead of leaving an empty rounded footer.
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 12, panelWidth, height, 12, IntegrationSettingsStyle.PANEL);
        graphics.centeredText(font, Component.literal(font.plainSubstrByWidth(title.getString(), titleWidthLimit)),
                width / 2, 17, 0xFFFFFFFF);
        graphics.enableScissor(panelLeft + 12, cardsViewportTop(), panelLeft + panelWidth - 12, contentBottom);
        graphics.fill(panelLeft + 18, othersHeaderY - 7,
                panelLeft + panelWidth - 18, othersHeaderY - 6, 0x88B7D2FB);
        for (Card card : cards) {
            if (!isCardVisible(card)) continue;
            if (card.option.key().equals(draggingKey)) {
                IntegrationSettingsStyle.roundedRect(graphics, card.x, card.y, card.width, card.height, 8, 0x16000000);
                IntegrationSettingsStyle.roundedRect(graphics, card.x + 6, card.y + 2,
                        card.width - 12, 5, 6, 0x80FFFFFF);
                continue;
            }
            if (card.motion != null) continue;
            boolean hovered = mouseX >= card.x && mouseX < card.x + card.width
                    && mouseY >= card.y && mouseY < card.y + card.height;
            drawCard(graphics, card, card.x, card.y, card.scale * card.displayScale, hovered, false);
        }
        for (EmptyState state : emptyStates) {
            if (state.y + font.lineHeight <= cardsViewportTop() || state.y >= contentBottom) continue;
            graphics.text(font, state.message, panelLeft + 20, state.y, IntegrationSettingsStyle.MUTED, false);
        }
        for (GroupDivider divider : groupDividers) {
            if (divider.y + divider.height < cardsViewportTop() || divider.y >= contentBottom) continue;
            int x = panelLeft + 16;
            IntegrationSettingsStyle.roundedRect(graphics, x, divider.y + 1, panelWidth - 32,
                    divider.height - 4, 6, 0x50203550);
            for (int line = 0; line < divider.lines.size(); line++) {
                graphics.text(font, divider.lines.get(line), x + 8, divider.y + 4 + line * font.lineHeight, 0xFFBBD6FF);
            }
            graphics.fill(x + 6, divider.y + divider.height - 3,
                    x + panelWidth - 38, divider.y + divider.height - 2, 0x7087B1F9);
        }
        drawDropIndicator(graphics);
        graphics.disableScissor();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (layoutDirty) rebuildLayout();
        updateScrollAnimation(System.nanoTime());
        updateCardMotions();
        updateAddonMenu(mouseX, mouseY);
        int menuMouseX = mouseX;
        int menuMouseY = mouseY;
        if (addonMenuBounds != null && mouseX >= addonMenuBounds.left() && mouseX < addonMenuBounds.right()
                && mouseY >= addonMenuBounds.top() && mouseY < addonMenuBounds.bottom()) mouseX = mouseY = -10000;
        boolean backdrop = minecraft.gui.screen() != this;
        if (backdrop) {
            // MaLiLib's dialog only asks its parent for the foreground pass.
            // Supply the world's blur/panorama too, with no hover behind the dialog.
            mouseX = mouseY = -10000;
            extractBackground(graphics, mouseX, mouseY, partialTick);
        }
        drawPanel(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(panelLeft + 12, cardsViewportTop(), panelLeft + panelWidth - 12, contentBottom);
        for (Card card : cards) {
            if (card.motion == null && !card.option.key().equals(draggingKey)) {
                drawCardControls(graphics, card, mouseX, mouseY, partialTick);
            }
        }
        for (Card card : cards) {
            if (card.motion == null || !isCardVisible(card)) continue;
            graphics.nextStratum();
            drawCard(graphics, card, card.x, card.y, card.scale * card.displayScale, false, false);
            drawCardControls(graphics, card, -10000, -10000, partialTick);
        }
        Card dragged = findCard(draggingKey);
        if (dragged != null) {
            graphics.nextStratum();
            drawDraggedCard(graphics, dragged, partialTick);
        }
        graphics.disableScissor();
        graphics.nextStratum();
        graphics.enableScissor(panelLeft + 12, contentTop, panelLeft + panelWidth - 12, contentBottom);
        drawSection(graphics, favoritesHeading, favoritesSearchBox, favoritesDisplayY(), favoritesPinned(), mouseX, mouseY, partialTick);
        drawSection(graphics, othersHeading, allSettingsSearchBox, Math.max(contentTop, othersHeaderY), othersPinned(), mouseX, mouseY, partialTick);
        graphics.disableScissor();
        if (draggingKey == null && addonCategoryButtons.isEmpty()) {
            for (AbstractWidget widget : contentWidgets) {
                if (widget instanceof ScaledEditor scaled && scaled.visible && scaled.isMouseOver(mouseX, mouseY)
                        && scaled.card.motion == null) {
                    graphics.nextStratum();
                    if (scaled.editor instanceof LitematicaHotkeyWidget hotkey) {
                        hotkey.renderHover(graphics, scaled.getX(), scaled.getY(), mouseX, mouseY);
                    } else if (scaled.editor instanceof LitematicaHotkeyWidget.Tools tools) {
                        tools.renderHover(graphics, scaled.getX(), scaled.getY(), mouseX, mouseY);
                    } else if (scaled.editor instanceof LitematicaOptionListButton cycling) {
                        graphics.setTooltipForNextFrame(font, cycling.hoverText(), mouseX, mouseY);
                    } else if (scaled.editor instanceof LitematicaNumericWidget numeric) {
                        Component text = numeric.hoverText((mouseX - scaled.getX()) / scaled.scale, (mouseY - scaled.getY()) / scaled.scale);
                        if (text != null) graphics.setTooltipForNextFrame(font, text, mouseX, mouseY);
                    } else if (scaled == scaled.card.resetControl) {
                        graphics.setTooltipForNextFrame(font, scaled.getMessage(), mouseX, mouseY);
                    }
                }
            }
        }
        if (addonMenuBounds != null) {
            graphics.nextStratum();
            IntegrationSettingsStyle.roundedRect(graphics, addonMenuBounds.left(), addonMenuBounds.top(),
                    addonMenuBounds.width(), addonMenuBounds.height(), 6, 0xE8253D5E);
            for (Button entry : addonCategoryButtons) entry.extractRenderState(graphics, menuMouseX, menuMouseY, partialTick);
        }
    }

    private void drawCardControls(GuiGraphicsExtractor graphics, Card card, int mouseX, int mouseY, float partialTick) {
        if (!isCardVisible(card)) return;
        if (card.resetControl != null) card.resetControl.editor.active = LitematicaConfigDiscovery.isModified(card.option.object());
        for (ScaledEditor control : card.controls) control.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (card.aliasButton != null) card.aliasButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (card.infoButton != null) card.infoButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private boolean isCardVisible(Card card) {
        return card.y < contentBottom && card.y + card.height * card.displayScale > cardsViewportTop();
    }

    private void drawSection(GuiGraphicsExtractor graphics, StringWidget heading, EditBox search, int y,
                             boolean pinned, int mouseX, int mouseY, float partialTick) {
        if (heading == null || y + SECTION_HEIGHT <= contentTop || y >= contentBottom) return;
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft + 12, y - 2,
                panelWidth - 24, SECTION_HEIGHT, 6, pinned ? 0x7023344D : 0x360F314D);
        if (pinned) graphics.fill(panelLeft + 18, y + SECTION_HEIGHT - 2,
                panelLeft + panelWidth - 18, y + SECTION_HEIGHT - 1, 0x8887B1F9);
        heading.extractRenderState(graphics, mouseX, mouseY, partialTick);
        search.extractRenderState(graphics, mouseX, mouseY, partialTick);
        var bar = heading == favoritesHeading ? favoritesCategories : allCategories;
        if (bar != null) bar.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCard(GuiGraphicsExtractor graphics, Card card, int x, int y, float scale,
                          boolean hovered, boolean dragged) {
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(scale, scale);
        if (dragged) {
            IntegrationSettingsStyle.roundedRect(graphics, 2, 4, card.logicalWidth, card.logicalHeight, 8, 0x50000000);
        }
        int fill = dragged
                ? IntegrationSettingsStyle.blend(IntegrationSettingsStyle.MODULE_CARD_HOVER, 0x70BBD9FF, dragLift)
                : (hovered ? IntegrationSettingsStyle.MODULE_CARD_HOVER : IntegrationSettingsStyle.MODULE_CARD);
        IntegrationSettingsStyle.roundedRect(graphics, 0, 0, card.logicalWidth, card.logicalHeight, 8, fill);
        if (dragged) {
            IntegrationSettingsStyle.moduleToggleOutline(graphics, 0, 0, card.logicalWidth, card.logicalHeight, dragLift);
        }
        IntegrationSettingsStyle.roundedRect(graphics, 8, 3, card.logicalWidth - 16, 6, 6, 0xFFFFFFFF);
        if (card.favorite) graphics.text(font, "*", card.logicalWidth - 14,
                card.aliasY + 4, 0xFFFFD782, false);
        for (int line = 0; line < card.titleLines.size(); line++) {
            graphics.text(font, card.titleLines.get(line), 12, 14 + line * font.lineHeight, 0xFFFFFFFF);
        }
        graphics.pose().popMatrix();
    }

    private void drawDraggedCard(GuiGraphicsExtractor graphics, Card card, float partialTick) {
        CardPose pose = dragPose(card);
        float shrink = pose.scale;
        int x = Math.round(pose.x);
        int y = Math.round(pose.y);
        drawCard(graphics, card, x, y, card.scale * shrink, true, true);
        // Keep every editor in the lifted preview, including input text.
        for (ScaledEditor editor : card.controls) {
            editor.extractAt(graphics, x + (editor.getX() - card.x) * shrink,
                    y + (editor.getY() - card.y) * shrink, card.scale * shrink,
                    -10000, -10000, partialTick);
        }
        for (AbstractWidget icon : new AbstractWidget[]{card.aliasButton, card.infoButton}) {
            if (icon == null) continue;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x - card.x * shrink, y - card.y * shrink);
            graphics.pose().scale(shrink, shrink);
            icon.visible = true;
            icon.extractRenderState(graphics, -10000, -10000, partialTick);
            icon.visible = false;
            graphics.pose().popMatrix();
        }
    }

    private CardPose dragPose(Card card) {
        float elapsed = Math.clamp((System.nanoTime() - dragStartedAt) / 160_000_000f, 0f, 1f);
        dragLift = elapsed * elapsed * (3f - 2f * elapsed);
        float fit = Math.min(0.72f, Math.max(1, contentBottom - cardsViewportTop() - 8) / (float) card.height);
        float shrink = 1f + (fit - 1f) * dragLift;
        int previewWidth = (int) Math.ceil(card.width * shrink);
        int previewHeight = (int) Math.ceil(card.height * shrink);
        int x = clamp((int) Math.round(dragMouseX - dragOffsetX * shrink), panelLeft + 16,
                panelLeft + panelWidth - 16 - previewWidth);
        int y = clamp((int) Math.round(dragMouseY - dragOffsetY * shrink - dragLift * 4f), cardsViewportTop(),
                Math.max(cardsViewportTop(), contentBottom - previewHeight));
        return new CardPose(x, y, shrink);
    }

    private static int titleTextWidth(int width, boolean favorite) {
        int reserved = width < 116 ? 24 : favorite ? 80 : 68;
        return Math.max(1, width - reserved);
    }

    private void drawDropIndicator(GuiGraphicsExtractor graphics) {
        if (dropTarget == null) return;
        int x = dropTarget.x;
        int y = dropTarget.y;
        graphics.fill(x + 4, y, x + dropTarget.width - 4, y + 2, 0xFFFFFFFF);
        graphics.fill(x + 4, y - 2, x + 6, y + 4, 0xDDEAF6FF);
        graphics.fill(x + dropTarget.width - 6, y - 2, x + dropTarget.width - 4, y + 4, 0xDDEAF6FF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (layoutDirty) rebuildLayout();
        // Stop at the last visible position so a click cannot chase a moving input or thumb.
        scrollTo(scroll);
        updateCardMotions();
        if (draggingKey != null) return true;
        if (addonMenuBounds != null && click.x() >= addonMenuBounds.left() && click.x() < addonMenuBounds.right()
                && click.y() >= addonMenuBounds.top() && click.y() < addonMenuBounds.bottom()) {
            for (Button entry : List.copyOf(addonCategoryButtons)) if (entry.mouseClicked(click, doubled)) return true;
            return true;
        }
        clearAddonMenu();
        // Native key recording and the advanced icon own their mouse buttons.
        // In particular, right-click resets advanced settings instead of starring the card.
        for (AbstractWidget widget : contentWidgets) {
            if (widget instanceof ScaledEditor scaled
                    && (scaled.editor instanceof LitematicaHotkeyWidget || scaled.editor instanceof LitematicaHotkeyWidget.Tools
                            || scaled.editor instanceof LitematicaOptionListButton)
                    && scaled.mouseClicked(click, doubled)) {
                if (minecraft.gui.screen() == this) setFocused(scaled);
                return true;
            }
        }
        if (getFocused() instanceof ScaledEditor scaled && scaled.editor instanceof LitematicaHotkeyWidget hotkey) {
            hotkey.finishCapture();
        }
        Card clickedCard = cardAt(click.x(), click.y());
        if (clickedCard != null && clickedCard.resetControl != null
                && clickedCard.resetControl.isMouseOver(click.x(), click.y())) {
            ScaledEditor reset = clickedCard.resetControl;
            reset.editor.active = LitematicaConfigDiscovery.isModified(clickedCard.option.object());
            if (reset.mouseClicked(click, doubled)) setFocused(reset);
            return true;
        }
        if (clickedCard != null && clickedCard.infoButton != null
                && clickedCard.infoButton.isMouseOver(click.x(), click.y())) return true;
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Card card = cardAt(click.x(), click.y());
            if (card != null) {
                toggleFavorite(card.option.key());
                rebuildLayout();
                return true;
            }
            return super.mouseClicked(click, doubled);
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        Card card = cardAt(click.x(), click.y());
        if (card != null && card.aliasButton != null
                && card.aliasButton.isMouseOver(click.x(), click.y())) {
            return super.mouseClicked(click, doubled);
        }
        if (card != null && click.y() >= card.y && click.y() < card.y + Math.max(9, 12 * card.scale)
                && click.x() >= card.x + 6 && click.x() < card.x + card.width - 6) {
            setFocused(null);
            setDragging(false);
            draggingKey = card.option.key();
            draggingFavorite = card.favorite;
            card.motion = null;
            card.displayScale = 1;
            dragMouseX = click.x();
            dragMouseY = click.y();
            dragOffsetX = click.x() - card.x;
            dragOffsetY = click.y() - card.y;
            dropTarget = null;
            dragLift = 0f;
            dragStartedAt = System.nanoTime();
            hideDraggedControls();
            return true;
        }
        if ((favoritesSearchBox != null && favoritesSearchBox.isMouseOver(click.x(), click.y()))
                || (allSettingsSearchBox != null && allSettingsSearchBox.isMouseOver(click.x(), click.y()))) {
            if (click.y() < contentTop || click.y() >= contentBottom) return false;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (draggingKey != null && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragMouseX = click.x();
            dragMouseY = click.y();
            updateDragTarget();
            setDragging(true);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (draggingKey != null && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragMouseX = click.x();
            dragMouseY = click.y();
            updateDragTarget();
            finishDrag();
            return true;
        }
        draggingKey = null;
        dropTarget = null;
        return super.mouseReleased(click);
    }

    private void updateDragTarget() {
        dropTarget = null;
        Card dragged = findCard(draggingKey);
        if (dragged == null) return;
        int groupTop = (draggingFavorite ? favoritesHeaderY : othersHeaderY) + SECTION_HEIGHT;
        int top = Math.max(cardsViewportTop(), groupTop);
        int bottom = draggingFavorite ? Math.min(contentBottom, othersHeaderY - 12) : contentBottom;
        if (!draggingFavorite) {
            GroupDivider group = groupDividers.stream().filter(divider -> divider.grouping.equals(groupingKey(dragged.option)))
                    .findFirst().orElse(null);
            if (group == null) return;
            groupTop = group.y + group.height;
            top = Math.max(top, groupTop);
            if (group != groupDividers.getLast()) bottom = Math.min(bottom, group.bottom);
        }
        int left = panelLeft + 16;
        int availableWidth = panelWidth - 32;
        if (dragMouseY < top || dragMouseY >= bottom || dragMouseX < left || dragMouseX >= left + availableWidth) return;
        int gap = Math.max(4, CARD_GAP - (int) Math.round(SparkFixConfig.litematicaCompactness()) / 2);
        int columnWidth = (availableWidth - gap * (dragged.columns - 1)) / dragged.columns;
        int column = clamp((int) ((dragMouseX - left + gap / 2.0) / (columnWidth + gap)), 0, dragged.columns - 1);
        List<Card> lane = cards.stream().filter(card -> sameDragGroup(dragged, card)
                && card.column == column && card != dragged).sorted(Comparator.comparingInt(card -> card.y)).toList();
        Card anchor = null;
        boolean after = false;
        for (Card card : lane) {
            anchor = card;
            after = dragMouseY >= card.y + card.height / 2.0;
            if (!after) break;
        }
        int markerY = anchor == null ? groupTop - 3
                : after ? anchor.y + anchor.height + gap / 2 : anchor.y - gap / 2;
        int x = left + column * (columnWidth + gap);
        int width = column == dragged.columns - 1 ? left + availableWidth - x : columnWidth;
        dropTarget = new DropTarget(column, anchor == null ? null : anchor.option.key(), after, x, markerY, width);
    }

    private boolean sameDragGroup(Card dragged, Card other) {
        return dragged.favorite == other.favorite
                && (dragged.favorite || groupingKey(dragged.option).equals(groupingKey(other.option)));
    }

    private void finishDrag() {
        updateCardMotions();
        Card dragged = findCard(draggingKey);
        Map<String, CardPose> previous = new LinkedHashMap<>();
        for (Card card : cards) previous.put(card.option.key(), new CardPose(card.x, card.y, card.displayScale));
        if (dragged != null) {
            previous.put(draggingKey, dragPose(dragged));
            if (dropTarget != null) {
                List<String> lane = cards.stream().filter(card -> sameDragGroup(dragged, card)
                        && card.column == dropTarget.column).map(card -> card.option.key()).toList();
                int originalSlot = lane.indexOf(draggingKey);
                List<String> remaining = new ArrayList<>(lane);
                remaining.remove(draggingKey);
                int slot = dropTarget.anchorKey == null ? remaining.size()
                        : remaining.indexOf(dropTarget.anchorKey) + (dropTarget.after ? 1 : 0);
                if (dragged.column != dropTarget.column || originalSlot != slot) {
                    List<String> order = draggingFavorite ? favoriteOrder : settingsOrder;
                    // An empty column only changes placement. Appending to the full order
                    // would move this card beyond the next category and create a new group.
                    if (dropTarget.anchorKey != null && order.contains(dropTarget.anchorKey)) {
                        order.remove(draggingKey);
                        int target = order.indexOf(dropTarget.anchorKey) + (dropTarget.after ? 1 : 0);
                        order.add(target, draggingKey);
                    }
                    assignColumn(layoutColumns, dragged, dropTarget.column);
                    if (regionFiltered(draggingFavorite)) {
                        assignColumn(filteredColumns, dragged, dropTarget.column);
                    }
                }
            }
        }
        draggingKey = null;
        dropTarget = null;
        setDragging(false);
        rebuildLayout();
        long now = System.nanoTime();
        for (Card card : cards) {
            CardPose from = previous.get(card.option.key());
            if (from == null || (from.x == card.x && from.y == card.y && from.scale == 1)) continue;
            if (card.controls.isEmpty() && from.y < contentBottom && from.y + card.height > cardsViewportTop()) addEditor(card);
            card.motion = new CardMotion(from, card.x, card.y, now);
        }
        updateCardMotions();
    }

    private void updateCardMotions() {
        long now = System.nanoTime();
        for (Card card : cards) {
            CardMotion motion = card.motion;
            if (motion == null) continue;
            float elapsed = Math.clamp((now - motion.startedAt) / (float) SETTLE_NANOS, 0f, 1f);
            float eased = elapsed * elapsed * (3f - 2f * elapsed);
            int x = Math.round(motion.from.x + (motion.x - motion.from.x) * eased);
            int y = Math.round(motion.from.y + (motion.y - motion.from.y) * eased);
            int dx = x - card.x;
            int dy = y - card.y;
            card.x = x;
            card.y = y;
            card.displayScale = motion.from.scale + (1 - motion.from.scale) * eased;
            for (AbstractWidget control : card.controls) {
                control.setX(control.getX() + dx);
                control.setY(control.getY() + dy);
                control.visible = card.y < contentBottom && card.y + card.height > cardsViewportTop();
            }
            for (AbstractWidget icon : new AbstractWidget[]{card.aliasButton, card.infoButton}) {
                if (icon == null) continue;
                icon.setX(icon.getX() + dx);
                icon.setY(icon.getY() + dy);
                icon.visible = card.y < contentBottom && card.y + card.height > cardsViewportTop();
            }
            if (elapsed == 1) card.motion = null;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (draggingKey != null && event.isEscape()) {
            dropTarget = null;
            finishDrag();
            return true;
        }
        // Escape belongs to the native recorder while binding a key; it must
        // finish/clear that binding before it can close the whole settings page.
        if (getFocused() instanceof ScaledEditor scaled && scaled.editor instanceof LitematicaHotkeyWidget hotkey
                && hotkey.isCapturing()) return scaled.keyPressed(event);
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (favoritesCategories != null && favoritesCategories.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        if (allCategories != null && allCategories.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        if (mouseY < contentTop || mouseY > contentBottom || mouseX < panelLeft || mouseX > panelLeft + panelWidth) return false;
        if (layoutDirty) rebuildLayout();
        for (Card card : cards) {
            if (card.infoButton == null || !card.infoButton.visible || card.motion != null
                    || !card.infoButton.isMouseOver(mouseX, mouseY)) continue;
            int pageSize = infoVisibleLines() - 1;
            int maxOffset = Math.max(0, infoLines(card).size() - pageSize);
            if (maxOffset > 0) {
                if (!card.option.key().equals(infoTooltipKey)) {
                    infoTooltipKey = card.option.key();
                    infoTooltipOffset = 0;
                }
                infoTooltipOffset = Math.clamp(infoTooltipOffset - (int) Math.signum(verticalAmount) * 3, 0, maxOffset);
                return true;
            }
            break;
        }
        smoothScrollBy(-verticalAmount * 26 * SparkFixConfig.litematicaScrollSensitivity());
        return true;
    }

    private void smoothScrollBy(double amount) {
        if (!Double.isFinite(amount) || amount == 0) return;
        long now = System.nanoTime();
        updateScrollAnimation(now);
        // Continue accumulating wheel/trackpad input, but reverse from the visible position immediately.
        double base = amount * (scrollTarget - scrollPosition) < 0 ? scrollPosition : scrollTarget;
        smoothScrollTo(base + amount, now);
    }

    private void smoothScrollTo(double next) {
        long now = System.nanoTime();
        updateScrollAnimation(now);
        smoothScrollTo(next, now);
    }

    private void smoothScrollTo(double next, long now) {
        next = Math.clamp(next, 0, Math.max(0, contentHeight - (contentBottom - contentTop)));
        if (next == scrollTarget) return;
        scrollStart = scrollPosition;
        scrollTarget = next;
        scrollStartedAt = now;
    }

    private void updateScrollAnimation(long now) {
        if (scrollStartedAt == 0) return;
        double progress = Math.clamp((now - scrollStartedAt) / (double) SCROLL_NANOS, 0, 1);
        double eased = 1 - Math.pow(1 - progress, 3);
        scrollPosition = scrollStart + (scrollTarget - scrollStart) * eased;
        int next = (int) Math.round(scrollPosition);
        if (next != scroll) {
            applyScroll(next);
            if (draggingKey != null) updateDragTarget();
        }
        if (progress == 1) scrollStartedAt = 0;
    }

    // Layout changes and dragging the native scrollbar need exact, immediate positioning.
    private void scrollTo(int next) {
        applyScroll(next);
        scrollPosition = scrollStart = scrollTarget = scroll;
        scrollStartedAt = 0;
    }

    private void applyScroll(int next) {
        int previous = scroll;
        scroll = next;
        clampScroll();
        int delta = previous - scroll;
        if (delta != 0) {
            for (Card card : cards) {
                card.y += delta;
                if (card.motion != null) {
                    CardMotion motion = card.motion;
                    card.motion = new CardMotion(new CardPose(motion.from.x, motion.from.y + delta, motion.from.scale),
                            motion.x, motion.y + delta, motion.startedAt);
                }
            }
            for (AbstractWidget widget : contentWidgets) widget.setY(widget.getY() + delta);
            for (EmptyState state : emptyStates) state.y += delta;
            for (GroupDivider divider : groupDividers) {
                divider.y += delta;
                divider.bottom += delta;
            }
            favoritesHeaderY += delta;
            othersHeaderY += delta;
        }
        if (favoritesHeading != null) {
            int headerY = favoritesDisplayY();
            favoritesHeading.setY(headerY + 2);
            favoritesSearchBox.setY(headerY + 2);
            if (favoritesCategories != null) favoritesCategories.setY(headerY + 2);
        }
        if (othersHeading != null) {
            int headerY = Math.max(contentTop, othersHeaderY);
            othersHeading.setY(headerY + 2);
            allSettingsSearchBox.setY(headerY + 2);
            if (allCategories != null) allCategories.setY(headerY + 2);
        }
        // Newly visible controls are created once; scrolling keeps existing native inputs alive.
        for (Card card : cards) {
            if (card.controls.isEmpty() && card.y + card.height > contentTop && card.y < contentBottom) addEditor(card);
        }
        for (AbstractWidget widget : contentWidgets) {
            widget.visible = widget.getBottom() > contentTop && widget.getY() < contentBottom;
        }
        if (scrollBar != null) scrollBar.sync();
        hideDraggedControls();
    }

    private Card cardAt(double x, double y) {
        if (x < panelLeft + 16 || x >= panelLeft + panelWidth - 16 || y < cardsViewportTop() || y >= contentBottom) return null;
        for (int index = cards.size() - 1; index >= 0; index--) {
            Card card = cards.get(index);
            if (x >= card.x && x < card.x + card.width * card.displayScale
                    && y >= card.y && y < card.y + card.height * card.displayScale) return card;
        }
        return null;
    }

    private Card findCard(String key) {
        if (key == null) return null;
        for (Card card : cards) if (key.equals(card.option.key())) return card;
        return null;
    }

    private void toggleFavorite(String key) {
        if (!favoriteOrder.remove(key)) favoriteOrder.add(key);
    }

    private void hideDraggedControls() {
        if (draggingKey == null) return;
        Card card = findCard(draggingKey);
        if (card != null) {
            for (AbstractWidget widget : card.controls) widget.visible = false;
            if (card.aliasButton != null) card.aliasButton.visible = false;
            if (card.infoButton != null) card.infoButton.visible = false;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void saveOnClose() {
        if (savedOnClose) return;
        setFocused(null);
        savedOnClose = true;
        customAliases.save(litematicaAliases, aliasPrimaryNames());
        SparkFixConfig.updateLitematicaCustomAliases(customAliases.savedAliases(), customAliases.deletedAliases());
        SparkFixConfig.setLitematicaSettingsSnapshot(favoriteOrder, settingsOrder,
                litematicaNames, litematicaAliases, layoutColumns, discoveredKeys);
        SparkFixConfig.setLitematicaAliasPresetsApplied(new ArrayList<>(appliedAliasPresets));
        LitematicaConfigDiscovery.save(groups);
        if (renderLayers != null) renderLayers.save();
        SparkFixConfig.save();
    }

    private void closeScreen() {
        saveOnClose();
        minecraft.setScreenAndShow(parent);
    }

    @Override public void onClose() { closeScreen(); }

    @Override public void removed() { if (!openingChildScreen) saveOnClose(); }

    record Option(String key, String group, Object object, String name, String comment) { }
    private record DropTarget(int column, String anchorKey, boolean after, int x, int y, int width) { }
    private record CardPose(float x, float y, float scale) { }
    private record CardMotion(CardPose from, int x, int y, long startedAt) { }

    private static final class GroupDivider {
        private final String grouping;
        private final List<FormattedCharSequence> lines;
        private int y;
        private int bottom;
        private final int height;
        private GroupDivider(String grouping, List<FormattedCharSequence> lines, int y, int height) {
            this.grouping = grouping;
            this.lines = List.copyOf(lines);
            this.y = y;
            this.height = height;
        }
    }

    private static final class EmptyState {
        private final Component message;
        private int y;

        private EmptyState(Component message, int y) {
            this.message = message;
            this.y = y;
        }
    }

    /** Reuses Minecraft's scrollbar rendering and thumb dragging, including resource-pack sprites. */
    private final class SettingsScrollBar extends AbstractScrollArea {
        private SettingsScrollBar() {
            super(panelLeft + panelWidth - 10, contentTop, 8, Math.max(1, contentBottom - contentTop),
                    Component.translatable("config.spark_fix.litematica_scrollbar"), defaultSettings(26));
        }

        @Override protected int contentHeight() { return contentHeight; }

        @Override public void setScrollAmount(double next) { scrollTo((int) Math.round(next)); }

        private void sync() {
            super.setScrollAmount(scroll);
            visible = active = maxScrollAmount() > 0;
        }

        @Override public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            if (!visible || !active || !updateScrolling(click)) return false;
            if (click.y() < scrollBarY() || click.y() >= scrollBarY() + scrollerHeight()) {
                double fraction = (click.y() - getY() - scrollerHeight() / 2.0)
                        / Math.max(1, getHeight() - scrollerHeight());
                smoothScrollTo(fraction * maxScrollAmount());
            }
            return true;
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (!isFocused() || !active) return false;
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
                smoothScrollTo(event.key() == GLFW.GLFW_KEY_HOME ? 0 : maxScrollAmount());
                return true;
            }
            Double amount = switch (event.key()) {
                case GLFW.GLFW_KEY_PAGE_UP -> (double) -(contentBottom - cardsViewportTop());
                case GLFW.GLFW_KEY_PAGE_DOWN -> (double) (contentBottom - cardsViewportTop());
                case GLFW.GLFW_KEY_UP -> -26 * SparkFixConfig.litematicaScrollSensitivity();
                case GLFW.GLFW_KEY_DOWN -> 26 * SparkFixConfig.litematicaScrollSensitivity();
                default -> null;
            };
            if (amount == null) return false;
            smoothScrollBy(amount);
            return true;
        }

        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float partialTick) {
            extractScrollbar(graphics, x, y);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    /** Keeps native text editing, focus and narration in sync with scaled drawing. */
    private final class ScaledEditor extends AbstractWidget {
        private final Card card;
        private final AbstractWidget editor;
        private final float scale;

        private ScaledEditor(Card card, AbstractWidget editor, int x, int y, float scale) {
            super(x, y, (int) Math.ceil(editor.getWidth() * scale),
                    (int) Math.ceil(editor.getHeight() * scale), editor.getMessage());
            this.editor = editor;
            this.card = card;
            this.scale = scale;
        }

        private MouseButtonEvent local(MouseButtonEvent click) {
            return new MouseButtonEvent((card.x + (click.x() - card.x) / card.displayScale - getX()) / scale,
                    (card.y + (click.y() - card.y) / card.displayScale - getY()) / scale, click.buttonInfo());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            extractAt(graphics, card.x + (getX() - card.x) * card.displayScale,
                    card.y + (getY() - card.y) * card.displayScale, scale * card.displayScale, mouseX, mouseY, partialTick);
        }

        private void extractAt(GuiGraphicsExtractor graphics, float x, float y, float drawScale,
                               int mouseX, int mouseY, float partialTick) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(drawScale, drawScale);
            editor.extractRenderState(graphics, (int) ((mouseX - x) / drawScale),
                    (int) ((mouseY - y) / drawScale), partialTick);
            graphics.pose().popMatrix();
        }

        @Override
        public boolean isMouseOver(double x, double y) {
            return y >= cardsViewportTop() && y < contentBottom
                    && super.isMouseOver(card.x + (x - card.x) / card.displayScale,
                            card.y + (y - card.y) / card.displayScale);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            return visible && active && isMouseOver(click.x(), click.y()) && editor.mouseClicked(local(click), doubled);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
            return editor.mouseDragged(local(click), deltaX / (scale * card.displayScale), deltaY / (scale * card.displayScale));
        }

        @Override public boolean mouseReleased(MouseButtonEvent click) { return editor.mouseReleased(local(click)); }
        @Override public boolean keyPressed(KeyEvent event) { return editor.keyPressed(event); }
        @Override public boolean keyReleased(KeyEvent event) { return editor.keyReleased(event); }
        @Override public boolean charTyped(CharacterEvent event) { return editor.charTyped(event); }
        @Override public boolean preeditUpdated(PreeditEvent event) { return editor.preeditUpdated(event); }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            editor.setFocused(focused);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { editor.updateNarration(output); }
    }

    private static final class Card {
        private final Option option;
        private int x;
        private int y;
        private final int width;
        private final int height;
        private final boolean favorite;
        private final int titleWidth;
        private final List<FormattedCharSequence> titleLines;
        private final int aliasX;
        private final int aliasY;
        private final int infoX;
        private final int infoY;
        private final int headerToolsX;
        private final int logicalWidth;
        private final int logicalHeight;
        private final int editorY;
        private final float scale;
        private int column;
        private int columns;
        private float displayScale = 1;
        private CardMotion motion;
        private final List<ScaledEditor> controls = new ArrayList<>();
        private ScaledEditor resetControl;
        private AbstractWidget aliasButton;
        private AbstractWidget infoButton;
        private Component infoComment;

        private Card(Option option, int x, int y, int width, int height, boolean favorite,
                     int titleWidth, List<FormattedCharSequence> titleLines,
                     int aliasX, int aliasY, int infoX, int infoY, int headerToolsX,
                     int logicalWidth, int logicalHeight, int editorY, float scale) {
            this.option = option;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.favorite = favorite;
            this.titleWidth = titleWidth;
            this.titleLines = titleLines;
            this.aliasX = aliasX;
            this.aliasY = aliasY;
            this.infoX = infoX;
            this.infoY = infoY;
            this.headerToolsX = headerToolsX;
            this.logicalWidth = logicalWidth;
            this.logicalHeight = logicalHeight;
            this.editorY = editorY;
            this.scale = scale;
        }
    }
}
