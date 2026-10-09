package android.net

class Uri private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String) = Uri(value)
    }
}
