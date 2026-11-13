package fish.update;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;

/** Inspects only a copied local build; verifies PE policy and Zone.Identifier stripping on hash-verified updates without executing setup. */
public final class NativeUpdateSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Expected a locally built x64 executable fixture.");
        Path root=Files.createTempDirectory("Fish-update-native-fixture-");
        try {
            Path file=root.resolve("copied-fixture.exe");
            Files.copy(Path.of(args[0]),file);
            try (var zone=new FileOutputStream(file+":Zone.Identifier")) {
                zone.write("[ZoneTransfer]\r\nZoneId=3\r\n".getBytes(StandardCharsets.UTF_8));
            }
            new UpdateSupport().inspectAndMark(file);
            boolean zonePresent;
            try (var zone=new java.io.FileInputStream(file+":Zone.Identifier")) {
                zonePresent=zone.read()>=0;
            } catch (IOException stripped) {
                zonePresent=false;
            }
            if (zonePresent) throw new AssertionError("Zone.Identifier was not stripped from SHA-256-verified executable.");
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
        System.out.println("Native PE integrity policy and SmartScreen Zone.Identifier stripping checks passed; no installer executed.");
    }
}
