#!/usr/bin/env python3
"""Static consistency checks for the app source tree (no Gradle, no Android SDK needed).

Added for AEmulator Plus (2026-11-16): the app is routinely edited in environments where the Android build
cannot run, and the errors below all fail the build (or crash at runtime) without being visible in a single file:

  * a duplicate top-level declaration in one package (Kotlin redeclaration, "Conflicting declarations")
  * a stale editor/backup copy under src/ that the Kotlin or Java source set still picks up (`Foo.kt.kt` declares
    `class Foo` a second time)
  * `R.<type>.<name>` used from code but absent from res/ (unresolved reference)
  * an activity/service/receiver/provider in AndroidManifest.xml whose class does not exist
  * `@string/...` and friends in the manifest that are not in res/
  * the same resource name defined twice in one values file/directory (aapt: "duplicate value for resource")
  * a translation whose format arguments differ from the default string (String.format throws at runtime)

This is a heuristic checker, not a compiler: it reads names, not types. It never writes to the tree.

Usage (from the checkout root):  python3 tools/check_sources.py
Exit code: 0 = clean (warnings allowed), 1 = errors found.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src")
MAIN = os.path.join(APP, "main")
RES = os.path.join(MAIN, "res")
MANIFEST = os.path.join(MAIN, "AndroidManifest.xml")

KT = (".kt",)
JAVA = (".java",)
SRC_SETS = ("main", "test", "androidTest")

errors = []
warnings = []


def err(where, msg):
    errors.append("%s: %s" % (where, msg))


def warn(where, msg):
    warnings.append("%s: %s" % (where, msg))


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def walk(base):
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for name in sorted(filenames):
            yield os.path.join(dirpath, name)


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


 
 
 
STALE_COMPILED = re.compile(r"\.(kt|java)\.(kt|java)$", re.I)    
STALE_COPY = re.compile(r"\.(kt|java)\.\w+$|~$", re.I)           
STALE_DIR = {".idea", ".gradle", ".git", "__pycache__"}


def check_stale_copies():
    for src_set in SRC_SETS:
        base = os.path.join(APP, src_set)
        if not os.path.isdir(base):
            continue
        for path in walk(base):
            name = os.path.basename(path)
            if STALE_COMPILED.search(name):
                err(rel(path), "stale copy with a compile-relevant name (the source set compiles it: duplicates/redeclaration)")
            elif STALE_COPY.search(name):
                warn(rel(path), "stale editor/backup copy in the source tree (delete it)")


 
 
 
KT_MODIFIERS = (
    "public", "private", "protected", "internal", "open", "abstract", "sealed", "data", "enum", "annotation",
    "value", "inline", "expect", "actual", "external", "suspend", "operator", "infix", "tailrec", "const",
    "lateinit", "override", "companion",
)
KT_DECL = re.compile(r"^(?P<mods>(?:[a-z]+\s+)*)(?P<kind>class|interface|object|typealias|val|var)\s+(?P<name>[A-Za-z_]\w*)")
KT_PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
JAVA_DECL = re.compile(r"^(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum)\s+(?P<name>[A-Za-z_]\w*)", re.M)
JAVA_PACKAGE = re.compile(r"^package\s+([\w.]+)\s*;", re.M)


def strip_kt_comments(text):
    out = []
    in_block = 0
    for line in text.splitlines():
        s = line
        res = ""
        i = 0
        while i < len(s):
            if in_block:
                if s.startswith("*/", i):
                    in_block -= 1
                    i += 2
                    continue
                i += 1
                continue
            if s.startswith("/*", i):
                in_block += 1
                i += 2
                continue
            if s.startswith("//", i):
                break
            res += s[i]
            i += 1
        out.append(res)
    return "\n".join(out)


def kotlin_declarations(text):
    text = strip_kt_comments(text)
    for line in text.splitlines():
        if not line or line[0] in " \t":
            continue   
        m = KT_DECL.match(line)
        if not m:
            continue
        mods = m.group("mods").split()
        if any(w not in KT_MODIFIERS for w in mods):
            continue
        yield m.group("kind"), m.group("name"), mods


def check_duplicate_declarations():
    seen = defaultdict(list)   
    for src_set in SRC_SETS:
        base = os.path.join(APP, src_set)
        if not os.path.isdir(base):
            continue
        for path in walk(base):
            if path.endswith(KT):
                text = read(path)
                pm = KT_PACKAGE.search(text)
                pkg = pm.group(1) if pm else ""
                for kind, name, mods in kotlin_declarations(text):
                    if "private" in mods:
                        continue   
                    seen[(src_set, pkg, kind, name)].append(path)
            elif path.endswith(JAVA):
                text = strip_kt_comments(read(path))
                pm = JAVA_PACKAGE.search(text)
                pkg = pm.group(1) if pm else ""
                for m in JAVA_DECL.finditer(text):
                    seen[(src_set, pkg, "class", m.group("name"))].append(path)
    for (src_set, pkg, kind, name), files in sorted(seen.items()):
        if len(files) > 1:
            err(", ".join(rel(f) for f in files),
                "duplicate top-level %s %s in package %r (%s)" % (kind, name, pkg or "<default>", src_set))


 
 
 
VALUES_TAGS = {
    "string": "string", "color": "color", "dimen": "dimen", "style": "style", "bool": "bool",
    "integer": "integer", "array": "array", "string-array": "array", "integer-array": "array",
    "plurals": "plurals", "attr": "attr", "drawable": "drawable", "fraction": "fraction",
    "declare-styleable": "styleable",
}
FILE_TYPES = {
    "drawable": "drawable", "mipmap": "mipmap", "layout": "layout", "xml": "xml", "raw": "raw",
    "anim": "anim", "animator": "animator", "menu": "menu", "font": "font", "color": "color",
    "transition": "transition", "navigation": "navigation", "interpolator": "interpolator",
}
RES_NAME_BAD = re.compile(r"[^A-Za-z0-9_]")


def res_type_of_dir(dirname):
    base = dirname.split("-", 1)[0]
    return FILE_TYPES.get(base)


def resource_index():
    """type -> name -> [defining file] (first definition wins for reporting)."""
    index = defaultdict(lambda: defaultdict(list))
    dupes = []
    if not os.path.isdir(RES):
        return index, dupes
    for dirname in sorted(os.listdir(RES)):
        full = os.path.join(RES, dirname)
        if not os.path.isdir(full):
            continue
        if dirname.startswith("values"):
            for path in walk(full):
                if not path.endswith(".xml"):
                    continue
                try:
                    tree = ET.parse(path)
                except ET.ParseError as e:
                    err(rel(path), "not well-formed XML: %s" % e)
                    continue
                local_dupes = defaultdict(list)
                for elem in tree.getroot():
                    if not isinstance(elem.tag, str):
                        continue
                    rtype = None
                    name = elem.get("name")
                    if elem.tag == "item":
                        rtype = elem.get("type")
                    else:
                        rtype = VALUES_TAGS.get(elem.tag)
                    if not rtype or not name:
                        continue
                    index[rtype][name].append(path)
                    local_dupes[(rtype, name)].append(path)
                for (rtype, name), files in local_dupes.items():
                    if len(files) > 1:
                        dupes.append((rel(path), rtype, name))
        else:
            rtype = res_type_of_dir(dirname)
            if not rtype:
                continue
            for path in walk(full):
                name = os.path.basename(path)
                name = re.sub(r"\.9\.png$", "", name)
                name = os.path.splitext(name)[0]
                index[rtype][RES_NAME_BAD.sub("_", name)].append(path)
    return index, dupes


R_REF = re.compile(r"\bR\.([a-z][a-z0-9_]*)\.([A-Za-z_]\w*)")
BUILTIN_R_TYPES = {"id"}   


def manifest_and_res_id_names():
    """@+id/foo definitions, so R.id code references can be checked too."""
    ids = set()
    pattern = re.compile(r"@\+id/([A-Za-z_]\w*)")
    for base in (RES, MAIN):
        for path in walk(base):
            if path.endswith(".xml"):
                for m in pattern.finditer(read(path)):
                    ids.add(m.group(1))
    return ids


def check_code_resource_references(index):
    missing = defaultdict(list)
    ids = manifest_and_res_id_names()
    for src_set in SRC_SETS:
        base = os.path.join(APP, src_set)
        if not os.path.isdir(base):
            continue
        for path in walk(base):
            if path.endswith(KT):
                text = strip_kt_comments(read(path))
            elif path.endswith(JAVA):
                text = strip_kt_comments(read(path))
            else:
                continue
            for m in R_REF.finditer(text):
                rtype, name = m.group(1), m.group(2)
                if rtype == "id":
                    if name not in ids:
                        missing[(rtype, name)].append(path)
                elif rtype in index and name not in index[rtype]:
                    missing[(rtype, name)].append(path)
    for (rtype, name), files in sorted(missing.items()):
        err(rel(files[0]), "R.%s.%s is used (%d site(s)) but not defined in res/" % (rtype, name, len(files)))


def check_res_cross_references(index):
    """@type/name inside res XML and the manifest, e.g. @style/Foo, @drawable/bar."""
    ref = re.compile(r"@(?!\+|\?|\*|android:)([a-z][a-z0-9_]*)/([A-Za-z_][\w.]*)")
    for base in (RES,):
        for path in walk(base):
            if not path.endswith(".xml"):
                continue
            for m in ref.finditer(read(path)):
                rtype, name = m.group(1), m.group(2)
                if rtype == "id":
                    continue
                if rtype in index and name not in index[rtype]:
                    err(rel(path), "@%s/%s is referenced but not defined" % (rtype, name))


 
 
 
MANIFEST_COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider", "application")


def declared_types():
    """Every top-level type the main source set declares (file names say nothing: VmActivitySlots.kt declares four)."""
    types = set()
    for sub in ("java", "kotlin"):
        base = os.path.join(MAIN, sub)
        if not os.path.isdir(base):
            continue
        for path in walk(base):
            if path.endswith(KT):
                text = read(path)
                pm = KT_PACKAGE.search(text)
                pkg = pm.group(1) if pm else ""
                for _kind, name, _mods in kotlin_declarations(text):
                    types.add((pkg + "." + name).strip("."))
            elif path.endswith(JAVA):
                text = strip_kt_comments(read(path))
                pm = JAVA_PACKAGE.search(text)
                pkg = pm.group(1) if pm else ""
                for m in JAVA_DECL.finditer(text):
                    types.add((pkg + "." + m.group("name")).strip("."))
    return types


def check_manifest(index):
    if not os.path.isfile(MANIFEST):
        err("app/src/main/AndroidManifest.xml", "missing")
        return
    text = read(MANIFEST)
    try:
        root = ET.fromstring(text)
    except ET.ParseError as e:
        err("app/src/main/AndroidManifest.xml", "not well-formed XML: %s" % e)
        return
    android = "{http://schemas.android.com/apk/res/android}"
    types = declared_types()

    def class_exists(fqn):
        if fqn.startswith(("android.", "androidx.", "com.android.")):
            return True
        return fqn in types

    package = root.get("package") or ""
    if not package:
         
        gradle = os.path.join(ROOT, "app", "build.gradle.kts")
        if os.path.isfile(gradle):
            m = re.search(r'^\s*namespace\s*=\s*"([\w.]+)"', read(gradle), re.M)
            package = m.group(1) if m else ""
    for elem in root.iter():
        if not isinstance(elem.tag, str):
            continue
        tag = elem.tag.split("}")[-1]
        name = elem.get(android + "name")
        if tag in MANIFEST_COMPONENTS and name:
            if "$" in name:
                continue
            if name.startswith("."):
                fqn = package + name
            elif "." not in name:
                fqn = (package + "." + name).strip(".")
            else:
                fqn = name
            if tag != "application" and not class_exists(fqn):
                err("app/src/main/AndroidManifest.xml", "<%s android:name=%r> has no source file (%s)" % (tag, name, fqn))
        for attr, value in elem.attrib.items():
            m = re.match(r"@(?!android:)([a-z][a-z0-9_]*)/([A-Za-z_][\w.]*)", value)
            if m:
                rtype, rname = m.group(1), m.group(2)
                if rtype in index and rname not in index[rtype]:
                    err("app/src/main/AndroidManifest.xml", "attribute %s=%r references a missing resource" % (attr, value))


 
 
 
FORMAT = re.compile(r"%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]|%(?!%)[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")
STRING_TAG = re.compile(r"<string\b([^>]*)>(.*?)</string>", re.S)
NAME_ATTR = re.compile(r'\bname="([^"]+)"')


def format_args(text):
    args = set()
    positional = False
    for i, m in enumerate(FORMAT.finditer(text)):
        if m.group(1):
            args.add(int(m.group(1)))
            positional = True
        else:
            args.add(i + 1)
    return args if positional else (set(range(1, len(args) + 1)) if args else set())


def check_translations():
    default = os.path.join(RES, "values", "strings.xml")
    if not os.path.isfile(default):
        return
    base_text = read(default)
    base = {}
    for m in STRING_TAG.finditer(base_text):
        name = NAME_ATTR.search(m.group(1))
        if name:
            base[name.group(1)] = m.group(2)
    for dirname in sorted(os.listdir(RES)):
        full = os.path.join(RES, dirname)
        if not dirname.startswith("values-") or not os.path.isdir(full):
            continue
        path = os.path.join(full, "strings.xml")
        if not os.path.isfile(path):
            continue
        for m in STRING_TAG.finditer(read(path)):
            name = NAME_ATTR.search(m.group(1))
            if not name or name.group(1) not in base:
                continue
            want = format_args(base[name.group(1)])
            got = format_args(m.group(2))
            if want != got:
                warn(rel(path), "%s: format arguments %s, default has %s"
                     % (name.group(1), sorted(got) or "none", sorted(want) or "none"))


def check_duplicate_values(dupes):
    for where, rtype, name in dupes:
        err(where, "duplicate %s %r in one values directory (aapt rejects it)" % (rtype, name))


 
def main():
    if not os.path.isdir(APP):
        sys.exit("run from the checkout root (app/src not found)")
    check_stale_copies()
    check_duplicate_declarations()
    index, dupes = resource_index()
    check_duplicate_values(dupes)
    check_code_resource_references(index)
    check_res_cross_references(index)
    check_manifest(index)
    check_translations()
    for w in warnings:
        print("warning: " + w)
    for e in errors:
        print("error: " + e)
    print("%d error(s), %d warning(s); %d resource name(s) indexed in %d type(s)"
          % (len(errors), len(warnings), sum(len(v) for v in index.values()), len(index)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
