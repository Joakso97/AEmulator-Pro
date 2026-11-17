
package app.aemu

import android.content.Context
import android.content.res.Configuration
import app.aemu.core.VmSettings
import java.util.Locale


object AppPrefs {
    private const val FILE = "app"

    
    val LANGUAGES = listOf(
        "" to "",
        "en" to "English",
        
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "it" to "Italiano",
        "nl" to "Nederlands",
        "sv" to "Svenska",
        "nb" to "Norsk bokmål",
        "da" to "Dansk",
        "fi" to "Suomi",
        "is" to "Íslenska",
        "ga" to "Gaeilge",
        
        "et" to "Eesti",
        "lv" to "Latviešu",
        "lt" to "Lietuvių",
        
        "pl" to "Polski",
        "cs" to "Čeština",
        "sk" to "Slovenčina",
        "hu" to "Magyar",
        "ro" to "Română",
        "bg" to "Български",
        "el" to "Ελληνικά",
        "hr" to "Hrvatski",
        "sl" to "Slovenščina",
        "mk" to "Македонски",
        "bs" to "Bosanski",
        "sr" to "Српски",
        "sq" to "Shqip",
        "cnr" to "Crnogorski",
        "ru" to "Русский",
        "uk" to "Українська",
        "be" to "Беларуская",
        
        "hy" to "Հայերեն",
        "ka" to "ქართული",
        "az" to "Azərbaycanca",
        
        "kk" to "Қазақша",
        "uz" to "Oʻzbekcha",
        "ky" to "Кыргызча",
        "tg" to "Тоҷикӣ",
        "tk" to "Türkmençe",
        
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "ur" to "اردو",
        "id" to "Bahasa Indonesia",
        "ms" to "Bahasa Melayu",
        "fil" to "Filipino",
        "vi" to "Tiếng Việt",
        "th" to "ไทย",
        "my" to "မြန်မာ",
        
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어",
        
        "tr" to "Türkçe",
        "ar" to "العربية",
        "fa" to "فارسی",
        "he" to "עברית",
    )

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun prefs(ctx: Context) = sp(ctx)

    
    data class HostUiOptions(val cutoutBarrier: Boolean = false, val sunsetNavbar: Boolean = true,
        val tabletNavbarRotation: Boolean = true, val holoNavbar: Boolean = false)
    
