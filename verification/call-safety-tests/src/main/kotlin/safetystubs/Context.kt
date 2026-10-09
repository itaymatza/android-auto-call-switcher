@file:Suppress("UNUSED_PARAMETER")

package android.content

open class Context {
    var runtimeGranted = true
    val services = mutableMapOf<Class<*>, Any>()

    fun <T> getSystemService(type: Class<T>): T? = services[type]?.let(type::cast)
}
