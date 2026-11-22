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

    public static void main(String[] a) throws Exception {
        String name = a[2]; int code = Integer.parseInt(a[3]);
        int strings = 0, ints = 0;
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
        if (ints != 4) throw new IllegalStateException("expected 4 version-code literals, got " + ints);
    }
}
