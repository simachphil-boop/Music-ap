package app.vesper.player.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTotalDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин"
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "%.0f КБ".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f МБ".format(bytes / (1024f * 1024f))
    else -> "%.2f ГБ".format(bytes / (1024f * 1024f * 1024f))
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "—" else SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(millis))

/** Russian plural: plural(5, "трек", "трека", "треков"). */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
    return "$n $word"
}

fun tracksCount(n: Int) = plural(n, "трек", "трека", "треков")
fun videosCount(n: Int) = plural(n, "видео", "видео", "видео")

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Доброе утро"
    in 12..16 -> "Добрый день"
    in 17..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}
