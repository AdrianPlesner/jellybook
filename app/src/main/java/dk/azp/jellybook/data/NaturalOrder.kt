package dk.azp.jellybook.data

/**
 * Compares strings the way a person reads numbered filenames, so `part2` sorts before `part10`. Plain text comparison puts
 * `10` first, which scrambles a book whose files are numbered without zero padding.
 */
internal object NaturalOrder : Comparator<String> {

    override fun compare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = digitRunEnd(left, leftIndex)
                val rightEnd = digitRunEnd(right, rightIndex)
                val byNumber = compareNumbers(left.substring(leftIndex, leftEnd), right.substring(rightIndex, rightEnd))
                if (byNumber != 0) return byNumber
                leftIndex = leftEnd
                rightIndex = rightEnd
            } else {
                val byChar = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
                if (byChar != 0) return byChar
                leftIndex++
                rightIndex++
            }
        }
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }

    private fun digitRunEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    /** Compares digit runs by value without parsing, so a run of any length is safe. */
    private fun compareNumbers(left: String, right: String): Int {
        val leftDigits = left.trimStart('0')
        val rightDigits = right.trimStart('0')
        val byLength = leftDigits.length.compareTo(rightDigits.length)
        return if (byLength != 0) byLength else leftDigits.compareTo(rightDigits)
    }
}
