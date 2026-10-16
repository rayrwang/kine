import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Static check of kine's mixins against one Minecraft release, without starting the game (the `mixinCheck` task of
 * `./gradlew build`, versions/mixincheck.gradle): every mixin's target class exists; every injector's method selector
 * matches a method of the target; every INVOKE / INVOKE_ASSIGN / FIELD / NEW point's target appears in that method's
 * code; an @Inject handler's parameters match the target's; every @Accessor, @Invoker, @Shadow and @Overwrite names
 * something that exists. What Mixin would refuse at load ("could not find any targets") shows here for all mixins at
 * once. And every Mixin or MixinExtras annotation in a mixin is written in the shape the oldest Mixin the jar admits
 * declares (versions/table.gradle): an element written as an array that Mixin declares as one value fails there at load
 * (Fabric Mixin 0.17.4 made @Redirect's `at` an array; 0.17.2 reads one At, and MixinExtras' redirect transformer
 * casts it: ClassCastException, ArrayList to AnnotationNode).
 *
 * usage: java -cp asm.jar:asm-tree.jar MixinCheck.java <kine classes dirs, ':'-separated> <mixin list file>
 *        <classpath file> [<floor Mixin jars file>]
 *   mixin list file: one mixin class name per line (ai.rrw.kine.mixin.X), as the running release would apply them
 *   classpath file: one jar or classes dir per line (the release's game jar, its libraries, Fabric API, loader)
 *   floor Mixin jars file: the oldest admitted Mixin's and MixinExtras' jars, one a line (the annotations' shapes)
 */
public class MixinCheck {
    static final Map<String, byte[]> CLASSES = new HashMap<>();
    static final Map<String, ClassNode> NODES = new HashMap<>();
    static final Map<String, byte[]> FLOOR = new HashMap<>();
    static final List<String> problems = new ArrayList<>();
    static final List<Path> kine = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        for (String d : a[0].split(File.pathSeparator)) if (!d.isEmpty()) kine.add(Paths.get(d));
        List<String> mixins = Files.readAllLines(Paths.get(a[1])).stream().map(String::trim)
            .filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        for (String jar : Files.readAllLines(Paths.get(a[2]))) readJar(jar.trim(), CLASSES);
        if (a.length > 3) for (String jar : Files.readAllLines(Paths.get(a[3]))) readJar(jar.trim(), FLOOR);
        int n = 0;
        for (String m : mixins) { check(m); n++; }
        for (String p : problems) System.out.println("PROBLEM " + p);
        System.out.println(n + " mixins checked, " + problems.size() + " problems");
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    static void readJar(String jar, Map<String, byte[]> into) throws IOException {
        if (jar.isEmpty() || !jar.endsWith(".jar") || !new File(jar).exists()) return;
        try (ZipFile z = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF"))
                    into.putIfAbsent(e.getName().substring(0, e.getName().length() - 6), z.getInputStream(e).readAllBytes());
            }
        }
    }

    static ClassNode node(String internal) {
        if (NODES.containsKey(internal)) return NODES.get(internal);
        byte[] b = CLASSES.get(internal);
        ClassNode cn = null;
        if (b != null) { cn = new ClassNode(); new ClassReader(b).accept(cn, 0); }
        NODES.put(internal, cn);
        return cn;
    }

    static ClassNode kineNode(String name) throws IOException {
        for (Path dir : kine) {
            Path f = dir.resolve(name.replace('.', '/') + ".class");
            if (!Files.exists(f)) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(Files.readAllBytes(f)).accept(cn, 0);
            return cn;
        }
        throw new FileNotFoundException(name + " in none of " + kine);
    }

    // ---- the annotations' shapes, against the floor Mixin's own annotation classes

    static final Map<String, ClassNode> FLOOR_NODES = new HashMap<>();

    static ClassNode floorNode(String internal) {
        return FLOOR_NODES.computeIfAbsent(internal, k -> {
            byte[] b = FLOOR.get(k);
            if (b == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, 0);
            return cn;
        });
    }

    /** A Mixin or MixinExtras annotation (and the ones nested in it): an element written as an array where the floor
     *  declares one value is a problem; an annotation the floor doesn't have is one too. */
    static void checkShape(String where, AnnotationNode an) {
        if (FLOOR.isEmpty()) return;
        if (!an.desc.startsWith("Lorg/spongepowered/asm/mixin/") && !an.desc.startsWith("Lcom/llamalad7/mixinextras/")) return;
        String internal = an.desc.substring(1, an.desc.length() - 1);
        ClassNode type = floorNode(internal);
        if (type == null) { problems.add(where + ": @" + simple(an.desc) + " is not in the oldest Mixin the jar admits"); return; }
        if (an.values == null) return;
        for (int k = 0; k < an.values.size(); k += 2) {
            String key = (String) an.values.get(k);
            Object v = an.values.get(k + 1);
            MethodNode el = null;
            for (MethodNode m : type.methods) if (m.name.equals(key) && m.desc.startsWith("()")) el = m;
            if (el == null) { problems.add(where + ": @" + simple(an.desc) + "." + key + " is not in the oldest Mixin the jar admits"); continue; }
            boolean array = Type.getReturnType(el.desc).getSort() == Type.ARRAY;
            if (v instanceof List<?> l && !array)
                problems.add(where + ": @" + simple(an.desc) + "." + key + " is written as an array, but the oldest Mixin the jar"
                    + " admits declares one value (compiled against a newer Mixin; versions/table.gradle)");
            for (Object o : list(v)) if (o instanceof AnnotationNode nested) checkShape(where, nested);
        }
    }

    static String simple(String desc) {
        String s = desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1);
        return s.replace('$', '.');
    }

    @SuppressWarnings("unchecked")
    static void checkShapes(String mixin, ClassNode mx) {
        for (AnnotationNode an : anns(mx.visibleAnnotations, mx.invisibleAnnotations)) checkShape(mixin, an);
        for (FieldNode f : mx.fields)
            for (AnnotationNode an : anns(f.visibleAnnotations, f.invisibleAnnotations)) checkShape(mixin + "." + f.name, an);
        for (MethodNode m : mx.methods) {
            for (AnnotationNode an : anns(m.visibleAnnotations, m.invisibleAnnotations)) checkShape(mixin + "." + m.name, an);
            for (List<AnnotationNode>[] ps : List.of(m.visibleParameterAnnotations == null ? new List[0] : m.visibleParameterAnnotations,
                                                     m.invisibleParameterAnnotations == null ? new List[0] : m.invisibleParameterAnnotations))
                for (List<AnnotationNode> p : ps) if (p != null) for (AnnotationNode an : p) checkShape(mixin + "." + m.name, an);
        }
    }

    static List<AnnotationNode> anns(List<AnnotationNode> v, List<AnnotationNode> i) {
        List<AnnotationNode> out = new ArrayList<>();
        if (v != null) out.addAll(v);
        if (i != null) out.addAll(i);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object val(AnnotationNode an, String key) {
        if (an.values == null) return null;
        for (int k = 0; k < an.values.size(); k += 2) if (an.values.get(k).equals(key)) return an.values.get(k + 1);
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return (List<Object>) l;
        return List.of(o);
    }

    static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;");

    static void check(String mixin) throws IOException {
        ClassNode mx = kineNode(mixin);
        String shortName = mixin.substring(mixin.lastIndexOf('.') + 1);
        checkShapes(shortName, mx);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode an : anns(mx.visibleAnnotations, mx.invisibleAnnotations)) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (Object t : list(val(an, "value"))) targets.add(((Type) t).getInternalName());
            for (Object t : list(val(an, "targets"))) targets.add(((String) t).replace('.', '/'));
        }
        if (targets.isEmpty()) { problems.add(shortName + ": no @Mixin targets"); return; }
        for (String t : targets) {
            ClassNode target = node(t);
            if (target == null) { problems.add(shortName + ": target class " + t + " not found"); continue; }
            for (FieldNode f : mx.fields)
                for (AnnotationNode an : anns(f.visibleAnnotations, f.invisibleAnnotations))
                    if (an.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;") && findField(target, f.name) == null)
                        problems.add(shortName + ": @Shadow field " + f.name + " not in " + t);
            for (MethodNode m : mx.methods)
                for (AnnotationNode an : anns(m.visibleAnnotations, m.invisibleAnnotations)) checkMethod(shortName, t, target, m, an);
        }
    }

    static FieldNode findField(ClassNode c, String name) {
        for (ClassNode k = c; k != null; k = k.superName == null ? null : node(k.superName))
            for (FieldNode f : k.fields) if (f.name.equals(name)) return f;
        return null;
    }

    static List<MethodNode> findMethods(ClassNode c, String name, String desc, boolean inherited) {
        List<MethodNode> out = new ArrayList<>();
        for (ClassNode k = c; k != null; k = inherited && k.superName != null ? node(k.superName) : null) {
            for (MethodNode m : k.methods)
                if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) out.add(m);
            if (!inherited) break;
        }
        return out;
    }

    static String accessorName(String method, String prefixGet) {
        for (String p : new String[]{"get", "set", "is", "call", "invoke"})
            if (method.startsWith(p) && method.length() > p.length()) {
                String r = method.substring(p.length());
                return Character.toLowerCase(r.charAt(0)) + r.substring(1);
            }
        return method;
    }

    static void checkMethod(String mixin, String t, ClassNode target, MethodNode m, AnnotationNode an) {
        String d = an.desc;
        if (d.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
            String name = (String) val(an, "value");
            if (name == null) name = accessorName(m.name.replace("kine$", ""), "get");
            if (findField(target, name) == null) problems.add(mixin + ": @Accessor field " + name + " not in " + t);
            return;
        }
        if (d.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
            String name = (String) val(an, "value");
            if (name == null) name = accessorName(m.name.replace("kine$", ""), "call");
            if (findMethods(target, name, null, true).isEmpty()) problems.add(mixin + ": @Invoker method " + name + " not in " + t);
            return;
        }
        if (d.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
            if (findMethods(target, m.name, m.desc, true).isEmpty()) problems.add(mixin + ": @Shadow method " + m.name + m.desc + " not in " + t);
            return;
        }
        if (d.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
            if (findMethods(target, m.name, m.desc, false).isEmpty()) problems.add(mixin + ": @Overwrite method " + m.name + m.desc + " not in " + t);
            return;
        }
        if (!INJECTORS.contains(d)) return;
        String kind = d.substring(d.lastIndexOf('/') + 1, d.length() - 1);
        List<MethodNode> selected = new ArrayList<>();
        for (Object sel : list(val(an, "method"))) {
            String s = (String) sel;
            String name = s, desc = null;
            int paren = s.indexOf('(');
            if (paren >= 0) { name = s.substring(0, paren); desc = s.substring(paren); }
            if (name.contains("*")) { selected.addAll(target.methods); continue; }
            List<MethodNode> ms = findMethods(target, name, desc, false);
            if (ms.isEmpty()) problems.add(mixin + "." + m.name + ": @" + kind + " method \"" + s + "\" not in " + t);
            selected.addAll(ms);
        }
        if (selected.isEmpty()) return;
        // the injection points
        List<Object> ats = new ArrayList<>(list(val(an, "at")));
        for (Object o : ats) {
            if (!(o instanceof AnnotationNode at)) continue;
            String value = (String) val(at, "value");
            String tgt = (String) val(at, "target");
            if (tgt == null || value == null) continue;
            if (!(value.equals("INVOKE") || value.equals("INVOKE_ASSIGN") || value.equals("FIELD") || value.equals("NEW"))) continue;
            boolean found = false;
            for (MethodNode sm : selected) if (contains(sm, value, tgt)) { found = true; break; }
            if (!found) problems.add(mixin + "." + m.name + ": @" + kind + " point " + value + " " + tgt + " not in " + t + "." + ((String) list(val(an, "method")).get(0)));
        }
        // @Inject handler parameters: the target's, a prefix of them, or none, then CallbackInfo[Returnable]
        if (kind.equals("Inject")) {
            Type[] h = Type.getArgumentTypes(m.desc);
            int ci = -1;
            for (int i = 0; i < h.length; i++) if (h[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) { ci = i; break; }
            if (ci < 0) { problems.add(mixin + "." + m.name + ": @Inject handler without CallbackInfo"); return; }
            for (MethodNode sm : selected) {
                Type[] p = Type.getArgumentTypes(sm.desc);
                if (ci > 0) {
                    boolean ok = ci <= p.length;
                    for (int i = 0; ok && i < ci; i++) ok = p[i].equals(h[i]);
                    if (!ok) problems.add(mixin + "." + m.name + ": @Inject handler args " + Arrays.toString(Arrays.copyOf(h, ci))
                        + " don't match " + t + "." + sm.name + sm.desc);
                }
            }
        }
    }

    static boolean contains(MethodNode mn, String value, String tgt) {
        // target forms: Lowner;name(desc)ret  or  Lowner;name:Ltype;  or (NEW) Lowner; / owner
        for (AbstractInsnNode in : mn.instructions) {
            if ((value.equals("INVOKE") || value.equals("INVOKE_ASSIGN")) && in instanceof MethodInsnNode mi) {
                if (tgt.equals("L" + mi.owner + ";" + mi.name + mi.desc) || tgt.equals(mi.owner + "." + mi.name + mi.desc)) return true;
                if (!tgt.contains("(") && tgt.endsWith(";" + mi.name)) return true;
            } else if (value.equals("FIELD") && in instanceof FieldInsnNode fi) {
                if (tgt.equals("L" + fi.owner + ";" + fi.name + ":" + fi.desc)) return true;
            } else if (value.equals("NEW") && in instanceof TypeInsnNode ti && in.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                if (tgt.equals("L" + ti.desc + ";") || tgt.equals(ti.desc)) return true;
            }
        }
        return false;
    }
}
