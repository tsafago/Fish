package fish.ui;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import fish.model.Appearance.FontChoice;
import fish.model.Profile;

/** Fonts are resolved outside paint; glyphs are still positioned on exact physical cells. */
public final class TerminalFonts {
    private static final FontRenderContext CONTEXT=new FontRenderContext(null,true,true);
    private static volatile List<String> installed;
    private record FileKey(Path path,long size,long modified) {}
    private static volatile FileKey cachedFile;
    private static volatile Font cachedFace;
    private static final Map<String,Font> RESOLVED_FAMILY=new ConcurrentHashMap<>();
    private record ValidatedKey(int id,Font face,String codePage) {}
    private static final Set<ValidatedKey> VALIDATED=ConcurrentHashMap.newKeySet();
    private static final List<String> CURATED=List.of("Cascadia Mono","Cascadia Code","Consolas","Lucida Console","Courier New","DejaVu Sans Mono","Liberation Mono");
    private TerminalFonts() {}
    public static boolean fixed(Font font) {
        Font face=font.getStyle()==Font.PLAIN && font.getSize2D()==16f ? font : font.deriveFont(Font.PLAIN,16f);
        double width=face.getStringBounds("M",CONTEXT).getWidth();
        if (width<=0) return false;
        for (char c=32;c<127;c++)
            if (!face.canDisplay(c) || Math.abs(face.getStringBounds(String.valueOf(c),CONTEXT).getWidth()-width)>.05) return false;
        return true;
    }
    public static List<String> installed() {
        List<String> snapshot=installed;
        if (snapshot==null) {
            synchronized (TerminalFonts.class) {
                snapshot=installed;
                if (snapshot==null) {
                    List<String> fonts=new ArrayList<>();
                    for (String family:GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                        if (fixed(new Font(family,Font.PLAIN,16))) fonts.add(family);
                    fonts.sort(Comparator.comparingInt((String name) -> CURATED.contains(name) ? CURATED.indexOf(name) : 100)
                            .thenComparing(Comparator.naturalOrder()));
                    snapshot=List.copyOf(fonts);
                    installed=snapshot;
                }
            }
        }
        return snapshot;
    }
    public static Font resolve(FontChoice choice) throws IOException {
        if (!choice.file().isEmpty()) {
            Path file=Path.of(choice.file());
            if (!Files.isRegularFile(file)) throw new IOException("Custom font file is missing: "+file);
            long size=Files.size(file);
            if (size>32_000_000) throw new IOException("Custom font exceeds the 32 MB safety limit.");
            FileKey key=new FileKey(file.toAbsolutePath().normalize(),size,Files.getLastModifiedTime(file).toMillis());
            Font face;
            if (key.equals(cachedFile)) {
                face=cachedFace;
            } else {
                try (var input=Files.newInputStream(file)) {
                    face=Font.createFont(Font.TRUETYPE_FONT,input).deriveFont(Font.PLAIN,16f);
                } catch (FontFormatException e) { throw new IOException("Unsupported TTF/OTF font: "+e.getMessage(),e); }
                if (!fixed(face)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
                cachedFile=key; cachedFace=face;
                return face;
            }
            if (!fixed(face)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
            return face;
        }
        String requested=choice.family();
        Font cached=RESOLVED_FAMILY.get(requested);
        if (cached!=null) return cached;
        if (requested.equals("Automatic")) {
            for (String candidate:CURATED) {
                Font probe=new Font(candidate,Font.PLAIN,16);
                if ((probe.getFamily(Locale.ROOT).equalsIgnoreCase(candidate) || probe.getFamily().equalsIgnoreCase(candidate))
                        && fixed(probe)) {
                    RESOLVED_FAMILY.put("Automatic",probe);
                    RESOLVED_FAMILY.putIfAbsent(probe.getFamily(),probe);
                    return probe;
                }
            }
            String fallback=installed().stream().findFirst().orElse(Font.MONOSPACED);
            Font face=new Font(fallback,Font.PLAIN,16);
            if (!fixed(face)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
            RESOLVED_FAMILY.put("Automatic",face);
            return face;
        }
        if (requested.equals(Font.MONOSPACED)) {
            Font face=new Font(Font.MONOSPACED,Font.PLAIN,16);
            if (!fixed(face)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
            RESOLVED_FAMILY.put(requested,face);
            return face;
        }
        Font probe=new Font(requested,Font.PLAIN,16);
        boolean directMatch=probe.getFamily(Locale.ROOT).equalsIgnoreCase(requested) || probe.getFamily().equalsIgnoreCase(requested);
        if (directMatch) {
            if (!fixed(probe)) throw new IOException("Terminal font is missing or not fixed-width: "+requested);
            RESOLVED_FAMILY.put(requested,probe);
            return probe;
        }
        if (!installed().contains(requested))
            throw new IOException("Terminal font is missing or not fixed-width: "+requested);
        if (!fixed(probe)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
        RESOLVED_FAMILY.put(requested,probe);
        return probe;
    }
    public static void validate(Font face,List<Profile> profiles) throws IOException {
        int faceId=System.identityHashCode(face);
        Set<String> checked=new HashSet<>();
        for (Profile profile:profiles) if (checked.add(profile.codePage())) {
            ValidatedKey vKey=new ValidatedKey(faceId,face,profile.codePage());
            if (VALIDATED.contains(vKey)) continue;
            byte[] bytes=new byte[256]; for (int i=0;i<256;i++) bytes[i]=(byte)i;
            String glyphs=new String(bytes,profile.charset());
            double cell=face.getStringBounds("M",CONTEXT).getWidth();
            for (int i=0;i<glyphs.length();i++) {
                char c=glyphs.charAt(i);
                if (!Character.isISOControl(c) && c!='\ufffd' && !face.canDisplay(c))
                    throw new IOException(face.getFamily()+" lacks U+"+String.format("%04X",(int)c)
                            +" required by code page "+profile.codePage()+". Choose another font.");
                if (!Character.isISOControl(c) && c!='\ufffd' && face.getStringBounds(String.valueOf(c),CONTEXT).getWidth()>cell+.05)
                    throw new IOException(face.getFamily()+" has a glyph wider than one cell (U+"+String.format("%04X",(int)c)
                            +", code page "+profile.codePage()+"). Choose another font.");
            }
            VALIDATED.add(vKey);
        }
    }
}
