package app.claudebuddy.ui

import java.util.Locale

/**
 * Язык интерфейса: русский или английский (выбирается в «Настройках»).
 * Значение ставит FeedStore при чтении настроек — его видят и экраны, и служба, и виджет.
 * Экраны пересобираются при смене языка (key(lang) в MainActivity).
 */
object Lang {
    @Volatile var code: String = "ru"
    val en get() = code == "en"
    val locale: Locale get() = if (en) Locale.ENGLISH else Locale.forLanguageTag("ru")
}

/** Текст на текущем языке. */
fun tr(ru: String, en: String) = if (Lang.en) en else ru

/** Число с правильной формой слова: tr для русского склонения и английского -s. */
fun countWord(n: Int, ruOne: String, ruFew: String, ruMany: String, enOne: String, enMany: String = enOne + "s") =
    if (Lang.en) (if (n == 1) enOne else enMany) else plural(n, ruOne, ruFew, ruMany)
