package pane5250.update;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.LongConsumer;
import pane5250.model.UpdateSettings;

public final class Updates implements AutoCloseable {
    public static final URI API=URI.create("https://api.github.com/repos/tsafago/Fish/releases?per_page=30");
    public static final URI WEBSITE=URI.create("https://github.com/tsafago/Fish/releases");
    public static final long MAX_INSTALLER=128L*1024*1024;
    public record Version(int major,int minor,int patch) implements Comparable<Version> {
        public Version {
            if (major<0 || minor<0 || patch<0 || major>999999 || minor>999999 || patch>999999)
                throw new IllegalArgumentException("Invalid update version.");
        }
        public static Version parse(String text) {
            if (!text.matches("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
                throw new IllegalArgumentException("Expected numeric X.Y.Z update version.");
            String[] parts=(text.startsWith("v") ? text.substring(1) : text).split("\\.");
            return new Version(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
        }
        @Override public int compareTo(Version value) {
            int result=Integer.compare(major,value.major);
            if (result==0) result=Integer.compare(minor,value.minor);
            return result==0 ? Integer.compare(patch,value.patch) : result;
        }
        @Override public String toString() { return major+"."+minor+"."+patch; }
    }
    public record Asset(String name,URI url,long size,String hash) {}
    public record Release(Version version,String tag,boolean preview,URI url,String notes,Asset installer,Asset checksums) {}
    public record Download(Release release,Path directory,Path installer) implements AutoCloseable {
        @Override public void close() throws IOException {
            Files.deleteIfExists(installer.resolveSibling(installer.getFileName()+".part"));
            Files.deleteIfExists(installer);
            Files.deleteIfExists(directory);
        }
    }
    public static final class Cancel {
        private String reason;
        private Runnable abort;
        public synchronized void check() throws InterruptedIOException {
            if (reason!=null || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException(reason==null ? "Update cancelled." : reason);
        }
        public void cancel() { cancel("Update cancelled."); }
        synchronized void cancel(String message) { if (reason==null) reason=message; if (abort!=null) abort.run(); abort=null; }
        synchronized void abortWith(Runnable action) throws InterruptedIOException {
            abort=action;
            if (reason!=null) { action.run(); check(); }
        }
        synchronized void detach() { abort=null; }
    }
    public interface Transport extends AutoCloseable {
        void fetch(URI url,OutputStream target,long limit,Cancel cancel,LongConsumer progress) throws IOException;
        @Override default void close() {}
    }
    public interface Platform {
        void inspectAndMark(Path installer) throws IOException;
        void launch(Path installer,String hash,long pid,Path dataDirectory) throws IOException;
    }
    private final Transport transport;
    private final Platform platform;
    private final Path stagingRoot;
    public Updates(Transport transport,Platform platform) { this(transport,platform,null); }
    public Updates(Transport transport,Platform platform,Path stagingRoot) {
        this.transport=transport; this.platform=platform; this.stagingRoot=stagingRoot;
    }
    public Updates() { this(new UpdateHttp(),new UpdateSupport()); }
    public Optional<Release> check(String current,UpdateSettings.Channel channel,Cancel cancel) throws IOException {
        return select(read(API,2_000_000,cancel),Version.parse(current),channel);
    }
    public static Optional<Release> select(byte[] json,Version current,UpdateSettings.Channel channel) throws IOException {
        if (json.length>2_000_000) throw new IOException("Release JSON exceeds its size limit.");
        Object parsed=Json.parse(utf8(json));
        if (!(parsed instanceof List<?> list) || list.size()>100) throw new IOException("Invalid GitHub release list.");
        Release newest=null;
        for (Object value:list) {
            try {
                Release release=release(value);
                Version version=release.version;
                if (version.compareTo(current)<=0 || version.major!=current.major) continue;
                if (channel==UpdateSettings.Channel.PREVIEW_PATCHES && version.minor!=current.minor) continue;
                if (release.preview && (channel==UpdateSettings.Channel.STABLE || version.minor!=current.minor)) continue;
                if (newest==null || version.compareTo(newest.version)>0
                        || version.equals(newest.version) && newest.preview && !release.preview) newest=release;
            } catch (IllegalArgumentException | ArithmeticException e) {
                System.getLogger(Updates.class.getName()).log(System.Logger.Level.DEBUG,"Ignored ineligible release: "+e.getMessage());
            }
        }
        return Optional.ofNullable(newest);
    }
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static Map<?,?> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected object.");
        return map;
    }
    private static String text(Map<?,?> map,String key) {
        if (!(map.get(key) instanceof String value)) throw new IllegalArgumentException("Missing release field: "+key);
        return value;
    }
    private static boolean flag(Map<?,?> map,String key) {
        if (!(map.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Invalid release flag.");
        return value;
    }
    private static Release release(Object value) {
        Map<?,?> map=object(value);
        if (flag(map,"draft")) throw new IllegalArgumentException("Draft.");
        String tag=text(map,"tag_name"); Version version=Version.parse(tag);
        URI url=URI.create("https://github.com/tsafago/Fish/releases/tag/"+tag);
        if (!url.toString().equals(text(map,"html_url"))) throw new IllegalArgumentException("Wrong release origin.");
        if (!(map.get("assets") instanceof List<?> assets) || assets.size()>64) throw new IllegalArgumentException("Invalid asset list.");
        Map<String,Asset> indexed=new HashMap<>();
        for (Object entry:assets) {
            Map<?,?> data=object(entry);
            String name=text(data,"name");
            if (!name.equals("Fish-"+version+"-setup.exe") && !name.equals("SHA256SUMS.txt")
                    && !name.equals("Fish-"+version+"-windows-x64.zip")) continue;
            if (!(data.get("size") instanceof BigDecimal size)) throw new IllegalArgumentException("Missing asset size.");
            long length=size.longValueExact();
            String hash=text(data,"digest");
            if (!hash.matches("sha256:[a-f0-9]{64}") || length<1 || length>MAX_INSTALLER
                    || !text(data,"state").equals("uploaded")) throw new IllegalArgumentException("Invalid asset metadata.");
            URI asset=URI.create("https://github.com/tsafago/Fish/releases/download/"+tag+"/"+name);
            if (!asset.toString().equals(text(data,"browser_download_url"))) throw new IllegalArgumentException("Wrong asset origin.");
            if (indexed.put(name,new Asset(name,asset,length,hash.substring(7)))!=null)
                throw new IllegalArgumentException("Duplicate release asset.");
        }
        Asset installer=indexed.get("Fish-"+version+"-setup.exe"),checksums=indexed.get("SHA256SUMS.txt");
        if (installer==null || checksums==null || checksums.size>32768 || !indexed.containsKey("Fish-"+version+"-windows-x64.zip"))
            throw new IllegalArgumentException("Missing matching Windows x64 installer/checksums.");
        String notes=map.get("body") instanceof String body ? body : "";
        return new Release(version,tag,flag(map,"prerelease"),url,notes.substring(0,Math.min(notes.length(),20000)),installer,checksums);
    }
    private byte[] read(URI url,int limit,Cancel cancel) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        transport.fetch(url,out,limit,cancel,bytes -> {});
        if (out.size()>limit) throw new IOException("Update metadata exceeds its size limit.");
        cancel.check(); return out.toByteArray();
    }
    public Download download(Release release,Cancel cancel,LongConsumer progress) throws IOException {
        validate(release);
        byte[] manifest=read(release.checksums.url,32768,cancel);
        if (manifest.length!=release.checksums.size || !hash(manifest).equals(release.checksums.hash))
            throw new IOException("Release checksum file failed its published size/SHA-256 check.");
        Map<String,String> hashes=new HashMap<>();
        for (String line:utf8(manifest).split("\\R")) {
            if (line.isBlank()) continue;
            if (!line.matches("[a-fA-F0-9]{64} [ *][A-Za-z0-9][A-Za-z0-9._-]{0,100}"))
                throw new IOException("Invalid release checksum line.");
            if (hashes.put(line.substring(66),line.substring(0,64).toLowerCase(Locale.ROOT))!=null)
                throw new IOException("Duplicate release checksum filename.");
        }
        if (!release.installer.hash.equals(hashes.get(release.installer.name)))
            throw new IOException("Installer SHA-256 disagrees with SHA256SUMS.txt.");
        Path directory=stagingRoot==null ? Files.createTempDirectory("Fish-update-") : Files.createTempDirectory(stagingRoot,"Fish-update-");
        Download download=new Download(release,directory,directory.resolve(release.installer.name));
        boolean success=false;
        try {
            Path partial=download.installer.resolveSibling(release.installer.name+".part");
            try (OutputStream out=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW)) {
                transport.fetch(release.installer.url,out,release.installer.size,cancel,progress);
            }
            cancel.check();
            verify(partial,release.installer);
            Files.move(partial,download.installer,StandardCopyOption.ATOMIC_MOVE);
            platform.inspectAndMark(download.installer);
            cancel.check(); success=true; return download;
        } finally { if (!success) download.close(); }
    }
    public void launch(Download download,long pid,Path dataDirectory) throws IOException {
        validate(download.release);
        if (!download.installer.toAbsolutePath().normalize().getParent().equals(download.directory.toAbsolutePath().normalize())
                || !download.installer.getFileName().toString().equals(download.release.installer.name)
                || Files.isSymbolicLink(download.directory))
            throw new IOException("Invalid update staging directory.");
        verify(download.installer,download.release.installer);
        platform.launch(download.installer,download.release.installer.hash,pid,dataDirectory);
    }
    private static void validate(Release release) throws IOException {
        try {
            if (!Version.parse(release.tag).equals(release.version)
                    || !release.url.equals(URI.create("https://github.com/tsafago/Fish/releases/tag/"+release.tag)))
                throw new IllegalArgumentException();
            for (Asset asset:List.of(release.installer,release.checksums)) {
                if (!asset.hash.matches("[a-f0-9]{64}") || asset.size<1 || asset.size>MAX_INSTALLER
                        || !asset.url.equals(URI.create("https://github.com/tsafago/Fish/releases/download/"+release.tag+"/"+asset.name)))
                    throw new IllegalArgumentException();
            }
            if (!release.installer.name.equals("Fish-"+release.version+"-setup.exe")
                    || !release.checksums.name.equals("SHA256SUMS.txt") || release.checksums.size>32768)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid release origin, names or integrity metadata. Nothing was downloaded.",e);
        }
    }
    private static void verify(Path file,Asset asset) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || Files.size(file)!=asset.size || !hash(file).equals(asset.hash))
            throw new IOException("Installer failed its published size/SHA-256 check. Nothing was run.");
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hash(Path file) throws IOException {
        try (InputStream input=Files.newInputStream(file)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int size;
            while ((size=input.read(buffer))!=-1) digest.update(buffer,0,size);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public void close() { transport.close(); }
}
