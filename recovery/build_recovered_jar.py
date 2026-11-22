#!/usr/bin/env python3
"""Rebuild app/libs/splinklez-1.1.0-recovered.jar from the 1.1.0 release bytecode,
leaving out every class whose Kotlin source file has been rewritten under app/src/main/java.

Inputs (in this folder):
  splinklez-1.1.0-classes.jar  dex2jar output of the signed 1.1.0 APK, cc/huhm/splinklez/** only
                               (R and BuildConfig removed; AGP regenerates them)
  class_source.json            class -> Kotlin source file name, from the release smali (.source)
"""
import json, os, re, subprocess, zipfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HERE = os.path.join(ROOT, "recovery")
src_map = json.load(open(os.path.join(HERE, "class_source.json")))

# Source files that now exist as Kotlin source: path relative to app/src/main/java, e.g. cc/huhm/splinklez/data/Models.kt
rewritten = set()
for dp, _, fs in os.walk(os.path.join(ROOT, "app/src/main/java")):
    for f in fs:
        if f.endswith(".kt"):
            rel = os.path.relpath(os.path.join(dp, f), os.path.join(ROOT, "app/src/main/java"))
            rewritten.add(rel)

def source_of(cls):
    top = cls.split("$")[0]
    s = src_map.get(top)
    if s in (None, "D8$$SyntheticClass"):
        return None
    return os.path.dirname(top) + "/" + s

final = os.path.join(ROOT, "app/libs/splinklez-1.1.0-recovered.jar")
out = final + ".tmp"
kept = dropped = 0
with zipfile.ZipFile(os.path.join(HERE, "splinklez-1.1.0-classes.jar")) as src, \
        zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name in sorted(src.namelist()):
        if not name.endswith(".class"):
            continue
        cls = name[:-6]
        if source_of(cls) in rewritten:
            dropped += 1
            continue
        z.writestr(name, src.read(name))
        kept += 1
print(f"recovered jar: kept {kept} classes, dropped {dropped} (rewritten as source: {len(rewritten)} files)")

# kotlinc inlined BuildConfig.VERSION_NAME/VERSION_CODE (1.1.0 / 10) into these classes; rewrite them to this build's version.
gradle = open(os.path.join(ROOT, "app/build.gradle.kts")).read()
vname = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
vcode = re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1)
cp = os.pathsep.join([os.path.join(HERE, "patcher"), os.path.join(HERE, "lib/asm-9.8.jar"), os.path.join(HERE, "lib/asm-tree-9.8.jar")])
java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
src = os.path.join(HERE, "patcher", "VersionPatch.java")
cls = os.path.join(HERE, "patcher", "VersionPatch.class")
if not os.path.exists(cls) or os.path.getmtime(cls) < os.path.getmtime(src):
    subprocess.run([java + "c", "-cp", cp, "-d", os.path.join(HERE, "patcher"), src], check=True)
subprocess.run([java, "-cp", cp, "VersionPatch", out, final, vname, vcode], check=True)
os.remove(out)
