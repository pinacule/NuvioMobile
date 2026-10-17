package com.nuvio.app.core.format

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String {
    val locale = Locale.forLanguageTag(localeTag)
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        FormatStyle.LONG,
        null,
        IsoChronology.INSTANCE,
        locale,
    )
    val effectivePattern = if (includeYear) pattern else pattern.withoutYearField()
    return DateTimeFormatter.ofPattern(effectivePattern, locale).format(LocalDate.parse(isoDate))
}

/** Drops the year field (y/u) plus one adjacent separator from a localized date pattern. */
private fun String.withoutYearField(): String {
    var result = replace(Regex("[\\s,./\\-\u2013\u2014]*[yYu]+"), "")
    result = result.replace(Regex("[yYu]+[\\s,./\\-\u2013\u2014]*"), "")
    return result.trim().trim(',', '.', '/', '-', '\u2013', '\u2014', ' ')
}
