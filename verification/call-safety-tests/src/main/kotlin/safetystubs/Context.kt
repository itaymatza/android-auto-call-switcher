@file:Suppress("UNUSED_PARAMETER")

package android.content

open class Context {
    val services = mutableMapOf<Class<*>, Any>()

    fun <T> getSystemService(type: Class<T>): T? = services[type]?.let(type::cast)
}
