// Usage: java MoveProbe.java <iterations> <mode>; mode none: create ancestor/root then move ancestor at once; mode scan: list root
// first, as the watcher's baseline scan does; mode settle: wait 50 ms before moving. Prints how many moves were denied.
import java.nio.file.*;

public class MoveProbe {
    public static void main(String[] args) throws Exception {
        int n = Integer.parseInt(args[0]);
        String mode = args[1];
        Path base = Files.createTempDirectory("kyo-move-probe-" + mode);
        int denied = 0;
        for (int i = 0; i < n; i++) {
            Path dir = base.resolve("p" + i);
            Path ancestor = dir.resolve("ancestor");
            Path root = ancestor.resolve("root");
            Files.createDirectories(root);
            if (mode.equals("scan")) {
                try (DirectoryStream<Path> s = Files.newDirectoryStream(root)) {
                    for (Path p : s) Files.readAttributes(p, "basic:*", LinkOption.NOFOLLOW_LINKS);
                }
                Files.readAttributes(root, "basic:*", LinkOption.NOFOLLOW_LINKS);
            }
            if (mode.equals("settle")) Thread.sleep(50);
            try {
                Files.move(ancestor, dir.resolve("target"));
            } catch (AccessDeniedException e) {
                denied++;
                if (denied <= 3) System.out.println(mode + " denied at " + i + ": " + e);
            }
        }
        System.out.println("PROBE " + mode + ": denied " + denied + " of " + n + " in " + base);
    }
}
