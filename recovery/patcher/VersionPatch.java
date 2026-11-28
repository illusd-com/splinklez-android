import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * The 1.1.0 bytecode has its own version inlined by kotlinc (BuildConfig.VERSION_NAME / VERSION_CODE are
 * compile-time constants): "1.1.0" in strings (UA header, 設定 › 版本, update banner) and the int 10 in the
 * update check. Rewrites them to the version being built, in place, so the jar matches app/build.gradle.kts.
 *
 * usage: VersionPatch in.jar out.jar versionName versionCode
 */
public class VersionPatch {
    static final String OLD_NAME = "1.1.0";
    static final int OLD_CODE = 10;
    // Methods whose literal 10 is the app's own versionCode (checked: the only int 10s in them),
    // plus any 10 compared with getMinSupportedVersionCode() (AppRootKt.Shell). 4 in total.
    static final Map<String, String> CODE_METHODS = Map.of(
        "cc/huhm/splinklez/ui/AppViewModel$checkUpdate$1", "invokeSuspend",
        "cc/huhm/splinklez/ui/AppViewModel", "checkUpdateNow");

    /** `10 < update.getMinSupportedVersionCode()` anywhere (the forced-update dialog in AppRootKt.Shell). */
    static boolean comparesWithMinSupported(AbstractInsnNode n) {
        AbstractInsnNode x = n.getNext();
        for (int k = 0; k < 3 && x != null; k++, x = x.getNext()) {
            if (x instanceof MethodInsnNode m && m.name.equals("getMinSupportedVersionCode")) return true;
        }
        return false;
    }

    /**
     * 1.2.3: the recovered 防災 LazyColumn hides the typhoon section when there is no storm. Insert
     * `AlertsExtrasKt.typhoonEmpty(LazyColumn)` right before the block that follows the typhoon blocks
     * (the one built from composableLambdaInstance(-299545250, ...)), so an explicit empty state can show there.
     */
    static final String HOOK_CLASS = "cc/huhm/splinklez/ui/AlertsScreenKt";
    static final String HOOK_METHOD = "AlertsScreen$lambda$24$0$0";
    static final int HOOK_KEY = -299545250;
    static final String SCOPE = "Landroidx/compose/foundation/lazy/LazyListScope;";

    static int hookAlerts(MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1, scope = -1;
        for (Type t : args) { if (t.getDescriptor().equals(SCOPE)) scope = slot; slot += t.getSize(); }
        if (scope < 0) throw new IllegalStateException("hook: no LazyListScope parameter in " + m.name);
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n instanceof LdcInsnNode l && l.cst instanceof Integer k && k == HOOK_KEY) {
                AbstractInsnNode x = n.getPrevious();
                while (x != null && !(x instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == scope)) x = x.getPrevious();
                if (x == null) throw new IllegalStateException("hook: no ALOAD scope before key");
                InsnList add = new InsnList();
                add.add(new VarInsnNode(Opcodes.ALOAD, scope));
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "cc/huhm/splinklez/extras/AlertsExtrasKt", "typhoonEmpty", "(" + SCOPE + ")V", false));
                m.instructions.insertBefore(x, add);
                return 1;
            }
        }
        return 0;
    }

    public static void main(String[] a) throws Exception {
        String name = a[2]; int code = Integer.parseInt(a[3]);
        int strings = 0, ints = 0, hooks = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(a[0]));
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] b = in.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(b).accept(cn, 0);
                    boolean changed = false;
                    for (MethodNode m : cn.methods) {
                        if (cn.name.equals(HOOK_CLASS) && m.name.equals(HOOK_METHOD)) {
                            int h = hookAlerts(m);
                            hooks += h; changed |= h > 0;
                        }
                        boolean codeHere = m.name.equals(CODE_METHODS.get(cn.name));
                        for (AbstractInsnNode n : m.instructions.toArray()) {
                            if (n instanceof LdcInsnNode l && l.cst instanceof String s && s.contains(OLD_NAME)) {
                                String t = s.replace(OLD_NAME + "（" + OLD_CODE + "）", name + "（" + code + "）").replace(OLD_NAME, name);
                                l.cst = t; strings++; changed = true;
                            } else if (n instanceof IntInsnNode i && i.getOpcode() == Opcodes.BIPUSH && i.operand == OLD_CODE
                                    && (codeHere || comparesWithMinSupported(i))) {
                                m.instructions.set(i, code <= 127 ? new IntInsnNode(Opcodes.BIPUSH, code) : new IntInsnNode(Opcodes.SIPUSH, code));
                                ints++; changed = true;
                            }
                        }
                    }
                    if (changed) {
                        ClassWriter w = new ClassWriter(0);
                        cn.accept(w);
                        b = w.toByteArray();
                    }
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(b);
                out.closeEntry();
            }
        }
        System.out.println("version patch -> " + name + " (" + code + "): " + strings + " strings, " + ints + " version-code literals");
        System.out.println("hooks: " + hooks);
        if (hooks != 1) throw new IllegalStateException("expected 1 AlertsScreen hook, got " + hooks);
        if (ints != 4) throw new IllegalStateException("expected 4 version-code literals, got " + ints);
    }
}
