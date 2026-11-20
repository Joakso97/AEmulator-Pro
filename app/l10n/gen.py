#!/usr/bin/env python3
"""Генерирует res/values*/strings.xml и res/xml/locales_config.xml из langs/*.py.
Запуск: python app/l10n/gen.py  (английский — язык по умолчанию, values/)."""
import os, re, runpy, sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "src", "main", "res")
sys.path.insert(0, HERE)
from keys import KEYS  # noqa: E402

 
DIRS = {
    'en': 'values',
    'ar': 'values-ar',
    'az': 'values-az',
    'b+cnr': 'values-b+cnr',
    'be': 'values-be',
    'bg': 'values-bg',
    'bn': 'values-bn',
    'bs': 'values-bs',
    'cs': 'values-cs',
    'da': 'values-da',
    'de': 'values-de',
    'el': 'values-el',
    'es': 'values-es',
    'et': 'values-et',
    'fa': 'values-fa',
    'fi': 'values-fi',
    'fil': 'values-fil',
    'fr': 'values-fr',
    'ga': 'values-ga',
    'hi': 'values-hi',
    'hr': 'values-hr',
    'hu': 'values-hu',
    'hy': 'values-hy',
    'id': 'values-in',
    'is': 'values-is',
    'it': 'values-it',
    'iw': 'values-iw',
    'ja': 'values-ja',
    'ka': 'values-ka',
    'kk': 'values-kk',
    'ko': 'values-ko',
    'ky': 'values-ky',
    'lt': 'values-lt',
    'lv': 'values-lv',
    'mk': 'values-mk',
    'ms': 'values-ms',
    'my': 'values-my',
    'nb': 'values-nb',
    'nl': 'values-nl',
    'pl': 'values-pl',
    'pt-BR': 'values-pt-rBR',
    'pt-PT': 'values-pt-rPT',
    'ro': 'values-ro',
    'ru': 'values-ru',
    'sk': 'values-sk',
    'sl': 'values-sl',
    'sq': 'values-sq',
    'sr': 'values-sr',
    'sv': 'values-sv',
    'tg': 'values-tg',
    'th': 'values-th',
    'tk': 'values-tk',
    'tr': 'values-tr',
    'uk': 'values-uk',
    'ur': 'values-ur',
    'uz': 'values-uz',
    'vi': 'values-vi',
    'zh-CN': 'values-zh-rCN',
    'zh-TW': 'values-zh-rTW',
}


def esc(s: str) -> str:
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def load(code):
    return runpy.run_path(os.path.join(HERE, "langs", code + ".py"))["S"]


def main():
    en = load("en")
    missing = [k for k in KEYS if k not in en]
    assert not missing, f"en: нет ключей {missing}"
    for code, d in DIRS.items():
        s = load(code)
        lack = [k for k in KEYS if k not in s]
        extra = [k for k in s if k not in KEYS]
        if lack or extra:
            print(f"{code}: нет {lack} лишние {extra}")
        out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- создано app/l10n/gen.py, правьте langs/*.py -->", "<resources>"]
        if code == "en":
            out.append('    <string name="app_name" translatable="false">AEmulator Pro</string>')
        added_keys = set(KEYS) | {"app_name"}
        for k in KEYS:
            if k in s:
                out.append(f'    <string name="{k}">{esc(s[k])}</string>')
         
         
        existing = os.path.join(RES, d, "strings.xml")
        if os.path.isfile(existing):
            for line in open(existing, encoding="utf-8"):
                match = re.search(r'<string name="([^"]+)"', line)
                if match:
                    kname = match.group(1)
                    if kname not in added_keys:
                        added_keys.add(kname)
                        out.append(line.rstrip("\r\n"))
        out.append("</resources>\n")
        path = os.path.join(RES, d)
        os.makedirs(path, exist_ok=True)
        with open(os.path.join(path, "strings.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(out))
    tags = [c for c in DIRS]
    xml = ['<?xml version="1.0" encoding="utf-8"?>', '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">']
    xml += [f'    <locale android:name="{t}" />' for t in tags]
    xml.append("</locale-config>\n")
    os.makedirs(os.path.join(RES, "xml"), exist_ok=True)
    with open(os.path.join(RES, "xml", "locales_config.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(xml))
    print(f"готово: {len(DIRS)} языков, {len(KEYS)} строк")


if __name__ == "__main__":
    main()
