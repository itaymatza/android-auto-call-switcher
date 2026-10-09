@file:Suppress("UNUSED_PARAMETER")

package android.database

class Cursor(
    val state: Int = 2,
    val column: Int = 0,
    val hasRow: Boolean = true,
) : AutoCloseable {
    var closed = false
    var onRead: () -> Unit = {}

    fun getColumnIndex(name: String) = column

    fun moveToFirst() = hasRow

    fun getInt(index: Int): Int {
        onRead()
        return state
    }

    override fun close() {
        closed = true
    }
}
