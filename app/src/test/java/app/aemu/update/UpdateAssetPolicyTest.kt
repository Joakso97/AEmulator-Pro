package app.aemu.update

import org.junit.Assert.*
import org.junit.Test

class UpdateAssetPolicyTest {
    private val plus = "AEmulator-Plus-0.0.0.2.1-app.aemu.plus.apk"
    private val publishedApk = "AEmulatorPro.apk"

    @Test fun choosesPlusApkRegardlessOfAssetOrder() {
        assertEquals(plus, listOf(plus, "notes.txt").firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
        assertEquals(plus, listOf("notes.txt", plus).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
    }
    @Test fun acceptsPublishedApkNameCaseInsensitively() {
        assertTrue(UpdateAssetPolicy.matches(publishedApk, "app.aemu.plus"))
        assertTrue(UpdateAssetPolicy.matches(publishedApk.uppercase(), "app.aemu.plus"))
        assertTrue(UpdateAssetPolicy.matches("AEmulator-Pro-0.0.0.3.21-app.aemu.plus.apk", "app.aemu.plus"))
    }
    @Test fun choosesPublishedApkRegardlessOfAssetOrder() {
        val unrelated = listOf("other.apk", "source.zip", "$publishedApk.asc")
        assertEquals(publishedApk, (unrelated + publishedApk).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
        assertEquals(publishedApk, (listOf(publishedApk) + unrelated).firstOrNull { UpdateAssetPolicy.matches(it, "app.aemu.plus") })
    }
    @Test fun publishedApkRequiresCorrectApplication() {
        for (applicationId in listOf("app.aemu", "app.aemu.clone", "other.app")) {
            assertFalse(UpdateAssetPolicy.matches(publishedApk, applicationId))
        }
    }
    @Test fun rejectsPublishedApkLookalikesAndArchives() {
        for (name in listOf("$publishedApk.zip", "$publishedApk.asc", "prefix-$publishedApk", "AEmulatorPlus+.apk", "AEmulatorPro.fake.apk")) {
            assertFalse(name, UpdateAssetPolicy.matches(name, "app.aemu.plus"))
        }
    }
    @Test fun neverMatchesOldSunsetOrCloneApks() {
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.apk", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("AEmulator-Sunset-0.0.0.3-sunset.30-app.aemu.clone.apk", "app.aemu.plus"))
    }
    @Test fun otherPackagesNeverMatch() {
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu"))
        assertFalse(UpdateAssetPolicy.matches(plus, "app.aemu.clone"))
        assertFalse(UpdateAssetPolicy.matches(plus, "other.app"))
    }
    @Test fun matchingIsCaseInsensitiveButRequiresExactPackageSuffix() {
        assertTrue(UpdateAssetPolicy.matches(plus.uppercase(), "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.zip", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("$plus.asc", "app.aemu.plus"))
        assertFalse(UpdateAssetPolicy.matches("prefix-app.aemu.plus.fake.apk", "app.aemu.plus"))
    }
}
