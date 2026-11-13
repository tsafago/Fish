package fish.net;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class SensitiveBuffer extends ByteArrayOutputStream {
    @Override public synchronized void reset() {
        Arrays.fill(buf,0,count,(byte)0);
        super.reset();
    }
    @Override public void close() { reset(); }
}
