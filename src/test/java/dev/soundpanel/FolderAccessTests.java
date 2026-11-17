package dev.soundpanel;

import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FolderAccessTests {
    static void run() throws IOException {
        Path root = Files.createTempDirectory("soundpanel-folder");
        Path profile = root.resolve("My Prism instance & friends").resolve(".minecraft");
        Path sounds = FolderAccess.sourceFor(profile);
        check(sounds.equals(profile.resolve("sounds").toAbsolutePath().normalize()), "real instance path");
        check(sounds.isAbsolute(), "path copied as absolute");
        check(FolderAccess.ensure(sounds).equals(sounds) && Files.isDirectory(sounds), "can create instance and sounds dirs");
        check(FolderAccess.ensure(sounds).equals(sounds), "ensure is idempotent");
        check(!Files.exists(root.resolve("sounds")), "doesn't create in launcher working directory");

        var commands = new ArrayList<List<String>>();
        FolderAccess.launch(sounds, Util.OperatingSystem.WINDOWS, p -> {
            check(p.equals(sounds), "ShellExecute receives exact local path");
            return 33;
        }, (args, checked) -> commands.add(args));
        check(commands.isEmpty(), "Windows native acceptance does not launch redundant Explorer");

        FolderAccess.launch(sounds, Util.OperatingSystem.WINDOWS, p -> 5, (args, checked) -> {
            check(!checked, "Explorer dispatch is enough to report an opening request");
            commands.add(List.copyOf(args));
        });
        check(commands.size() == 1 && Path.of(commands.getFirst().getFirst()).getFileName().toString().equalsIgnoreCase("explorer.exe")
                && commands.getFirst().size() == 2 && commands.getFirst().get(1).equals(sounds.toString()),
                "Explorer receives a single path argument even with spaces and ampersand");

        commands.clear();
        FolderAccess.launch(sounds, Util.OperatingSystem.WINDOWS, p -> {
            throw new UnsatisfiedLinkError("test missing Shell DLL");
        }, (args, checked) -> {
            commands.add(List.copyOf(args));
            if (commands.size() == 1) throw new IOException("test missing Explorer command");
        });
        check(commands.size() == 2 && commands.get(1).equals(List.of(
                "rundll32.exe", "url.dll,FileProtocolHandler", sounds.toUri().toASCIIString())),
                "falls back to Minecraft-style Windows file URI if Explorer cannot start");

        commands.clear();
        FolderAccess.launch(sounds, Util.OperatingSystem.LINUX, p -> { throw new AssertionError("Windows shell on Linux"); },
                (args, checked) -> {
                    check(checked, "freedesktop opener exit is checked");
                    commands.add(List.copyOf(args));
                    if (commands.size() == 1) throw new IOException("no xdg desktop handler");
                });
        check(commands.equals(List.of(List.of("xdg-open", sounds.toString()), List.of("gio", "open", sounds.toString()))),
                "Linux falls back from xdg-open to gio with exact path");

        try {
            FolderAccess.launch(sounds, Util.OperatingSystem.WINDOWS, p -> 31, (args, checked) -> { throw new IOException("offline test"); });
            throw new AssertionError("nothing opened but no failure surfaced");
        } catch (IOException expected) {
            check(expected.getSuppressed().length == 1, "opening failure keeps cause");
        }
        Path collision = Files.writeString(profile.resolve("not-a-folder"), "unexpected file");
        try {
            FolderAccess.ensure(collision);
            throw new AssertionError("regular file was treated as a directory");
        } catch (IOException expected) {
            check(Files.isRegularFile(collision), "creation failure leaves existing contents untouched");
        }
        System.out.println("PASS: custom launcher folder creation, safe Windows path arguments, fallback and actionable failures");
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
