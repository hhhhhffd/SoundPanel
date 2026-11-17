package dev.soundpanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Indexes only resource packs already loaded by the client. It never fetches a pack. */
public final class PackSoundCatalog {
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static volatile Map<Identifier, List<String>> active = Map.of();
    private PackSoundCatalog() {}

    static void register() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("soundpanel", "server_sound_index"); }
            @Override public void reload(ResourceManager manager) {
                try { active = scan(manager); }
                catch (RuntimeException e) {
                    active = Map.of();
                    SoundPanel.LOG.warn("Could not index currently active server sounds", e);
                }
            }
        });
    }

    public static boolean isActive(Identifier event) { return active.containsKey(event); }
    static Map<Identifier, List<String>> activeSounds() { return active; }

    public static Map<Identifier, List<String>> scan(ResourceManager resources) {
        if (resources.streamResourcePacks().noneMatch(pack -> pack.getInfo().source() == ResourcePackSource.SERVER)) {
            return Map.of();
        }
        Map<Identifier, Set<String>> packs = new HashMap<>();
        Map<Identifier, Set<Identifier>> parents = new HashMap<>();
        // Highest-priority active resource for each audio asset, including file-only pack overrides.
        Map<Identifier, Resource> files = resources.findResources("sounds", id -> id.getPath().endsWith(".ogg"));
        for (String namespace : resources.getAllNamespaces()) {
            for (Resource resource : resources.getAllResources(Identifier.of(namespace, "sounds.json"))) {
                String pack = resource.getPackId();
                boolean fromServer = server(resource);
                // Vanilla definitions are consulted only to identify server OGG overrides with no sounds.json.
                if (!fromServer && !isVanilla(pack)) continue;
                try (InputStream input = resource.getInputStream()) {
                    byte[] json = input.readNBytes(MAX_JSON_BYTES + 1);
                    if (json.length > MAX_JSON_BYTES) continue;
                    JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) continue;
                    for (var definition : parsed.getAsJsonObject().entrySet()) {
                        Identifier event = Identifier.tryParse(namespace + ":" + definition.getKey());
                        if (event == null || !definition.getValue().isJsonObject()) continue;
                        JsonObject object = definition.getValue().getAsJsonObject();
                        if (fromServer) add(packs, event, pack);
                        JsonElement sounds = object.get("sounds");
                        if (sounds == null || !sounds.isJsonArray()) continue;
                        for (JsonElement sound : sounds.getAsJsonArray()) {
                            String name = string(sound);
                            boolean eventRef = false;
                            if (sound.isJsonObject()) {
                                var entry = sound.getAsJsonObject();
                                name = string(entry.get("name"));
                                eventRef = "event".equals(string(entry.get("type")));
                            }
                            if (name == null) continue;
                            Identifier ref = Identifier.tryParse(name);
                            if (ref == null) continue;
                            if (eventRef) {
                                parents.computeIfAbsent(ref, unused -> new HashSet<>()).add(event);
                            } else {
                                Resource file = files.get(Identifier.of(ref.getNamespace(), "sounds/" + ref.getPath() + ".ogg"));
                                if (file != null && server(file)) add(packs, event, file.getPackId());
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // One invalid/outdated pack should not remove the other results.
                    SoundPanel.LOG.debug("Unable to index sound definitions {} from {}", namespace, pack, e);
                }
            }
        }

        // Include IDs taking their audio indirectly through a sound event overridden by a pack.
        ArrayDeque<Identifier> pending = new ArrayDeque<>(packs.keySet());
        while (!pending.isEmpty()) {
            Identifier child = pending.removeFirst();
            for (Identifier owner : parents.getOrDefault(child, Set.of())) {
                if (packs.computeIfAbsent(owner, unused -> new TreeSet<>()).addAll(packs.get(child))) pending.addLast(owner);
            }
        }

        Map<Identifier, List<String>> result = new TreeMap<>();
        packs.forEach((id, origins) -> result.put(id, List.copyOf(origins)));
        return Collections.unmodifiableMap(result);
    }

    private static boolean server(Resource resource) {
        return resource.getPack().getInfo().source() == ResourcePackSource.SERVER;
    }

    private static boolean isVanilla(String pack) {
        return pack.equals("vanilla") || pack.equals("minecraft");
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static void add(Map<Identifier, Set<String>> packs, Identifier id, String pack) {
        packs.computeIfAbsent(id, unused -> new TreeSet<>()).add(pack);
    }
}
