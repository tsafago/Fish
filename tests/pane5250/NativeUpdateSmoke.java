package pane5250.update;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/** Inspects only a copied local build; never executes setup or acquires credentials. */
public final class NativeUpdateSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Expected a locally built x64 executable fixture.");
        Path root=Files.createTempDirectory("Fish-update-native-fixture-");
        try {
            Path file=root.resolve("copied-fixture.exe");
            Files.copy(Path.of(args[0]),file);
            new UpdateSupport().inspectAndMark(file);
            try (var zone=new java.io.FileInputStream(file+":Zone.Identifier")) {
                if (!new String(zone.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("ZoneId=3"))
                    throw new AssertionError("Internet-origin mark missing.");
            }
            Files.write(file,new byte[2048],StandardOpenOption.TRUNCATE_EXISTING);
            boolean rejected=false;
            try { new UpdateSupport().inspectAndMark(file); }
            catch (IOException expected) { rejected=true; }
            if (!rejected) throw new AssertionError("Malformed PE was accepted.");
        } finally {
            try (var paths=Files.walk(root)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Native unsigned-PE/integrity policy and Internet-origin checks passed; no installer executed.");
    }
}
