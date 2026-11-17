package dev.soundpanel;

import net.minecraft.resource.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class PackSoundCatalogTests {
    private PackSoundCatalogTests() {}

    static void run() {
        var vanilla = pack("vanilla", ResourcePackSource.BUILTIN);
        var local = pack("file/server_fake", ResourcePackSource.NONE);
        var remote = pack("server/official-example", ResourcePackSource.SERVER);
        var base = resource(vanilla, """
            {
              "entity.mock.attack":{"sounds":["entity/mock/attack"]},
              "entity.mock.parent":{"sounds":[{"name":"entity.mock.attack","type":"event"}]},
              "entity.mock.local":{"sounds":["entity/mock/local"]}
            }
            """);
        var localDef = resource(local, """
            {"local_only":{"sounds":["unrelated"]},"can_not_leak":{"sounds":["entity/mock/attack"]}}
            """);
        var serverDef = resource(remote, """
            {"custom.hit":{"sounds":["example:hit"]},"custom.reference":{"sounds":[{"name":"minecraft:entity.mock.parent","type":"event"}]}}
            """);
        var manager = manager(List.of(vanilla, local, remote), Map.of(
                Identifier.ofVanilla("sounds.json"), List.of(base, localDef),
                Identifier.of("example", "sounds.json"), List.of(serverDef)),
                Map.of(Identifier.ofVanilla("sounds/entity/mock/attack.ogg"), resource(remote, ""),
                        Identifier.ofVanilla("sounds/entity/mock/local.ogg"), resource(local, "")));
        var active = PackSoundCatalog.scan(manager);
        var expected = Set.of(Identifier.ofVanilla("entity.mock.attack"), Identifier.ofVanilla("entity.mock.parent"),
                Identifier.of("example", "custom.hit"), Identifier.of("example", "custom.reference"));
        if (!active.keySet().equals(expected)) throw new AssertionError("Unexpected server IDs: " + active);
        for (var sources : active.values()) {
            if (!sources.equals(List.of("server/official-example"))) throw new AssertionError("Invalid source " + sources);
        }
        var localOnly = manager(List.of(vanilla, local), Map.of(Identifier.ofVanilla("sounds.json"), List.of(base, localDef)), Map.of());
        if (!PackSoundCatalog.scan(localOnly).isEmpty()) throw new AssertionError("Local resource packs leaked into server sounds");
    }

    private static ResourcePack pack(String id, ResourcePackSource source) {
        var info = new ResourcePackInfo(id, Text.literal(id), source, Optional.empty());
        return (ResourcePack) Proxy.newProxyInstance(ResourcePack.class.getClassLoader(), new Class<?>[] {ResourcePack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInfo" -> info;
                    case "getId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Resource resource(ResourcePack pack, String data) {
        return new Resource(pack, () -> new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static ResourceManager manager(List<ResourcePack> packs, Map<Identifier, List<Resource>> definitions,
                                           Map<Identifier, Resource> files) {
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(), new Class<?>[] {ResourceManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "streamResourcePacks" -> packs.stream();
                    case "getAllNamespaces" -> Set.of("minecraft", "example");
                    case "getAllResources" -> definitions.getOrDefault(args[0], List.of());
                    case "findResources" -> files;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
