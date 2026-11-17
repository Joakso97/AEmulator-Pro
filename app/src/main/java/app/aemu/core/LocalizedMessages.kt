package app.aemu.core

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException


class Msg(val english: String, @StringRes val res: Int = 0, vararg val args: Any?) {
    fun text(ctx: Context): String = Localized.resolve(ctx, english, res, args)
    override fun toString() = english

    companion object {
        
        fun of(t: Throwable): Msg {
            val le = t as? LocalizedError
            return Msg(t.message ?: t.toString(), le?.resId ?: 0, *(le?.resArgs ?: emptyArray()))
        }
    }
}


interface LocalizedError {
    val resId: Int
    val resArgs: Array<out Any?>
}

class LocalizedException(message: String, @StringRes override val resId: Int, vararg args: Any?) :
    IllegalStateException(message), LocalizedError {
    override val resArgs: Array<out Any?> = args
}

class LocalizedIllegalArgumentException(message: String, @StringRes override val resId: Int, vararg args: Any?) :
    IllegalArgumentException(message), LocalizedError {
    override val resArgs: Array<out Any?> = args
}

class LocalizedIOException(message: String, @StringRes override val resId: Int, vararg args: Any?) :
    IOException(message), LocalizedError {
    override val resArgs: Array<out Any?> = args
}


fun localizedError(message: String, @StringRes res: Int, vararg args: Any?): Nothing =
    throw LocalizedException(message, res, *args)

object Localized {
    fun resolve(ctx: Context, english: String, @StringRes res: Int, args: Array<out Any?>): String {
        if (res == 0) return english
        val resolved = Array<Any?>(args.size) { i -> val a = args[i]; if (a is Msg) a.text(ctx) else a }
        return runCatching { ctx.getString(res, *resolved) }.getOrDefault(english)
    }
}


fun Throwable.userMessageOrNull(ctx: Context): String? =
    if ((this is LocalizedError && resId != 0) || message != null) userMessage(ctx) else null


fun Throwable.userMessage(ctx: Context): String =
    if (this is LocalizedError && resId != 0) Localized.resolve(ctx, message ?: javaClass.simpleName, resId, resArgs)
    else message ?: javaClass.simpleName
