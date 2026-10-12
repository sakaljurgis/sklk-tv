package lt.sklk.tv

import java.util.Locale

object Format {
    /** `1h 25m`, `54m` */
    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** `3.7 GB`, `760 MB` */
    fun size(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }

    /** `1:02:03`, `23:10` */
    fun time(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
