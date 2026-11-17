package dev.soundpanel;

import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;

/** Serve imported audio to the normal Minecraft sound loader without a global resource reload. */
public final class ClipResourceFactory implements ResourceFactory {
    private final ResourceFactory original;
    public ClipResourceFactory(ResourceFactory original) { this.original = original; }
    @Override public Optional<Resource> getResource(Identifier id) { return original.getResource(id); }

    @Override public InputStream open(Identifier id) throws IOException {
        var path = SoundPanel.CONFIG.resolveVirtual(id);
        return path == null ? original.open(id) : Files.newInputStream(path);
    }
}
