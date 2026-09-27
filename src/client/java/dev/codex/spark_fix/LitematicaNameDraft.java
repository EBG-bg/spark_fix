package dev.codex.spark_fix;

import java.util.ArrayList;
import java.util.List;

/** Unsaved tag values live independently of widgets, including empty tags. */
final class LitematicaNameDraft {
    static final class Tag {
        String text;

        Tag(String text) { this.text = text == null ? "" : text; }
    }

    final List<Tag> main = new ArrayList<>();
    final List<Tag> aliases = new ArrayList<>();
    private final String fallback;

    LitematicaNameDraft(String fallback, List<String> names, List<String> aliases) {
        this.fallback = fallback;
        names.forEach(value -> main.add(new Tag(value)));
        aliases.forEach(value -> this.aliases.add(new Tag(value)));
        if (main.isEmpty()) main.add(new Tag(fallback));
    }

    Tag add(boolean mainName) {
        Tag tag = new Tag("");
        (mainName ? main : aliases).add(tag);
        return tag;
    }

    boolean canMove(Tag tag) {
        return main.contains(tag) && !tag.text.isBlank()
                && main.stream().filter(value -> !value.text.isBlank()).count() >= 2;
    }

    boolean moveToAliases(Tag tag) {
        if (!canMove(tag)) return false;
        main.remove(tag);
        aliases.add(tag);
        return true;
    }

    List<String> mainNames() {
        List<String> result = clean(main);
        return result.isEmpty() ? List.of(fallback) : result;
    }

    List<String> searchAliases() { return clean(aliases); }

    private static List<String> clean(List<Tag> tags) {
        return tags.stream().map(tag -> tag.text.trim()).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
