#!/usr/bin/env python3
"""Apply narrow, checked build adaptations to the pinned CM11 source tree."""
import hashlib
import pathlib
import shutil
import sys
import zipfile

base = pathlib.Path(sys.argv[1]).resolve()
src = base / 'src'
recipe = pathlib.Path(__file__).resolve().parent

 
 
apk = base / 'tools/Term.apk'
expected = '4cbf6adb273a6afa01f7d5f4ea97ac22b5a97ce1a34c000f2ef308a6383e8821'
if hashlib.sha256(apk.read_bytes()).hexdigest() != expected:
    raise SystemExit('Terminal APK checksum mismatch')
shutil.copytree(recipe / 'device/aemulator/aess', src / 'device/aemulator/aess', dirs_exist_ok=True)
proprietary = src / 'vendor/cm/proprietary'
proprietary.mkdir(exist_ok=True)
shutil.copyfile(apk, proprietary / 'Term.apk')
with zipfile.ZipFile(apk) as archive:
    for name in ('libjackpal-androidterm5.so', 'libjackpal-termexec2.so'):
        target = proprietary / 'lib/armeabi' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.read('lib/armeabi/' + name))

common = src / 'vendor/cm/config/common.mk'
old = 'vendor/cm/proprietary/lib/armeabi/libjackpal-androidterm4.so:system/lib/libjackpal-androidterm4.so'
new = ('vendor/cm/proprietary/lib/armeabi/libjackpal-androidterm5.so:system/lib/libjackpal-androidterm5.so \\\n'
       '    vendor/cm/proprietary/lib/armeabi/libjackpal-termexec2.so:system/lib/libjackpal-termexec2.so')
content = common.read_text()
if old in content:
    content = content.replace(old, new)
elif new not in content:
    raise SystemExit('Unexpected CM terminal packaging configuration')
 
 
for package in ('CMUpdater', 'CMFota', 'CMAccount'):
    content = content.replace('    ' + package + ' \\\n', '')
content = content.replace('    CMAccount\n', '    # AESS omits retired CM account service.\n')
common.write_text(content)
 
 
minui = src / 'bootable/recovery/minui/Android.mk'
old_kernel = '''# Some devices need kernel headers for graphics
ifeq ($(TARGET_PREBUILT_KERNEL),)
  LOCAL_ADDITIONAL_DEPENDENCIES := $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr
  LOCAL_C_INCLUDES += $(TARGET_OUT_INTERMEDIATES)/KERNEL_OBJ/usr/include
endif'''
new_kernel = '''# AESS: kernel-less userspace uses the platform's exported Linux headers.
ifneq ($(TARGET_NO_KERNEL),true)
''' + old_kernel + '\nendif'
content = minui.read_text()
if new_kernel not in content:
    if old_kernel not in content:
        raise SystemExit('Unexpected CM minui kernel-header rule')
    minui.write_text(content.replace(old_kernel, new_kernel))
 
 
 
ime = src / 'frameworks/base/services/java/com/android/server/InputMethodManagerService.java'
old_ime = '''            defIm = InputMethodUtils.getMostApplicableDefaultIME(
                    mSettings.getEnabledInputMethodListLocked());
            Slog.i(TAG, "No default found, using " + defIm.getId());'''
new_ime = '''            defIm = InputMethodUtils.getMostApplicableDefaultIME(
                    mSettings.getEnabledInputMethodListLocked());
            // AESS: first boot may have discovered IMEs but no enabled default yet.
            if (defIm == null) {
                defIm = InputMethodUtils.getMostApplicableDefaultIME(mMethodList);
                if (defIm != null) {
                    setInputMethodEnabledLocked(defIm.getId(), true);
                }
            }
            if (defIm != null) {
                Slog.i(TAG, "No default found, using " + defIm.getId());
            } else {
                Slog.w(TAG, "No applicable default input method available yet");
            }'''
content = ime.read_text()
if new_ime not in content:
    if old_ime not in content:
        raise SystemExit('Unexpected CM11 default input-method selection')
    ime.write_text(content.replace(old_ime, new_ime))
print('AESS target, terminal packaging and CM11 first-boot IME fix installed.')
