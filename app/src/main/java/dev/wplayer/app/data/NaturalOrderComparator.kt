package dev.wplayer.app.data

/**
 * Orders names the way people expect episode lists to read:
 * case-insensitive, with digit runs compared by numeric value ("2" < "10").
 */
object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = a.digitRunEnd(i)
                val endB = b.digitRunEnd(j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                val byValue = compareValuesBy(numA, numB, { it.length }, { it })
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun String.digitRunEnd(start: Int): Int {
        var end = start
        while (end < length && this[end].isDigit()) end++
        return end
    }
}
