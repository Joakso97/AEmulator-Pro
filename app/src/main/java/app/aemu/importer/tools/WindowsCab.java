
package app.aemu.importer.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;


public final class WindowsCab {
    private WindowsCab() {}

    
    public interface Source {
        long length();
        
        void readFully(long pos, byte[] b, int off, int len) throws IOException;
    }

    private static LgCab.Src adapt(final Source s) {
        return new LgCab.Src() {
            @Override public long length() { return s.length(); }
            @Override public void readFully(long pos, byte[] b, int off, int len) throws IOException { s.readFully(pos, b, off, len); }
            @Override public void close() {}
        };
    }

    private static boolean isWim(LgCab.Member m) {
        return m.name.toLowerCase(Locale.ROOT).endsWith(".wim");
    }

    
    public static boolean hasWim(Source s) {
        if (s.length() < 64) return false;
        try (LgCab.Src src = adapt(s)) {
            byte[] h = new byte[8];                 
            src.readFully(0, h, 0, 8);
            if (!LgCab.isCabHeader(h)) return false;
            LgCab.Cab cab = LgCab.parse(src);
            for (LgCab.Member m : cab.members) if (isWim(m)) return true;
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    
    public static List<File> extractWims(Source s, File outDir, Consumer<String> log) throws IOException {
        try (LgCab.Src src = adapt(s)) {
            LgCab.Cab cab = LgCab.parse(src);
            List<String> names = new ArrayList<>();
            for (LgCab.Member m : cab.members) if (isWim(m)) names.add(m.name.replace('\\', '/'));
            if (names.isEmpty()) throw new IOException("CAB: no .wim file inside");
            log.accept("cab: " + cab.members.size() + " members, " + cab.folders.size() + " folder(s); unpacking " + names);
            return LgCab.extract(src, cab, outDir, WindowsCab::isWim, log);
        }
    }
}
