package io.github.foadsf.openobd

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/** A recurring service item. Either interval may be absent; whichever comes first is due first. */
data class CareItem(
    val id: Long,
    val name: String,
    val everyKm: Int?,
    val everyMonths: Int?,
    val lastKm: Int? = null,
    val lastMillis: Long? = null,
)

data class CareStatus(val fraction: Double?, val text: String)

data class Garage(val odometerKm: Int?, val items: List<CareItem>)

private const val DAY = 86_400_000L

/** Generic starting points; the owner's manual is the authority for a specific car. */
val DEFAULT_CARE = listOf(
    CareItem(1, "Engine oil & filter", 10_000, 12),
    CareItem(2, "Engine air filter", 20_000, null),
    CareItem(3, "Cabin filter", 20_000, null),
    CareItem(4, "Spark plugs", 60_000, null),
    CareItem(5, "Brake fluid", null, 24),
    CareItem(6, "Coolant", 50_000, null),
)

fun dueMillis(lastMillis: Long, months: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = lastMillis
        add(Calendar.MONTH, months)
    }.timeInMillis

/**
 * How much of the interval is left (1 = just done, 0 = due, negative = overdue) and a short
 * sentence saying when it is due.
 */
fun CareItem.status(odometerKm: Int?, now: Long): CareStatus {
    val parts = mutableListOf<String>()
    var fraction: Double? = null
    if (everyKm != null && lastKm != null && odometerKm != null) {
        val left = lastKm + everyKm - odometerKm
        fraction = left.toDouble() / everyKm
        parts += if (left >= 0) "${grouped(left)} km left" else "${grouped(-left)} km overdue"
    }
    if (everyMonths != null && lastMillis != null) {
        val due = dueMillis(lastMillis, everyMonths)
        val daysLeft = ((due - now).toDouble() / DAY).roundToInt()
        val f = daysLeft / (everyMonths * 30.44)
        fraction = fraction?.coerceAtMost(f) ?: f
        parts += when {
            daysLeft < 0 -> "${span(-daysLeft)} overdue"
            daysLeft == 0 -> "due today"
            else -> "${span(daysLeft)} left"
        }
    }
    if (parts.isEmpty()) {
        val noOdo = everyKm != null && lastKm != null && odometerKm == null
        return CareStatus(null, if (noOdo) "Enter the odometer to track" else "Tap to record the last service")
    }
    return CareStatus(fraction, parts.joinToString(" · "))
}

private fun span(days: Int): String = when {
    days >= 60 -> "${(days / 30.44).roundToInt()} months"
    days >= 14 -> "${days / 7} weeks"
    days == 1 -> "1 day"
    else -> "$days days"
}

fun grouped(n: Int): String = "%,d".format(n).replace(',', ' ')

fun Garage.toJson(): String = JSONObject().apply {
    put("odometer", odometerKm ?: JSONObject.NULL)
    put("items", JSONArray(items.map { i ->
        JSONObject().apply {
            put("id", i.id)
            put("name", i.name)
            put("everyKm", i.everyKm ?: JSONObject.NULL)
            put("everyMonths", i.everyMonths ?: JSONObject.NULL)
            put("lastKm", i.lastKm ?: JSONObject.NULL)
            put("lastMillis", i.lastMillis ?: JSONObject.NULL)
        }
    }))
}.toString()

fun garageFromJson(json: String?): Garage {
    if (json == null) return Garage(null, DEFAULT_CARE)
    return runCatching {
        val o = JSONObject(json)
        fun JSONObject.intOrNull(k: String) = if (isNull(k)) null else optInt(k)
        fun JSONObject.longOrNull(k: String) = if (isNull(k)) null else optLong(k)
        val arr = o.getJSONArray("items")
        Garage(
            o.intOrNull("odometer"),
            (0 until arr.length()).map { n ->
                val i = arr.getJSONObject(n)
                CareItem(
                    i.getLong("id"), i.getString("name"), i.intOrNull("everyKm"), i.intOrNull("everyMonths"),
                    i.intOrNull("lastKm"), i.longOrNull("lastMillis"),
                )
            },
        )
    }.getOrElse { Garage(null, DEFAULT_CARE) }
}
