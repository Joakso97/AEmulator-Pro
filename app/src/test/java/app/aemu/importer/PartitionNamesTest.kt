package app.aemu.importer

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PartitionNamesTest {
    @Test fun signedSystemWithAndWithoutExtension() {
        for (n in listOf("system_signed", "system_signed.img", "SYSTEM_SIGNED", "system.img", "factoryfs.img")) assertTrue(n, PartitionNames.isSystem(n))
        for (n in listOf("system", "system_signed.xml", "cdrom_signed", "system_signed.bak")) assertFalse(n, PartitionNames.isSystem(n))
    }

    @Test fun signedBootWithAndWithoutExtension() {
        for (n in listOf("boot.img", "boot_signed", "boot_signed.img", "Boot_Signed")) assertTrue(n, PartitionNames.isBoot(n))
        for (n in listOf("boot", "bootloader.img", "boot_signed.bin", "recovery_signed")) assertFalse(n, PartitionNames.isBoot(n))
    }

    @Test fun signedRecoveryWithAndWithoutExtension() {
        for (n in listOf("recovery.img", "recovery_signed", "recovery_signed.img", "recovery.img.lz4", "recovery_signed.lz4", "recovery.emmc.win"))
            assertTrue(n, PartitionNames.isRecovery(n))
         
        for (n in listOf("recovery", "recovery-from-boot.p", "boot_signed")) assertFalse(n, PartitionNames.isRecovery(n))
    }

    @Test fun oemImageNames() {
        for (n in listOf("oem.img", "oem.ext4.img", "oem_signed", "oem_signed.img", "OEM.IMG", "oem.rfs")) assertTrue(n, PartitionNames.isOem(n))
         
        for (n in listOf("oem", "oem.xml", "oemsbl.img", "system.img", "oem_config.bin")) assertFalse(n, PartitionNames.isOem(n))
    }

    @Test fun abSlotPartitionDumpNames() {
         
        assertTrue(PartitionNames.isSystem("system_a.bin"))
        assertTrue(PartitionNames.isSystem("SYSTEM_A.IMG"))
        assertTrue(PartitionNames.isBoot("boot_a.bin"))
        assertTrue(PartitionNames.isBoot("boot_a.img"))
        assertTrue(PartitionNames.isOem("oem_a.bin"))
        assertTrue(PartitionNames.isVendor("vendor_a.bin"))
        assertTrue(PartitionNames.isVendor("vendor_a.img"))
        for (n in listOf("system_b.bin", "boot_b.bin", "oem_b.bin", "vendor_b.bin", "system_a", "boot_a")) {
            assertFalse(n, PartitionNames.isSystem(n)); assertFalse(n, PartitionNames.isBoot(n))
            assertFalse(n, PartitionNames.isOem(n)); assertFalse(n, PartitionNames.isVendor(n))
        }
         
        for (n in listOf("oem_bootloader_a.bin", "lk_a.bin", "tee_a.bin", "vbmeta_a.bin", "userdata.bin", "factory_bootloader.bin", "metadata.bin")) {
            assertFalse(n, PartitionNames.isSystem(n)); assertFalse(n, PartitionNames.isBoot(n))
            assertFalse(n, PartitionNames.isOem(n)); assertFalse(n, PartitionNames.isVendor(n))
        }
    }

    @Test fun vendorImageNames() {
        for (n in listOf("vendor.img", "vendor.ext4.img", "VENDOR.IMG")) assertTrue(n, PartitionNames.isVendor(n))
        for (n in listOf("vendor", "vendor.xml", "vendor.new.dat", "vendor_config.bin")) assertFalse(n, PartitionNames.isVendor(n))
    }

    @Test fun odinMd5MemberNames() {
         
        assertTrue(PartitionNames.isSystem(PartitionNames.stripOdinMd5("system.img.md5")))
        assertTrue(PartitionNames.isBoot(PartitionNames.stripOdinMd5("boot.img.md5")))
        assertTrue(PartitionNames.isRecovery(PartitionNames.stripOdinMd5("recovery.img.md5")))
        assertTrue(PartitionNames.isSystem(PartitionNames.stripOdinMd5("factoryfs.img.md5")))
         
        assertTrue(PartitionNames.stripOdinMd5("CODE_S5303.tar.md5") == "CODE_S5303.tar.md5")
        assertTrue(PartitionNames.stripOdinMd5("system.img") == "system.img")
        assertTrue(PartitionNames.stripOdinMd5(".md5") == ".md5")
    }

    @Test fun rawSystemDumpZip() {
        assertTrue(PartitionNames.isRawSystemDump(listOf("build.prop", "bin/", "bin/sh", "framework/framework.jar")))
        assertTrue(PartitionNames.isRawSystemDump(listOf("bin/sh", "app/a.apk", "build.prop")))
         
        assertFalse(PartitionNames.isRawSystemDump(listOf("system/build.prop", "system/bin/sh")))
        assertFalse(PartitionNames.isRawSystemDump(listOf("build.prop", "app/a.apk")))
        assertFalse(PartitionNames.isRawSystemDump(listOf("bin/sh", "etc/hosts")))
        assertFalse(PartitionNames.isRawSystemDump(listOf("__MACOSX/build.prop", "__MACOSX/bin/sh")))
        assertFalse(PartitionNames.isRawSystemDump(listOf("binary/x", "build.prop")))
    }

    @Test fun numberedRawPartitionDump() {
         
        assertTrue(PartitionNames.isSystem("26.system.img"))
        assertTrue(PartitionNames.isVendor("28.vendor.img"))
        assertTrue(PartitionNames.isBoot("25.boot.img"))
        assertTrue(PartitionNames.isRecovery("2.recovery.img"))
         
        for (n in listOf("9.uboot_bak.img", "3.misc.img", "27.cache.img", "29.vbmeta.img")) {
            assertFalse(n, PartitionNames.isSystem(n)); assertFalse(n, PartitionNames.isVendor(n)); assertFalse(n, PartitionNames.isBoot(n))
        }
         
        assertFalse(PartitionNames.isVendor("28.img"))
    }

    @Test fun anyPartitionNames() {
        assertTrue(PartitionNames.anyMount("persist.img") == "persist")
        assertTrue(PartitionNames.anyMount("misc.bin") == "misc")
        assertTrue(PartitionNames.anyMount("my_partition.ext4.img") == "my_partition")
        assertTrue(PartitionNames.anyMount("FRP.IMG") == "frp")
        assertTrue(PartitionNames.anyMount("product.img") == "system/product")
        assertTrue(PartitionNames.anyMount("system_ext.img") == "system/system_ext")
        assertTrue(PartitionNames.anyMount("modem_a.img") == "modem")
        assertTrue(PartitionNames.anyMount("modem_b.img") == "modem")
        assertTrue(PartitionNames.anyMount("notes.txt") == null)
        assertTrue(PartitionNames.anyMount("notes.txt", true) == null)
    }

    @Test fun diskImageNames() {
         
        for (n in listOf("system.img", "x/recovery_stable-channel.bin", "disk.raw", "dump", "DISK.IMG")) assertTrue(n, PartitionNames.isDiskImageName(n))
        for (n in listOf("fw.zip", "system.new.dat", "system.new.dat.br", "live.iso", "system.sin", "a.tar", "a.7z")) assertFalse(n, PartitionNames.isDiskImageName(n))
    }
}
