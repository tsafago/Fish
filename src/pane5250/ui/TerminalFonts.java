package pane5250.ui;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import pane5250.model.Appearance.FontChoice;
import pane5250.model.Profile;

/** Fonts are resolved outside paint; glyphs are still positioned on exact physical cells. */
public final class TerminalFonts {
    private static final FontRenderContext CONTEXT=new FontRenderContext(null,true,true);
    private static List<String> installed;
    private record FileKey(Path path,long size,long modified) {}
    private static FileKey cachedFile;
    private static Font cachedFace;
    private TerminalFonts() {}
    public static boolean fixed(Font font) {
        Font face=font.deriveFont(Font.PLAIN,16f);
        double width=face.getStringBounds("M",CONTEXT).getWidth();
        if (width<=0) return false;
        for (char c=32;c<127;c++)
            if (!face.canDisplay(c) || Math.abs(face.getStringBounds(String.valueOf(c),CONTEXT).getWidth()-width)>.05) return false;
        return true;
    }
    public static List<String> installed() {
        if (installed==null) {
            List<String> fonts=new ArrayList<>();
            for (String family:GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                if (fixed(new Font(family,Font.PLAIN,16))) fonts.add(family);
            List<String> curated=List.of("Cascadia Mono","Cascadia Code","Consolas","Lucida Console","Courier New","DejaVu Sans Mono","Liberation Mono");
            fonts.sort(Comparator.comparingInt((String name) -> curated.contains(name) ? curated.indexOf(name) : 100)
                    .thenComparing(Comparator.naturalOrder()));
            installed=List.copyOf(fonts);
        }
        return installed;
    }
    public static Font resolve(FontChoice choice) throws IOException {
        Font face;
        if (!choice.file().isEmpty()) {
            Path file=Path.of(choice.file());
            if (!Files.isRegularFile(file)) throw new IOException("Custom font file is missing: "+file);
            if (Files.size(file)>32_000_000) throw new IOException("Custom font exceeds the 32 MB safety limit.");
            FileKey key=new FileKey(file.toAbsolutePath().normalize(),Files.size(file),Files.getLastModifiedTime(file).toMillis());
            if (key.equals(cachedFile)) face=cachedFace;
            else {
                try (var input=Files.newInputStream(file)) {
                    face=Font.createFont(Font.TRUETYPE_FONT,input).deriveFont(Font.PLAIN,16f);
                } catch (FontFormatException e) { throw new IOException("Unsupported TTF/OTF font: "+e.getMessage(),e); }
                cachedFile=key; cachedFace=face;
            }
        } else {
            String family=choice.family().equals("Automatic") ? installed().stream().findFirst().orElse(Font.MONOSPACED) : choice.family();
            if (!family.equals(Font.MONOSPACED) && !installed().contains(family))
                throw new IOException("Terminal font is missing or not fixed-width: "+family);
            face=new Font(family,Font.PLAIN,16);
        }
        if (!fixed(face)) throw new IOException("Choose a monospaced font; this face has unequal printable ASCII advances.");
        return face;
    }
    public static void validate(Font face,List<Profile> profiles) throws IOException {
        Set<String> checked=new HashSet<>();
        for (Profile profile:profiles) if (checked.add(profile.codePage())) {
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
        }
    }
}