    fun hostUiOptions(ctx: Context): HostUiOptions = runCatching {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "host-ui.json"))
        val json = org.json.JSONObject(file.openRead().bufferedReader().use { it.readText() })
        HostUiOptions(json.optBoolean("cutoutBarrier", false), json.optBoolean("sunsetNavbar", true),
            json.optBoolean("tabletNavbarRotation", true), json.optBoolean("holoNavbar", false))
    }.getOrDefault(HostUiOptions())

    fun setHostUiOptions(ctx: Context, options: HostUiOptions) {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "host-ui.json"))
        val stream = file.startWrite()
        try {
            stream.write(org.json.JSONObject().put("cutoutBarrier", options.cutoutBarrier)
                .put("sunsetNavbar", options.sunsetNavbar).put("tabletNavbarRotation", options.tabletNavbarRotation).put("holoNavbar", options.holoNavbar)
                .toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    
    fun tabBarHidden(ctx: Context): Boolean = runCatching {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "tab-bar-hidden"))
        file.openRead().bufferedReader().use { it.readText().trim() == "1" }
    }.getOrDefault(false)

    fun setTabBarHidden(ctx: Context, hidden: Boolean) {
        val file = android.util.AtomicFile(java.io.File(ctx.filesDir, "tab-bar-hidden"))
        val stream = file.startWrite()
        try {
            stream.write((if (hidden) "1" else "0").toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }

    fun language(ctx: Context): String = sp(ctx).getString("lang", "") ?: ""
    fun setLanguage(ctx: Context, tag: String) = sp(ctx).edit().putString("lang", tag).apply()

    fun theme(ctx: Context): Int = sp(ctx).getInt("theme", THEME_SYSTEM)
    fun setTheme(ctx: Context, v: Int) = sp(ctx).edit().putInt("theme", v).apply()

    
    fun accentColor(ctx: Context): Int = sp(ctx).getInt("accent_color", 0)
    fun setAccentColor(ctx: Context, argb: Int) = sp(ctx).edit().putInt("accent_color", argb).apply()

    fun dynamicColor(ctx: Context): Boolean = sp(ctx).getBoolean("dynamic", true)
    fun setDynamicColor(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("dynamic", v).apply()

    
    fun phantomWarningDisabled(ctx: Context): Boolean = sp(ctx).getBoolean("phantom_warning_never", false)
    fun setPhantomWarningDisabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("phantom_warning_never", v).apply()

    fun autoCheckUpdates(ctx: Context): Boolean = sp(ctx).getBoolean("auto_check_updates", true)
    fun setAutoCheckUpdates(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_check_updates", v).apply()

    
    fun library3d(ctx: Context): Boolean = sp(ctx).getBoolean("library_3d", false)
    fun setLibrary3d(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("library_3d", v).apply()

    
    fun newHome(ctx: Context): Boolean = sp(ctx).getBoolean("new_home", true)
    fun setNewHome(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("new_home", v).apply()

    
    fun builtinBrowser(ctx: Context): Boolean = sp(ctx).getBoolean("builtin_browser", true)
    fun setBuiltinBrowser(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("builtin_browser", v).apply()

    
    fun browserSort(ctx: Context): String = sp(ctx).getString("browser_sort", "date") ?: "date"
    fun browserSortAsc(ctx: Context): Boolean = sp(ctx).getBoolean("browser_sort_asc", false)
    fun setBrowserSort(ctx: Context, key: String, asc: Boolean) =
        sp(ctx).edit().putString("browser_sort", key).putBoolean("browser_sort_asc", asc).apply()
    fun browserHidden(ctx: Context): Boolean = sp(ctx).getBoolean("browser_hidden", false)
    fun setBrowserHidden(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("browser_hidden", v).apply()
    
    fun browserDir(ctx: Context): String? = sp(ctx).getString("browser_dir", null)
    fun setBrowserDir(ctx: Context, path: String?) = sp(ctx).edit().putString("browser_dir", path).apply()

    
    fun libraryLayout(ctx: Context): String? = sp(ctx).getString("library_layout", null)
    fun setLibraryLayout(ctx: Context, json: String) = sp(ctx).edit().putString("library_layout", json).apply()

    fun lastUpdateCheck(ctx: Context): Long = sp(ctx).getLong("last_update_check", 0L)
    fun setLastUpdateCheck(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_update_check", v).apply()

    
    fun defaults(ctx: Context): VmSettings {
        val p = sp(ctx)
        val d = VmSettings()
        return d.copy(
            gpu = p.getBoolean("def_gpu", d.gpu),
            jit = p.getBoolean("def_jit", d.jit),
            netProxy = p.getBoolean("def_proxy", d.netProxy),
            showNavBar = p.getBoolean("def_nav", d.showNavBar),
            navButtons = p.getString("def_nav_buttons", d.navButtons) ?: d.navButtons,
            trackball = p.getBoolean("def_trackball", false),
            trackballDpad = p.getBoolean("def_trackball_dpad", false),
            trackballStepDp = p.getInt("def_trackball_step", 18).coerceIn(4, 48),
            mouseKeyboard = p.getBoolean("def_mouse_keyboard", false),
            keypad = p.getBoolean("def_keypad", false),
            keepScreenOn = p.getBoolean("def_awake", d.keepScreenOn),
            showLed = p.getBoolean("def_led", d.showLed),
        )
    }

    fun setDefaults(ctx: Context, s: VmSettings) = sp(ctx).edit()
        .putBoolean("def_gpu", s.gpu).putBoolean("def_jit", s.jit).putBoolean("def_proxy", s.netProxy)
        .putBoolean("def_nav", s.showNavBar).putBoolean("def_awake", s.keepScreenOn).putBoolean("def_led", s.showLed)
        .putString("def_nav_buttons", s.navButtons).putBoolean("def_trackball", s.trackball)
        .putBoolean("def_trackball_dpad", s.trackballDpad).putInt("def_trackball_step", s.trackballStepDp)
        .putBoolean("def_mouse_keyboard", s.mouseKeyboard).putBoolean("def_keypad", s.keypad).apply()

    
    
    fun wrap(base: Context): Context {
        val tag = language(base)
        val night = when (theme(base)) {
            THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> -1
        }
        if (tag.isEmpty() && night < 0) return base
        val cfg = Configuration(base.resources.configuration)
        if (tag.isNotEmpty()) {
            val loc = Locale.forLanguageTag(tag)
            Locale.setDefault(loc)
            cfg.setLocale(loc)
            cfg.setLayoutDirection(loc)
        }
        if (night >= 0) cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }
}
