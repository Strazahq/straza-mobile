package dev.straza.approver.shared.flow

import dev.straza.approver.shared.net.ResolvedRequest

/**
 * Builds a CSV snapshot of the resolved (Activity) feed for a user-initiated
 * export: RFC 4180 quoting, UTC ISO-8601 timestamps. The agent's reason is
 * exported under a header that names it an unverified claim. The file is an
 * unsigned copy of what the feed already shows (no token, key or challenge);
 * strazad holds the authoritative history.
 */
object ActivityCsv {

    private val HEADER = listOf(
        "deployment", "decided_at_utc", "state", "tool",
        "requester", "rule_id", "decided_by", "reason (unverified agent claim)",
    )

    /** One deployment's resolved rows as CSV (blank deployment column if unknown). */
    fun export(deploymentName: String?, rows: List<ResolvedRequest>): String =
        exportAll(listOf((deploymentName ?: "") to rows))

    /** Several deployments in one file, grouped in the given order; the
     *  `deployment` column tells them apart. An empty list still emits the header. */
    fun exportAll(sections: List<Pair<String, List<ResolvedRequest>>>): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",", transform = ::csvField)).append("\r\n")
        for ((deploymentName, rows) in sections) {
            for (r in rows) {
                val cells = listOf(
                    deploymentName,
                    r.decidedAtEpochSeconds?.let(::isoUtc).orEmpty(),
                    r.state.wire,
                    r.toolLabel,
                    r.requester,
                    r.ruleId,
                    r.decidedBy.orEmpty(),
                    r.justification,
                )
                sb.append(cells.joinToString(",", transform = ::csvField)).append("\r\n")
            }
        }
        return sb.toString()
    }

    /** RFC 4180: wrap in quotes (doubling internal quotes) when a field contains a
     *  comma, quote, CR or LF; otherwise emit verbatim. */
    internal fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Epoch seconds to `YYYY-MM-DDThh:mm:ssZ` (UTC), by Howard Hinnant's
     *  civil-from-days algorithm, so no date dependency is needed. */
    internal fun isoUtc(epochSeconds: Long): String {
        val days = epochSeconds.floorDiv(86_400L)
        val secOfDay = epochSeconds.mod(86_400L)

        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L                                   // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365  // [0, 399]
        val y = yoe + era * 400L
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)              // [0, 365]
        val mp = (5 * doy + 2) / 153                                   // [0, 11]
        val day = doy - (153 * mp + 2) / 5 + 1                         // [1, 31]
        val month = if (mp < 10) mp + 3 else mp - 9                    // [1, 12]
        val year = if (month <= 2) y + 1 else y

        val hh = secOfDay / 3600
        val mm = (secOfDay % 3600) / 60
        val ss = secOfDay % 60
        return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(hh, 2)}:${pad(mm, 2)}:${pad(ss, 2)}Z"
    }

    private fun pad(n: Long, width: Int): String {
        val s = n.toString()
        return if (s.length >= width) s else "0".repeat(width - s.length) + s
    }
}
