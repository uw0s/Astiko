package app.astiko.util

/**
 * Natural sort for line names: numeric prefix first, then the rest.
 * "01N" sorts right after "01". "Χ95" (no numeric prefix) sorts last.
 */
fun compareLineShortNames(
    a: String,
    b: String,
): Int {
    val (aNum, aRest) = splitLineName(a)
    val (bNum, bRest) = splitLineName(b)
    val byNumber = aNum.compareTo(bNum)
    return if (byNumber != 0) byNumber else aRest.compareTo(bRest)
}

private fun splitLineName(name: String): Pair<Int, String> {
    val digits = name.takeWhile { it.isDigit() }
    return (digits.toIntOrNull() ?: Int.MAX_VALUE) to name
}
