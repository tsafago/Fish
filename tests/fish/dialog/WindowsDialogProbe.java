package fish.ui;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static java.lang.foreign.ValueLayout.*;

/** Win64 read-only probe. No input, activation, repositioning or capture of the desktop image. */
final class WindowsDialogProbe {
    private static MethodHandle fn(String dll,String name,MemoryLayout result,MemoryLayout... args) {
        var symbol=SymbolLookup.libraryLookup(dll,Arena.global()).find(name).orElseThrow();
        return Linker.nativeLinker().downcallHandle(symbol,FunctionDescriptor.of(result,args));
    }
    private static final MethodHandle find=fn("user32","FindWindowExW",ADDRESS,ADDRESS,ADDRESS,ADDRESS,ADDRESS),
        pid=fn("user32","GetWindowThreadProcessId",JAVA_INT,ADDRESS,ADDRESS),
        visible=fn("user32","IsWindowVisible",JAVA_INT,ADDRESS),
        client=fn("user32","GetClientRect",JAVA_INT,ADDRESS,ADDRESS),
        rect=fn("user32","GetWindowRect",JAVA_INT,ADDRESS,ADDRESS),
        dpiForWindow=fn("user32","GetDpiForWindow",JAVA_INT,ADDRESS),
        monitor=fn("user32","MonitorFromWindow",ADDRESS,ADDRESS,JAVA_INT),
        monitorInfo=fn("user32","GetMonitorInfoW",JAVA_INT,ADDRESS,ADDRESS),
        origin=fn("user32","ClientToScreen",JAVA_INT,ADDRESS,ADDRESS),
        hit=fn("user32","WindowFromPoint",ADDRESS,JAVA_LONG),
        ancestor=fn("user32","GetAncestor",ADDRESS,ADDRESS,JAVA_INT),
        dc=fn("user32","GetDC",ADDRESS,ADDRESS),
        release=fn("user32","ReleaseDC",JAVA_INT,ADDRESS,ADDRESS),
        pixel=fn("gdi32","GetPixel",JAVA_INT,ADDRESS,JAVA_INT,JAVA_INT),
        dpi=fn("user32","SetThreadDpiAwarenessContext",ADDRESS,ADDRESS);
    private static final MemorySegment NIL=MemorySegment.NULL;
    private static final int owner=(int)ProcessHandle.current().pid();

    record Geometry(int outerW,int outerH,int clientW,int clientH,int dpi,int monitorW,int monitorH) {}
    private static final class Query implements AutoCloseable {
        final Arena arena=Arena.ofConfined();
        final MemorySegment storage=arena.allocate(16,4),title,previousDpi;
        Query(String name) throws Throwable {
            title=arena.allocateFrom(name,StandardCharsets.UTF_16LE);
            previousDpi=(MemorySegment)dpi.invokeExact(MemorySegment.ofAddress(-4L));
        }
        MemorySegment window() throws Throwable {
            MemorySegment last=NIL;
            while (true) {
                last=(MemorySegment)find.invokeExact(NIL,last,NIL,title);
                if (last.address()==0) return NIL;
                int thread=(int)pid.invokeExact(last,storage);
                if (thread!=0 && storage.get(JAVA_INT,0)==owner && (int)visible.invokeExact(last)!=0) return last;
            }
        }
        public void close() {
            try { if (previousDpi.address()!=0) { MemorySegment unused=(MemorySegment)dpi.invokeExact(previousDpi); } }
            catch (Throwable t) { throw new AssertionError(t); }
            finally { arena.close(); }
        }
    }
    static Geometry geometry(String title) {
        try (Query q=new Query(title)) {
            var h=q.window(); var r=q.storage;
            if (h.address()==0 || (int)rect.invokeExact(h,r)==0) throw new AssertionError("No visible fixture HWND: "+title);
            int w=r.get(JAVA_INT,8)-r.get(JAVA_INT,0), ht=r.get(JAVA_INT,12)-r.get(JAVA_INT,4);
            if ((int)client.invokeExact(h,r)==0) throw new AssertionError("No client rectangle");
            var info=q.arena.allocate(40,4);
            info.set(JAVA_INT,0,40);
            if ((int)monitorInfo.invokeExact((MemorySegment)monitor.invokeExact(h,2),info)==0)
                throw new AssertionError("No physical monitor bounds");
            return new Geometry(w,ht,r.get(JAVA_INT,8),r.get(JAVA_INT,12),(int)dpiForWindow.invokeExact(h),
                    info.get(JAVA_INT,12)-info.get(JAVA_INT,4),info.get(JAVA_INT,16)-info.get(JAVA_INT,8));
        } catch (Throwable t) { throw new AssertionError(t); }
    }

    static final class Paint implements AutoCloseable {
        private final CountDownLatch armed=new CountDownLatch(1);
        private final Thread worker;
        private volatile boolean done;
        private volatile Throwable error;
        int samples,white,first=-1,last=-1;

        Paint(String title) throws InterruptedException {
            worker=Thread.ofPlatform().daemon().name("own-window-pixel-sampler").start(() -> {
                try (Query q=new Query(title)) {
                    if (q.window().address()!=0) throw new AssertionError("Must arm before opening "+title);
                    MemorySegment screen=(MemorySegment)dc.invokeExact(NIL);
                    try {
                        armed.countDown();
                        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                        MemorySegment h=NIL;
                        while (!done && System.nanoTime()<end) {
                            if (h.address()==0) h=q.window();
                            MemorySegment r=q.storage;
                            if (h.address()!=0 && (int)client.invokeExact(h,r)!=0) {
                                // Empty middle of the caption, away from title, borders and buttons.
                                int width=r.get(JAVA_INT,8);
                                r.set(JAVA_INT,0,(int)(width*.65)); r.set(JAVA_INT,4,10);
                                if (width>250 && (int)origin.invokeExact(h,r)!=0) {
                                    int x=r.get(JAVA_INT,0),y=r.get(JAVA_INT,4);
                                    long point=(long)y<<32 | Integer.toUnsignedLong(x);
                                    var top=(MemorySegment)ancestor.invokeExact((MemorySegment)hit.invokeExact(point),2);
                                    if (top.equals(h)) {
                                        int c=(int)pixel.invokeExact(screen,x,y);
                                        if (c!=-1) {
                                            int v=((c&255)+(c>>>8&255)+(c>>>16&255))/3;
                                            if (samples++==0) first=v;
                                            last=v; if (v>=225) white++;
                                        }
                                    }
                                }
                            }
                            Thread.sleep(2);
                        }
                    } finally { int unused=(int)release.invokeExact(NIL,screen); }
                } catch (Throwable t) { error=t; armed.countDown(); }
            });
            if (!armed.await(3,TimeUnit.SECONDS) || error!=null) throw new AssertionError("Sampler did not arm",error);
        }
        public void close() throws InterruptedException {
            done=true; worker.join(3000);
            if (worker.isAlive() || error!=null) throw new AssertionError("Sampler failed",error);
        }
    }
}
