package dev.soundpanel;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class SoundPanelScreen extends Screen {
    private static final int ROW = 17;
    private static final int WHITE = 0xffebeafa;
    private static final int MUTED = 0xffa5a7bb;
    private static final int ACCENT = 0xffbaacff;
    private final Screen parent;
    private final Set<String> expanded = new HashSet<>();
    private enum Tab { ENTITIES, ALL, PACKS }
    private Tab tab = Tab.ENTITIES;
    private boolean importing;
    private boolean chooseFile;
    private String filterText = "";
    private String fileFilter = "";
    private String status = "";
    private SoundCatalog.Event selected;
    private List<SoundCatalog.Group> groups = List.of();
    private List<SoundCatalog.Group> packGroups = List.of();
    private List<Identifier> ids = List.of();
    private List<Row> tree = List.of();
    private List<Path> fileRows = List.of();
    private TextFieldWidget filter;
    private ButtonWidget entitiesTab, allTab, packTab, fileTab, preview, original, reset, firstFile, reloadFile;
    private int x, y, w, h, listY, listEnd, visible, scroll, previousScroll;

    private record Row(SoundCatalog.Group group, SoundCatalog.Event event) {}
    SoundPanelScreen(Screen parent) { super(Text.literal("Sound Panel")); this.parent = parent; }

    @Override protected void init() {
        w = Math.min(width - 12, 590);
        h = Math.min(height - 12, 358);
        x = (width - w) / 2;
        y = (height - h) / 2;
        listY = y + 84;
        listEnd = y + h - 59;
        visible = Math.max(0, (listEnd - listY) / ROW);
        entitiesTab = button(x + 12, y + 32, 59, "Entities", () -> changeTab(Tab.ENTITIES));
        allTab = button(x + 75, y + 32, 70, "All sounds", () -> changeTab(Tab.ALL));
        packTab = button(x + 149, y + 32, 96, "Server sounds", () -> changeTab(Tab.PACKS));
        fileTab = button(x + 12, y + 32, 86, "Back to sounds", () -> files(false));
        reloadFile = button(x + 102, y + 32, 62, "Refresh", this::rebuildFiles);
        firstFile = button(x + 168, y + 32, 88, "Open folder", this::openFolder);
        filter = new TextFieldWidget(textRenderer, x + 12, y + 58, w - 24, 18, Text.literal("Filter"));
        filter.setMaxLength(180);
        filter.setChangedListener(value -> {
            if (chooseFile) fileFilter = value; else filterText = value;
            scroll = 0;
            rebuild();
        });
        addDrawableChild(filter);
        int gap = 3, part = (w - 24 - gap * 4) / 5, buttonsY = y + h - 40;
        preview = button(x + 12, buttonsY, part, "Preview", () -> play(false));
        original = button(x + 12 + part + gap, buttonsY, part, "Original", () -> play(true));
        chooseButton = button(x + 12 + 2 * (part + gap), buttonsY, part, "Choose file", () -> files(true));
        reset = button(x + 12 + 3 * (part + gap), buttonsY, part, "Reset", this::reset);
        button(x + 12 + 4 * (part + gap), buttonsY, part, "Done", this::close);
        var sorted = new TreeSet<>(Comparator.comparing(Identifier::toString));
        sorted.addAll(Registries.SOUND_EVENT.getIds());
        sorted.addAll(client.getSoundManager().getKeys());
        ids = List.copyOf(sorted);
        groups = SoundCatalog.group(ids);
        if (tab == Tab.PACKS) scanPacks();
        filter.setText(chooseFile ? fileFilter : filterText);
        rebuild();
    }

    private ButtonWidget chooseButton;
    private ButtonWidget button(int bx, int by, int bw, String label, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> action.run()).dimensions(bx, by, bw, 20).build());
    }

    private void changeTab(Tab next) {
        tab = next;
        if (tab == Tab.PACKS) scanPacks();
        scroll = 0;
        selected = null;
        status = "";
        rebuild();
    }

    private void scanPacks() {
        Map<Identifier, List<String>> scan = PackSoundCatalog.activeSounds();
        Map<String, List<SoundCatalog.Event>> groups = new TreeMap<>();
        for (var entry : scan.entrySet()) {
            var id = entry.getKey();
            for (String source : entry.getValue()) {
                groups.computeIfAbsent(source, name -> new ArrayList<>())
                        .add(new SoundCatalog.Event(id, id.toString(), "Server sound · " + source));
            }
        }
        packGroups = groups.entrySet().stream()
                .map(entry -> new SoundCatalog.Group("server:" + entry.getKey(), entry.getKey(),
                        entry.getValue().stream().sorted(Comparator.comparing(SoundCatalog.Event::label)).toList()))
                .toList();
    }

    private Identifier assignmentId(SoundCatalog.Event event) {
        return tab == Tab.PACKS ? SoundPanel.serverKey(client, event.id()) : event.id();
    }

    private void files(boolean open) {
        if (open && (selected == null || importing)) return;
        if (open) previousScroll = scroll;
        chooseFile = open;
        status = open ? "Click a file to assign it. Choose Original or Preview to listen afterward." : "";
        filter.setText(open ? fileFilter : filterText);
        scroll = open ? 0 : previousScroll;
        rebuild();
    }

    private void rebuildFiles() { scroll = 0; rebuild(); }

    private void openFolder() {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(SoundPanel.CONFIG.sourceDir());
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new UnsupportedOperationException("Desktop open");
                }
                Desktop.getDesktop().open(SoundPanel.CONFIG.sourceDir().toFile());
            } catch (Exception e) {
                if (client != null) client.execute(() -> status = "Open the game's sounds folder manually, then click Refresh.");
            }
        });
    }

    private static boolean includes(String text, String filter) {
        text = text.toLowerCase(Locale.ROOT);
        for (String token : filter.strip().toLowerCase(Locale.ROOT).split("\\s+")) if (!text.contains(token)) return false;
        return true;
    }

    private void rebuild() {
        if (chooseFile) {
            try (var path = Files.list(SoundPanel.CONFIG.sourceDir())) {
                fileRows = path.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> AudioConversion.isSupportedName(p.getFileName().toString()))
                        .filter(p -> includes(p.getFileName().toString(), fileFilter))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .toList();
            } catch (java.io.IOException e) { fileRows = List.of(); }
        } else {
            List<Row> rows = new ArrayList<>();
            if (tab == Tab.ALL) {
                for (Identifier id : ids) {
                    String text = id.toString();
                    var clip = SoundPanel.CONFIG.mappings().get(text);
                    if (includes(text + (clip == null ? "" : " " + clip.name()), filterText)) {
                        rows.add(new Row(null, new SoundCatalog.Event(id, text)));
                    }
                }
            } else {
                for (var group : tab == Tab.PACKS ? packGroups : groups) {
                    boolean matchesGroup = includes(group.label() + " " + group.id(), filterText);
                    List<SoundCatalog.Event> matches = matchesGroup ? group.events() : group.events().stream()
                            .filter(event -> includes(group.label() + " " + event.id() + " " + event.label(), filterText)).toList();
                    if (matches.isEmpty()) continue;
                    rows.add(new Row(group, null));
                    if (expanded.contains(group.id()) || !filterText.isBlank()) {
                        for (var event : matches) rows.add(new Row(group, event));
                    }
                }
            }
            tree = List.copyOf(rows);
        }
        scroll = clamp(scroll);
        refresh();
    }

    private int clamp(int target) { return Math.max(0, Math.min(Math.max(0, size() - visible), target)); }
    private int size() { return chooseFile ? fileRows.size() : tree.size(); }

    private void refresh() {
        entitiesTab.visible = allTab.visible = packTab.visible = !chooseFile;
        entitiesTab.active = tab != Tab.ENTITIES;
        allTab.active = tab != Tab.ALL;
        packTab.active = tab != Tab.PACKS;
        reloadFile.visible = fileTab.visible = firstFile.visible = chooseFile;
        preview.visible = original.visible = chooseButton.visible = reset.visible = !chooseFile;
        preview.active = original.active = selected != null;
        chooseButton.active = selected != null && !importing;
        reset.active = selected != null && !importing && SoundPanel.CONFIG.mappings().containsKey(assignmentId(selected).toString());
        filter.setPlaceholder(Text.literal(chooseFile ? "Find audio in the game's sounds folder" : "Find an entity, action or sound ID..."));
    }

    private void play(boolean vanilla) {
        if (selected == null) return;
        status = SoundPanel.preview(client, selected.id(), assignmentId(selected), vanilla)
                ? (vanilla ? "Playing original" : "Playing preview") : "No playable audio for that sound.";
    }

    private void reset() {
        if (selected == null || importing) return;
        try {
            SoundPanel.stopPreview(client);
            SoundPanel.CONFIG.clear(assignmentId(selected));
            status = "Custom assignment removed.";
            refresh();
        } catch (Exception e) {
            SoundPanel.LOG.warn("Could not reset a mapping", e);
            status = "Could not save settings.";
        }
    }

    private void importFile(Path path) {
        if (selected == null || importing) return;
        final var target = assignmentId(selected);
        final var game = client;
        importing = true;
        if (chooseFile) files(false);
        status = "Checking and importing " + path.getFileName() + "...";
        refresh();
        CompletableFuture.supplyAsync(() -> {
            try { return SoundPanel.CONFIG.install(target, path); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((clip, failure) -> game.execute(() -> {
            importing = false;
            if (failure == null) status = "Assigned " + clip.name();
            else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                String message = cause.getMessage();
                status = message != null && (message.startsWith("Choose") || message.startsWith("File is") ||
                        message.startsWith("Invalid") || message.startsWith("FFmpeg") || message.startsWith("To import") || message.startsWith("Audio"))
                        ? message : "Could not import audio; check your file and the game log.";
                SoundPanel.LOG.warn("Could not import audio", cause);
            }
            if (game.currentScreen == this) rebuild();
        }));
    }

    @Override public void onFilesDropped(List<Path> paths) {
        if (selected == null) { status = "Select a sound event first."; return; }
        if (paths.size() != 1) { status = "Drop one audio file at a time."; return; }
        importFile(paths.getFirst());
    }

    @Override public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0 && click.x() >= x + 12 && click.x() < x + w - 12
                && click.y() >= listY && click.y() < listY + visible * ROW) {
            int index = scroll + ((int) click.y() - listY) / ROW;
            if (index >= size()) return super.mouseClicked(click, doubleClick);
            setFocused(null);
            if (chooseFile) importFile(fileRows.get(index));
            else {
                Row row = tree.get(index);
                if (row.event() == null) {
                    if (!expanded.add(row.group().id())) expanded.remove(row.group().id());
                    rebuild();
                } else {
                    selected = row.event();
                    status = selected.note();
                    refresh();
                    if (doubleClick) play(false);
                }
            }
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= x && mouseX < x + w && mouseY >= listY && mouseY < listEnd && vertical != 0) {
            scroll = clamp(scroll + (int) Math.copySign(Math.max(1, Math.ceil(Math.abs(vertical) * 3)), -vertical));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88080515);
        ctx.fill(x, y, x + w, y + h, 0xff191723);
        ctx.fill(x, y, x + w, y + 2, 0xff977aff);
        ctx.drawText(textRenderer, "SOUND PANEL", x + 12, y + 12, WHITE, true);
        String location = chooseFile ? "Instance / sounds" : "Minecraft 1.21.11";
        ctx.drawText(textRenderer, location, x + w - 12 - textRenderer.getWidth(location), y + 12, ACCENT, false);
        ctx.fill(x + 11, listY - 1, x + w - 11, listEnd + 1, 0xff100e18);
        if (visible > 0) renderRows(ctx, mx, my);
        String summary = selected == null ? switch (tab) {
            case ALL -> ids.size() + " sound IDs";
            case ENTITIES -> groups.size() + " entity sound groups";
            case PACKS -> packGroups.size() + " active server packs with sounds";
        } : selected.label() + "   " + selected.id();
        ctx.drawText(textRenderer, textRenderer.trimToWidth(summary, w - 24), x + 12, y + h - 53, MUTED, false);
        String footer = status.isEmpty() ? "Expand an entity, choose an action, then choose or drop an audio file." : status;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(footer, w - 24), x + 12, y + h - 13, MUTED, false);
        super.render(ctx, mx, my, delta);
    }

    private void renderRows(DrawContext ctx, int mx, int my) {
        ctx.enableScissor(x + 12, listY, x + w - 12, listEnd);
        for (int i = 0; i < visible && i + scroll < size(); i++) {
            int line = listY + i * ROW;
            boolean hovered = mx >= x + 12 && mx < x + w - 12 && my >= line && my < line + ROW;
            Row row = chooseFile ? null : tree.get(i + scroll);
            boolean group = row != null && row.event() == null;
            boolean child = row != null && row.group() != null && !group;
            boolean selectedRow = row != null && row.event() != null && row.event().equals(selected);
            int background = selectedRow ? 0xff46365e : hovered ? 0xff352c48 : group ? 0xff292236 : (i & 1) == 0 ? 0xff1e192a : 0xff221c2f;
            ctx.fill(x + 12, line, x + w - 12, line + ROW - 1, background);
            int left = x + (child ? 37 : 19);
            if (child) ctx.fill(x + 25, line, x + 26, line + ROW, 0xff665785);
            if (selectedRow) ctx.fill(x + 12, line, x + 14, line + ROW - 1, ACCENT);
            String label, right = "";
            if (chooseFile) label = fileRows.get(i + scroll).getFileName().toString();
            else if (group) {
                boolean opened = !filterText.isBlank() || expanded.contains(row.group().id());
                label = (opened ? "▾  " : "▸  ") + row.group().label();
                right = Integer.toString(row.group().events().size());
            } else {
                label = row.event().label();
                var clip = SoundPanel.CONFIG.mappings().get(assignmentId(row.event()).toString());
                if (clip != null) right = clip.name();
            }
            int rightMax = Math.max(30, (int) (w * 0.36));
            right = textRenderer.trimToWidth(right, rightMax);
            int labelMax = w - (left - x) - 24 - (right.isEmpty() ? 0 : textRenderer.getWidth(right) + 10);
            ctx.drawText(textRenderer, textRenderer.trimToWidth(label, Math.max(10, labelMax)), left, line + 4, WHITE, false);
            if (!right.isEmpty()) ctx.drawText(textRenderer, right, x + w - 20 - textRenderer.getWidth(right), line + 4, ACCENT, false);
        }
        if (size() == 0) {
            String empty = chooseFile ? "No audio files. Add them to the game's sounds folder and click Refresh."
                    : tab == Tab.PACKS && packGroups.isEmpty() ? "No custom sounds from an active server resource pack."
                    : "No matching sounds.";
            ctx.drawText(textRenderer, textRenderer.trimToWidth(empty, w - 42), x + 20, listY + 8, MUTED, false);
        }
        ctx.disableScissor();
    }

    @Override public void renderBackground(DrawContext ctx, int mx, int my, float deltaTicks) {}
    @Override public void close() {
        if (chooseFile) { files(false); return; }
        SoundPanel.stopPreview(client);
        client.setScreen(parent);
    }
    @Override public boolean shouldPause() { return false; }
}
