package org.gameyfin.app.core.download.files

/**
 * The part of a download a request asks for, after RFC 9110's `Range` and `If-Range` rules.
 * Several ranges in one request are not supported, so they read as the whole body, as the RFC allows.
 */
sealed interface ByteRange {
    data object Whole : ByteRange

    /** Inclusive at both ends, as `Content-Range` writes it. */
    data class Part(val start: Long, val end: Long) : ByteRange

    data object Unsatisfiable : ByteRange

    companion object {
        fun resolve(range: String?, ifRange: String?, etag: String, size: Long): ByteRange {
            if (range == null) return Whole
            // A changed file must be sent whole, never continued.
            if (ifRange != null && ifRange.trim() != etag) return Whole

            val (unit, spec) = range.split('=', limit = 2).takeIf { it.size == 2 } ?: return Whole
            if (!unit.trim().equals("bytes", ignoreCase = true) || ',' in spec) return Whole
            val (first, last) = spec.trim().split('-', limit = 2).takeIf { it.size == 2 } ?: return Whole

            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it >= 0 } ?: return Whole
                if (suffix == 0L || size == 0L) return Unsatisfiable
                return Part(maxOf(0L, size - suffix), size - 1)
            }

            val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return Whole
            val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return Whole
            if (end < start) return Whole
            if (start >= size) return Unsatisfiable
            return Part(start, minOf(end, size - 1))
        }
    }
}
