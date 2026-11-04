package pane5250.update;

import java.io.IOException;
import java.nio.file.Path;
import pane5250.NativeLibrary;

final class UpdateSupport implements Updates.Platform {
    @Override public void inspectAndMark(Path installer) throws IOException {
        load(); inspect(installer.toAbsolutePath().toString());
    }
    @Override public void launch(Path installer,String hash,long pid,Path dataDirectory) throws IOException {
        load(); launchNative(installer.toAbsolutePath().toString(),hash,pid,dataDirectory.toAbsolutePath().toString());
    }
    private static void load() throws IOException {
        try { NativeLibrary.load(); }
        catch (IllegalStateException e) { throw new IOException("Windows updater unavailable: "+e.getMessage(),e); }
    }
    private static native void inspect(String file) throws IOException;
    private static native void launchNative(String file,String hash,long pid,String directory) throws IOException;
}
