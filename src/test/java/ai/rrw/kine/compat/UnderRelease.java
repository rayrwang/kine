package ai.rrw.kine.compat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Runs test code under another release's rules. Rules fixes each flag when the class starts, from the release in the
 * kine.minecraft property, so one test JVM sees only the development release's. Here the kine classes are loaded
 * afresh in a class loader of their own: Rules starts there with the release asked for (and GameVersion is handed a
 * table that names it, since the build's table holds only the development family), and the code given runs there, so
 * the copies it calls take that release's branches. For unit tests of a rule's other side; a release's recorded traces
 * are replayed with traceTest.
 */
public final class UnderRelease {
    private UnderRelease() {}

    /** What a fresh `check` gives when run with the rules of `release`. The check is a public class with a public
     *  no-argument constructor in a kine package; it and every kine class it uses are loaded anew for the run, so it
     *  should hand back plain values (numbers, arrays, strings), not kine objects. */
    public static synchronized <T> T get(String release, Class<? extends Supplier<T>> check) {
        URL[] urls = {location(Rules.class), location(check)};
        try (Loader loader = new Loader(urls, UnderRelease.class.getClassLoader(), release)) {
            String was = System.getProperty("kine.minecraft");
            System.setProperty("kine.minecraft", release);
            try {
                Class.forName(Rules.class.getName(), true, loader);
            } finally {
                if (was == null) System.clearProperty("kine.minecraft");
                else System.setProperty("kine.minecraft", was);
            }
            @SuppressWarnings("unchecked")
            Supplier<T> s = (Supplier<T>) Class.forName(check.getName(), true, loader).getDeclaredConstructor().newInstance();
            return s.get();
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("can't run " + check.getName() + " under " + release, e);
        }
    }

    private static URL location(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }

    /** Loads kine's classes itself, before asking its parent (everything else comes from the parent), and serves
     *  GameVersion a one-row table naming the release. */
    private static final class Loader extends URLClassLoader {
        private final String release;

        Loader(URL[] urls, ClassLoader parent, String release) {
            super(urls, parent);
            this.release = release;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("ai.rrw.kine.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name.equals("kine-epochs.txt"))
                return new ByteArrayInputStream((release + " test\n").getBytes(StandardCharsets.UTF_8));
            return super.getResourceAsStream(name);
        }
    }
}
