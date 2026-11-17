package dev.soundpanel;

import net.minecraft.util.Identifier;
import java.util.*;

final class SoundCatalog {
    static final Identifier BOTTLE_THROW = Identifier.ofVanilla("entity.experience_bottle.throw");
    static final Identifier BOTTLE_SHATTER = Identifier.of("soundpanel", "bottle.shatter");
    static final Identifier SPLASH_BREAK = Identifier.ofVanilla("entity.splash_potion.break");
    static final Identifier ORB_PICKUP = Identifier.ofVanilla("entity.experience_orb.pickup");

    record Event(Identifier id, String label, String note) {
        Event(Identifier id, String label) { this(id, label, id.toString()); }
    }

    record Group(String id, String label, List<Event> events) {}
    private SoundCatalog() {}

    static List<Group> group(Collection<Identifier> ids) {
        Map<String, List<Event>> grouped = new TreeMap<>();
        for (Identifier id : ids) {
            String[] segments = id.getPath().split("\\.", 3);
            if (segments.length != 3 || !segments[0].equals("entity") || segments[1].isBlank()) continue;
            String group = id.getNamespace() + ":" + segments[1];
            grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(new Event(id, humanize(segments[2])));
        }
        String key = "minecraft:experience_bottle";
        List<Event> knownBottle = new ArrayList<>(List.of(new Event(BOTTLE_THROW, "Throw"),
                new Event(BOTTLE_SHATTER, "Shatter", "Bottle impact · separate from splash potions"),
                new Event(ORB_PICKUP, "XP pickup", "Shared with experience orbs from other sources")));
        if (grouped.containsKey(key)) {
            for (Event event : grouped.get(key)) if (!BOTTLE_THROW.equals(event.id())) knownBottle.add(event);
        }
        grouped.put(key, knownBottle);
        var groups = new ArrayList<Group>();
        for (var group : grouped.entrySet()) {
            boolean vanilla = group.getKey().startsWith("minecraft:");
            String label = key.equals(group.getKey()) ? "Bottle o' Enchanting"
                    : humanize(group.getKey().substring(group.getKey().indexOf(':') + 1))
                        + (vanilla ? "" : " (" + group.getKey().split(":")[0] + ")");
            if (!key.equals(group.getKey())) group.getValue().sort(Comparator.comparing(Event::label));
            groups.add(new Group(group.getKey(), label, List.copyOf(group.getValue())));
        }
        groups.sort(Comparator.comparing(Group::label));
        return List.copyOf(groups);
    }
    static String humanize(String path) {
        String value = path.replace('_', ' ').replace('.', ' ').strip();
        if (value.isEmpty()) return path;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
