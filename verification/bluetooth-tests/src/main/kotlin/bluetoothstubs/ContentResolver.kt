@file:Suppress("UNUSED_PARAMETER")

package android.content

import android.database.Cursor
import android.net.Uri

class ContentResolver {
    var read: () -> Cursor? = { null }
    var queries = 0

    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        arguments: Array<String>?,
        sort: String?,
    ): Cursor? {
        queries++
        return read()
    }
}
