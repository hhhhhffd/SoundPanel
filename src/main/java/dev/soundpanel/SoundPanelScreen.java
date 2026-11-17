package dev.soundpanel;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class SoundPanelScreen extends Screen {
    private static final int BASE = 0xff10141c, PANEL = 0xff161d28, LINE = 0xff2a3444;
    private static final int WHITE = 0xffecf3f8, TEXT = 0xffc8d6e1, MUTED = 0xff91a2b7;
    private static final int GREEN = 0xff78e7c1, YELLOW = 0xffffcd80, RED = 0xffff9e9e;
    private static final int SELECT = 0xff26454a, HOVER = 0xff252f3d, BUTTON = 0xff223040;
    private static final int INACTIVE = 0xff1b222d;
    private final Screen parent;
    private final Set<String> opened = new HashSet<>();
    private final Set<String> seenServerGroups = new HashSet<>();
    private enum Tab { PVP, ALL, SERVER }
    private enum Pane { SOUNDS, FILES }
    private record Row(SoundCatalog.Group group, SoundCatalog.Event event) {}
    private Tab tab = Tab.PVP;
    private Pane focus = Pane.SOUNDS;
    private String soundFilter = "", fileFilter = "", status = "";
    private int statusColor = MUTED;
    private SoundCatalog.Event selected;
    private Path candidate;
    private SoundConfig.Clip prepared;
    private boolean working;
    private boolean removed;
    private int soundScroll, fileScroll;
    private int soundCursor, fileCursor = -1;
    private List<SoundCatalog.Group> allGroups = List.of();
    private List<Row> rows = List.of();
    private List<Path> allFiles = List.of(), files = List.of();
    private TextFieldWidget soundSearch, fileSearch;
    private ActionButton pvpTab, allTab, serverTab, original, current, reset, preview, apply, reload, openFolder;
    private SoundPanelLayout layout;

    SoundPanelScreen(Screen parent) {
        super(Text.literal("Sound Panel"));
        this.parent = parent;
        opened.add(PvpSounds.QUICK_ID);
        selected = PvpSounds.GROUPS.getFirst().events().getFirst();
        soundCursor = 1;
    }

    @Override protected void init() {
        layout = SoundPanelLayout.of(width, height);
        var l = layout;
        int x = l.x(), tabY = l.tabY(), tabH = l.tabHeight();
        int left = l.leftX(), lw = l.leftWidth(), right = l.rightX(), rw = l.rightWidth();
        int searchY = l.searchY(), searchH = l.compact() ? 14 : 16;
        int scanWidth = textRenderer.getWidth("Scan") + 10;
        int filesWidth = textRenderer.getWidth("Files") + 10;
        int filesX = right + rw - filesWidth;
        int scanX = filesX - scanWidth - 3;
        int pvpWidth = l.w() < 300 ? 40 : 50;
        int allWidth = l.w() < 300 ? 40 : 55;
        int allX = x + 11 + pvpWidth;
        int serverX = allX + allWidth + 3;
        int serverWidth = Math.max(26, Math.min(90, scanX - serverX - 5));
        String serverLabel = serverWidth >= textRenderer.getWidth("Server sounds") + 4 ? "Server sounds" : "Server";
        pvpTab = button(x + 8, tabY, pvpWidth, tabH, "PvP", Kind.TAB, () -> changeTab(Tab.PVP));
        allTab = button(allX, tabY, allWidth, tabH, "All", Kind.TAB, () -> changeTab(Tab.ALL));
        serverTab = button(serverX, tabY, serverWidth, tabH, serverLabel, Kind.TAB, () -> changeTab(Tab.SERVER));
        serverTab.setTooltip(Tooltip.of(Text.literal("Sounds from the server's active resource pack")));
        reload = button(scanX, tabY, scanWidth, tabH, "Scan", Kind.SOFT, this::reloadFromButton);
        openFolder = button(filesX, tabY, filesWidth, tabH, "Files", Kind.SOFT, this::openFolder);
        reload.setTooltip(Tooltip.of(Text.literal("Refresh the audio file list")));
        openFolder.setTooltip(Tooltip.of(Text.literal("Open the instance's sounds folder")));
        button(x + l.w() - 28, l.y() + 5, 20, 15, "X", Kind.SOFT, this::close)
                .setTooltip(Tooltip.of(Text.literal("Close Sound Panel")));

        soundSearch = addDrawableChild(new TextFieldWidget(textRenderer, left + 7, searchY, lw - 14, searchH, Text.literal("Search sounds")));
        fileSearch = addDrawableChild(new TextFieldWidget(textRenderer, right + 7, searchY, rw - 14, searchH, Text.literal("Search audio files")));
        soundSearch.setMaxLength(120);
        fileSearch.setMaxLength(120);
        soundSearch.setPlaceholder(Text.literal("Search sounds"));
        fileSearch.setPlaceholder(Text.literal("Search files"));
        soundSearch.setText(soundFilter);
        fileSearch.setText(fileFilter);
        soundSearch.setChangedListener(value -> {
            soundFilter = value; soundScroll = 0; soundCursor = 0; rebuildSounds();
        });
        fileSearch.setChangedListener(value -> {
            fileFilter = value; fileScroll = 0; fileCursor = 0; filterFiles();
        });
        int by = l.buttonY(), bh = l.buttonHeight(), gap = 3;
        int splitL = (lw - 14 - gap) / 2, splitR = (rw - 14 - gap) / 2;
        original = button(left + 7, by, splitL, bh, "Original", Kind.SECONDARY, () -> playAssignment(true));
        current  = button(left + 7 + splitL + gap, by, lw - 14 - splitL - gap, bh, "Current", Kind.SECONDARY, () -> playAssignment(false));
        reset = button(left + lw - 42, l.detailY() - 3, 35, 13, "Reset", Kind.SOFT, this::reset);
        preview = button(right + 7, by, splitR, bh, "Preview", Kind.SECONDARY, this::previewCandidate);
        apply = button(right + 7 + splitR + gap, by, rw - 14 - splitR - gap, bh, "Apply", Kind.PRIMARY, this::applyCandidate);

        buildAllSounds();
        rebuildSounds();
        readFiles();
        refreshButtons();
    }

    private void buildAllSounds() {
        var ids = new TreeSet<Identifier>();
        ids.addAll(Registries.SOUND_EVENT.getIds());
        ids.addAll(client.getSoundManager().getKeys());
        var groups = new ArrayList<>(SoundCatalog.group(ids));
        var misc = new TreeMap<String, List<SoundCatalog.Event>>();
        for (Identifier id : ids) {
            if (id.getPath().startsWith("entity.")) continue;
            String kind = id.getPath().split("\\.", 2)[0];
            misc.computeIfAbsent(kind, unused -> new ArrayList<>()).add(new SoundCatalog.Event(id, id.toString()));
        }
        for (var entry : misc.entrySet()) {
            String label = switch (entry.getKey()) {
                case "block" -> "Blocks";
                case "item" -> "Items";
                case "ui" -> "Interface";
                case "music", "music_disc" -> SoundCatalog.humanize(entry.getKey());
                default -> SoundCatalog.humanize(entry.getKey());
            };
            groups.add(new SoundCatalog.Group("other:" + entry.getKey(), label, List.copyOf(entry.getValue())));
        }
        allGroups = List.copyOf(groups);
    }

    private List<SoundCatalog.Group> serverGroups() {
        Map<String, List<SoundCatalog.Event>> groups = new TreeMap<>();
        for (var entry : PackSoundCatalog.activeSounds().entrySet()) {
            var id = entry.getKey();
            for (String source : entry.getValue()) {
                groups.computeIfAbsent(source, name -> new ArrayList<>())
                        .add(new SoundCatalog.Event(id, id.toString(), "Current server pack: " + source));
            }
        }
        var result = new ArrayList<SoundCatalog.Group>();
        int number = 0;
        for (var entry : groups.entrySet()) {
            String title = groups.size() == 1 ? "Active server pack" : "Active server pack " + (++number);
            String groupId = "server:" + entry.getKey();
            if (seenServerGroups.add(groupId)) opened.add(groupId);
            result.add(new SoundCatalog.Group(groupId, title,
                    entry.getValue().stream().sorted(Comparator.comparing(SoundCatalog.Event::label)).toList()));
        }
        return result;
    }

    private void changeTab(Tab next) {
        if (tab == next) return;
        SoundPanel.stopPreview(client);
        tab = next;
        selected = null;
        soundCursor = soundScroll = 0;
        soundSearch.setText("");
        if (next == Tab.PVP) {
            selected = PvpSounds.GROUPS.getFirst().events().getFirst();
            opened.add(PvpSounds.GROUPS.getFirst().id());
            soundCursor = 1;
        }
        focus = Pane.SOUNDS;
        clearStatus();
        rebuildSounds();
    }

    private List<SoundCatalog.Group> sourceGroups() {
        return switch (tab) {
            case PVP -> PvpSounds.GROUPS;
            case ALL -> allGroups;
            case SERVER -> serverGroups();
        };
    }

    private static boolean matches(String text, String filter) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : filter.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!lower.contains(token)) return false;
        }
        return true;
    }

    private void rebuildSounds() {
        var entries = new ArrayList<Row>();
        for (var group : sourceGroups()) {
            if (!soundFilter.isBlank() && group.id().equals(PvpSounds.QUICK_ID)) continue; // no duplicate search results
            boolean wholeGroup = matches(group.label() + " " + group.id(), soundFilter);
            List<SoundCatalog.Event> events = wholeGroup ? group.events() : group.events().stream()
                    .filter(event -> matches(group.label() + " " + event.label() + " " + event.id(), soundFilter)).toList();
            if (events.isEmpty()) continue;
            entries.add(new Row(group, null));
            if (opened.contains(group.id()) || !soundFilter.isBlank()) {
                for (var event : events) entries.add(new Row(group, event));
            }
        }
        rows = List.copyOf(entries);
        soundScroll = clamp(soundScroll, rows.size(), layout.visibleRows());
        refreshButtons();
    }

    private void readFiles() {
        var source = SoundPanel.CONFIG.sourceDir();
        try {
            Files.createDirectories(source);
            try (var paths = Files.list(source)) {
                allFiles = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> AudioConversion.isSupportedName(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
            }
        } catch (java.io.IOException e) {
            allFiles = List.of();
        }
        filterFiles();
    }

    private void filterFiles() {
        var shown = new ArrayList<Path>();
        if (candidate != null && !allFiles.contains(candidate) &&
                matches(candidate.getFileName().toString(), fileFilter)) shown.add(candidate);
        allFiles.stream().filter(p -> matches(p.getFileName().toString(), fileFilter)).forEach(shown::add);
        files = List.copyOf(shown);
        fileScroll = clamp(fileScroll, files.size(), layout.visibleRows());
        fileCursor = candidate == null ? fileCursor : files.indexOf(candidate);
        refreshButtons();
    }

    private void reloadFromButton() {
        SoundPanel.stopPreview(client);
        SoundPanel.CONFIG.discardCandidate(prepared);
        prepared = null;
        readFiles();
        setStatus(allFiles.isEmpty() ? "Add audio to the sounds folder to get started." : allFiles.size() + " audio files available.", MUTED);
    }

    private void openFolder() {
        var game = client;
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(SoundPanel.CONFIG.sourceDir());
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new UnsupportedOperationException("Desktop folder opening unsupported");
                }
                Desktop.getDesktop().open(SoundPanel.CONFIG.sourceDir().toFile());
            } catch (Exception e) {
                game.execute(() -> setStatus("Open the instance's sounds folder manually.", YELLOW));
            }
        });
    }

    private Identifier assignment(SoundCatalog.Event event) {
        return tab == Tab.SERVER ? SoundPanel.serverKey(client, event.id()) : event.id();
    }
    private SoundConfig.Clip clip(SoundCatalog.Event event) {
        return event == null ? null : SoundPanel.CONFIG.clipFor(assignment(event));
    }
    private void refreshButtons() {
        if (pvpTab == null || apply == null) return;
        pvpTab.selected = tab == Tab.PVP;
        allTab.selected = tab == Tab.ALL;
        serverTab.selected = tab == Tab.SERVER;
        original.active = selected != null;
        boolean changed = clip(selected) != null;
        current.active = changed;
        reset.active = !working && changed;
        reset.visible = changed;
        preview.active = !working && candidate != null;
        apply.active = !working && selected != null && candidate != null;
        reload.active = !working;
    }

    private void playAssignment(boolean vanilla) {
        if (selected == null) return;
        if (SoundPanel.preview(client, selected.id(), assignment(selected), vanilla)) {
            setStatus(vanilla ? "Playing original." : "Playing current assignment.", GREEN);
        } else setStatus("No playable audio for this event. Check Minecraft's master volume.", YELLOW);
    }

    private void pickCandidate(Path path) {
        if (!Objects.equals(candidate, path)) {
            SoundPanel.stopPreview(client);
            SoundPanel.CONFIG.discardCandidate(prepared);
            prepared = null;
            candidate = path;
        }
        focus = Pane.FILES;
        setStatus("File selected. Preview it, then Apply to the highlighted sound.", MUTED);
        filterFiles();
    }

    private void selectSound(int index, boolean toggleGroup) {
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        soundCursor = index;
        focus = Pane.SOUNDS;
        if (row.event() == null) {
            if (toggleGroup && soundFilter.isBlank()) {
                if (!opened.add(row.group().id())) opened.remove(row.group().id());
                rebuildSounds();
            }
            return;
        }
        selected = row.event();
        clearStatus();
        refreshButtons();
    }

    private void reset() {
        if (working || selected == null || clip(selected) == null) return;
        try {
            SoundPanel.stopPreview(client);
            SoundPanel.CONFIG.clear(assignment(selected));
            setStatus("Reset " + selected.label() + " to the original.", GREEN);
            refreshButtons();
        } catch (Exception error) { fail(error); }
    }

    private void previewCandidate() { audition(false); }
    private void applyCandidate() { audition(true); }

    private void audition(boolean commit) {
        if (candidate == null || working || (commit && selected == null)) return;
        final var path = candidate;
        final var event = selected;
        final var id = event == null ? Identifier.of("soundpanel", "preview") : event.id();
        final var target = event == null ? null : assignment(event);
        final var reusable = prepared;
        final var game = client;
        working = true;
        refreshButtons();
        setStatus(commit ? "Preparing assignment..." : "Preparing preview...", MUTED);
        CompletableFuture.supplyAsync(() -> {
            SoundConfig.Clip transientClip = null;
            try {
                if (commit && reusable == null) {
                    return new AudioResult(null, SoundPanel.CONFIG.install(target, path), null);
                }
                transientClip = reusable == null ? SoundPanel.CONFIG.prepareCandidate(path) : reusable;
                var applied = commit ? SoundPanel.CONFIG.installCandidate(target, transientClip) : null;
                return new AudioResult(transientClip, applied, null);
            } catch (Exception failure) {
                return new AudioResult(transientClip, null, failure);
            }
        }).thenAccept(result -> game.execute(() -> {
            working = false;
            // The original target was captured at button press. A result never changes a newly selected sound.
            if (removed || game.currentScreen != this) {
                SoundPanel.CONFIG.discardCandidate(result.prepared());
                return;
            }
            if (result.prepared() != null && Objects.equals(path, candidate)) prepared = result.prepared();
            else SoundPanel.CONFIG.discardCandidate(result.prepared());
            if (result.failure() != null) fail(result.failure());
            else if (result.applied() != null) {
                setStatus("Applied " + result.applied().name() + " to " + event.label() + ".", GREEN);
            } else if (Objects.equals(path, candidate)) {
                if (SoundPanel.previewCandidate(game, id, result.prepared())) {
                    setStatus("Playing file preview. Nothing has been changed.", GREEN);
                } else setStatus("Preview could not start. Check Minecraft's master volume.", YELLOW);
            }
            refreshButtons();
        }));
    }

    private record AudioResult(SoundConfig.Clip prepared, SoundConfig.Clip applied, Exception failure) {}
    private void fail(Exception error) {
        SoundPanel.LOG.warn("Sound Panel action failed", error);
        String message = error.getMessage();
        if (message == null || (!(error instanceof java.io.IOException))) message = "Could not read or save this audio file.";
        setStatus(message, RED);
    }
    private void clearStatus() { setStatus("", MUTED); }
    private void setStatus(String value, int color) { status = value; statusColor = color; }

    @Override public void onFilesDropped(List<Path> paths) {
        if (working) return;
        if (paths.size() != 1 || paths.getFirst().getFileName() == null ||
                !Files.isRegularFile(paths.getFirst()) ||
                !AudioConversion.isSupportedName(paths.getFirst().getFileName().toString())) {
            setStatus("Drop one supported audio file.", YELLOW);
            return;
        }
        fileSearch.setText("");
        pickCandidate(paths.getFirst().toAbsolutePath().normalize());
        if (fileCursor >= 0) fileScroll = reveal(fileCursor, fileScroll, layout.visibleRows());
    }

    @Override public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            Pane pane = paneAt(click.x(), click.y());
            if (pane != null && click.y() >= layout.listTop() && click.y() < layout.listBottom()) {
                int relative = ((int) click.y() - layout.listTop()) / layout.rowHeight();
                if (relative < layout.visibleRows()) {
                    setFocused(null);
                    focus = pane;
                    if (pane == Pane.SOUNDS) {
                        int index = soundScroll + relative;
                        boolean event = index < rows.size() && rows.get(index).event() != null;
                        selectSound(index, true);
                        if (doubleClick && event) playAssignment(clip(selected) == null);
                    } else if (!working && fileScroll + relative < files.size()) {
                        pickCandidate(files.get(fileScroll + relative));
                        if (doubleClick) previewCandidate();
                    }
                    return true;
                }
            }
            if (pane != null) focus = pane;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        Pane pane = paneAt(mx, my);
        if (pane != null && my >= layout.listTop() && my < layout.listBottom() && vertical != 0) {
            int amount = (int) Math.copySign(Math.max(1, Math.ceil(Math.abs(vertical) * 3)), -vertical);
            if (pane == Pane.SOUNDS) soundScroll = clamp(soundScroll + amount, rows.size(), layout.visibleRows());
            else fileScroll = clamp(fileScroll + amount, files.size(), layout.visibleRows());
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    private Pane paneAt(double xx, double yy) {
        if (yy < layout.paneY() || yy >= layout.paneBottom()) return null;
        if (xx >= layout.leftX() && xx < layout.leftX() + layout.leftWidth()) return Pane.SOUNDS;
        if (xx >= layout.rightX() && xx < layout.rightX() + layout.rightWidth()) return Pane.FILES;
        return null;
    }

    @Override public boolean keyPressed(KeyInput key) {
        int code = key.key();
        boolean enter = code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER;
        boolean command = (key.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (enter && command && apply.active) { applyCandidate(); return true; }
        if (command && code == GLFW.GLFW_KEY_F) {
            var field = focus == Pane.SOUNDS ? soundSearch : fileSearch;
            setFocused(field); field.setFocused(true);
            return true;
        }
        if (!(getFocused() instanceof ActionButton)) {
            Pane pane = getFocused() == soundSearch ? Pane.SOUNDS : getFocused() == fileSearch ? Pane.FILES : focus;
            if (code == GLFW.GLFW_KEY_UP || code == GLFW.GLFW_KEY_DOWN) {
                keyMove(pane, code == GLFW.GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            if (enter) {
                if (pane == Pane.FILES && preview.active) { previewCandidate(); return true; }
                if (pane == Pane.SOUNDS && soundCursor >= 0 && soundCursor < rows.size()) {
                    if (rows.get(soundCursor).event() == null) selectSound(soundCursor, true);
                    else playAssignment(clip(selected) == null);
                    return true;
                }
            }
        }
        return super.keyPressed(key);
    }

    private void keyMove(Pane pane, int amount) {
        focus = pane;
        if (pane == Pane.SOUNDS && !rows.isEmpty()) {
            int next = Math.max(0, Math.min(rows.size() - 1, soundCursor + amount));
            selectSound(next, false);
            soundScroll = reveal(next, soundScroll, layout.visibleRows());
        } else if (pane == Pane.FILES && !working && !files.isEmpty()) {
            int next = Math.max(0, Math.min(files.size() - 1, fileCursor + amount));
            pickCandidate(files.get(next));
            fileScroll = reveal(next, fileScroll, layout.visibleRows());
        }
    }

    private static int clamp(int start, int size, int visible) {
        return Math.max(0, Math.min(Math.max(0, size - visible), start));
    }
    private static int reveal(int index, int start, int visible) {
        if (index < start) return index;
        if (index >= start + visible) return Math.max(0, index - visible + 1);
        return start;
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        var l = layout;
        ctx.fill(0, 0, width, height, 0xb0050910);
        ctx.fill(l.x(), l.y(), l.x() + l.w(), l.y() + l.h(), BASE);
        ctx.fill(l.x(), l.y(), l.x() + l.w(), l.y() + 2, GREEN);
        write(ctx, "SOUND PANEL", l.x() + 8, l.y() + 10, 106, WHITE, true);
        if (l.w() >= 342) write(ctx, "1.0.1  /  PvP AUDIO", l.x() + 103, l.y() + 10, l.w() - 135, MUTED, false);
        pane(ctx, l.leftX(), l.leftWidth(), "1   SOUND EVENTS", focus == Pane.SOUNDS);
        pane(ctx, l.rightX(), l.rightWidth(), "2   YOUR AUDIO FILES", focus == Pane.FILES);
        renderSounds(ctx, mx, my);
        renderFiles(ctx, mx, my);
        renderDetails(ctx);
        String footer = status.isEmpty() ? (l.compact() ? "Preview first. Apply saves the change." : "Preview is temporary. Apply saves. Ctrl+Enter = Apply.")
                : status;
        write(ctx, footer, l.x() + 8, l.footerY(), l.w() - 16, status.isEmpty() ? MUTED : statusColor, false);
        super.render(ctx, mx, my, delta);
    }

    private void pane(DrawContext ctx, int x, int w, String heading, boolean active) {
        var l = layout;
        ctx.fill(x, l.paneY(), x+w, l.paneBottom(), PANEL);
        ctx.fill(x, l.paneY(), x+w, l.paneY()+1, active ? GREEN : LINE);
        int searchH = l.compact() ? 14 : 16;
        ctx.fill(x+7, l.searchY(), x+w-7, l.searchY()+searchH, BASE);
        write(ctx, heading, x+7, l.paneY()+5, w-14, active ? GREEN : MUTED, false);
    }

    private void renderSounds(DrawContext ctx, int mx, int my) {
        var l = layout;
        int x = l.leftX(), w = l.leftWidth();
        if (l.listBottom() <= l.listTop()) return;
        ctx.enableScissor(x + 4, l.listTop(), x + w - 4, l.listBottom());
        for (int i = 0; i < l.visibleRows() && i + soundScroll < rows.size(); i++) {
            int index = i + soundScroll;
            Row row = rows.get(index);
            int line = l.listTop() + i*l.rowHeight();
            boolean hovered = mx >= x + 4 && mx < x + w - 4 && my >= line && my < line + l.rowHeight();
            boolean active = row.event() != null && row.event().equals(selected);
            boolean keyboard = focus == Pane.SOUNDS && soundCursor == index;
            paintRow(ctx, x, w, line, active, hovered || keyboard, row.event() == null);
            if (row.event() == null) {
                boolean expanded = opened.contains(row.group().id()) || !soundFilter.isBlank();
                write(ctx, expanded ? "-" : "+", x+8, line+3, 9, GREEN, false);
                write(ctx, row.group().label(), x+19, line+3, w-33, WHITE, false);
            } else {
                ctx.fill(x+11, line, x+12, line+l.rowHeight(), LINE);
                var clip = clip(row.event());
                if (clip != null) ctx.fill(x+w-13, line+4, x+w-8, line+9, GREEN);
                write(ctx, row.event().label(), x+18, line+3, w-32, active ? WHITE : TEXT, false);
            }
        }
        if (rows.isEmpty()) write(ctx, tab == Tab.SERVER && soundFilter.isBlank() ? "No active server audio" : "No matching sounds",
                x+8, l.listTop()+4, w-16, MUTED, false);
        scrollbar(ctx, x+w-6, soundScroll, rows.size());
        ctx.disableScissor();
    }

    private void renderFiles(DrawContext ctx, int mx, int my) {
        var l = layout;
        int x = l.rightX(), w = l.rightWidth();
        if (l.listBottom() <= l.listTop()) return;
        ctx.enableScissor(x + 4, l.listTop(), x + w - 4, l.listBottom());
        for (int i = 0; i < l.visibleRows() && i + fileScroll < files.size(); i++) {
            Path path = files.get(i+fileScroll);
            int line = l.listTop() + i*l.rowHeight();
            boolean hovered = mx >= x + 4 && mx < x + w - 4 && my >= line && my < line + l.rowHeight();
            boolean active = path.equals(candidate);
            paintRow(ctx, x, w, line, active, hovered, false);
            String file = path.getFileName().toString();
            int dot = file.lastIndexOf('.');
            String ext = dot <= 0 ? "" : file.substring(dot+1).toUpperCase(Locale.ROOT);
            int extW = textRenderer.getWidth(ext);
            write(ctx, ext, x+w-extW-9, line+3, extW, active ? GREEN : MUTED, false);
            write(ctx, dot <= 0 ? file : file.substring(0, dot), x+8, line+3, w-extW-24, active ? WHITE : TEXT, false);
        }
        if (files.isEmpty()) {
            write(ctx, fileFilter.isBlank() ? "Drop files here" : "No matching files", x+8, l.listTop()+3, w-16, WHITE, false);
            if (fileFilter.isBlank() && l.listBottom()-l.listTop() >= 22)
                write(ctx, "Or copy into .minecraft/sounds", x+8, l.listTop()+15, w-16, MUTED, false);
        }
        scrollbar(ctx, x+w-6, fileScroll, files.size());
        ctx.disableScissor();
    }

    private void paintRow(DrawContext ctx, int x, int w, int y, boolean selected, boolean hover, boolean heading) {
        if (selected || hover || heading) ctx.fill(x+4, y, x+w-4, y+layout.rowHeight()-1, selected ? SELECT : hover ? HOVER : BUTTON);
        if (selected) ctx.fill(x+4, y, x+6, y+layout.rowHeight()-1, GREEN);
    }

    private void scrollbar(DrawContext ctx, int x, int scroll, int count) {
        var l = layout;
        int viewport = l.listBottom() - l.listTop();
        if (count <= l.visibleRows() || viewport <= 0) return;
        int thumb = Math.max(5, (int)(((double) l.visibleRows()/count)*viewport));
        int shift = (int)(((double)scroll / Math.max(1, count-l.visibleRows()))*(viewport-thumb));
        ctx.fill(x, l.listTop(), x+2, l.listBottom(), LINE);
        ctx.fill(x, l.listTop()+shift, x+2, l.listTop()+shift+thumb, MUTED);
    }

    private void renderDetails(DrawContext ctx) {
        var l = layout;
        int ly = l.detailY(), lx = l.leftX(), lw = l.leftWidth(), rx = l.rightX(), rw = l.rightWidth();
        ctx.fill(lx+5, ly-6, lx+lw-5, ly-5, LINE);
        ctx.fill(rx+5, ly-6, rx+rw-5, ly-5, LINE);
        SoundConfig.Clip existing = clip(selected);
        String name = selected == null ? "Select a sound event" : selected.label();
        if (l.compact() && existing != null) name += ": " + existing.name();
        write(ctx, name, lx+7, ly, lw-(existing == null ? 14 : 53), existing != null ? GREEN : WHITE, false);
        write(ctx, candidate == null ? "Choose a file to preview" : candidate.getFileName().toString(),
                rx+7, ly, rw-14, candidate == null ? MUTED : YELLOW, false);
        if (!l.compact()) {
            String detail = existing != null ? "Current: " + existing.name()
                    : selected == null ? "Each event has its own assignment." : selected.note();
            write(ctx, detail, lx+7, ly+12, lw-14, existing != null ? GREEN : MUTED, false);
            write(ctx, candidate == null ? "Files from this Minecraft instance" : "Preview does not change any sounds.",
                    rx+7, ly+12, rw-14, MUTED, false);
        }
    }

    private void write(DrawContext ctx, String text, int x, int y, int max, int rgb, boolean shadow) {
        if (max <= 0 || text.isEmpty()) return;
        String rendered = textRenderer.getWidth(text) <= max ? text : textRenderer.trimToWidth(text, Math.max(0, max-6)) + "...";
        ctx.drawText(textRenderer, rendered, x, y, rgb, shadow);
    }

    private enum Kind { TAB, PRIMARY, SECONDARY, SOFT }
    private ActionButton button(int x, int y, int w, int h, String label, Kind style, Runnable run) {
        return addDrawableChild(new ActionButton(x, y, w, h, label, style, run));
    }

    private final class ActionButton extends ButtonWidget {
        private final Kind kind;
        private boolean selected;
        ActionButton(int x, int y, int w, int h, String label, Kind kind, Runnable run) {
            super(x,y,w,h,net.minecraft.text.Text.literal(label), ignored -> run.run(), DEFAULT_NARRATION_SUPPLIER);
            this.kind = kind;
        }
        @Override protected void drawIcon(DrawContext ctx, int mx, int my, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean hover = isHovered() || isFocused();
            int fill = !active ? INACTIVE : kind == Kind.PRIMARY ? (hover ? 0xffa7ffda : GREEN)
                    : selected ? SELECT : hover ? HOVER : kind == Kind.TAB || kind == Kind.SECONDARY ? BUTTON : BASE;
            ctx.fill(x, y, x+w, y+h, fill);
            if (selected) ctx.fill(x, y+h-1, x+w, y+h, GREEN);
            else if (isFocused()) ctx.fill(x, y, x+w, y+1, GREEN);
            String label = getMessage().getString();
            String visible = textRenderer.trimToWidth(label, Math.max(0,w-4));
            int rgb = !active ? MUTED : kind == Kind.PRIMARY ? 0xff10261f : selected ? GREEN : WHITE;
            ctx.drawText(textRenderer, visible, x+(w-textRenderer.getWidth(visible))/2, y+(h-8)/2, rgb, false);
        }
    }

    @Override public void renderBackground(DrawContext ctx, int mx, int my, float deltaTicks) {}
    @Override public void close() { client.setScreen(parent); }
    @Override public void removed() {
        removed = true;
        SoundPanel.stopPreview(client);
        // Do not delete a prepared clip while an in-flight Apply is copying it.
        if (!working) SoundPanel.CONFIG.discardCandidate(prepared);
        super.removed();
    }
    @Override public boolean shouldPause() { return false; }
}
