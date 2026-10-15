package timber.log

// The shared parsers use Android's Timber. Desktop leaves debug output disabled to avoid logging session metadata.
object Timber {
    fun d(message: String?, vararg args: Any?) = Unit
    fun i(message: String?, vararg args: Any?) = Unit
    fun w(message: String?, vararg args: Any?) = Unit
    fun w(error: Throwable?, message: String?, vararg args: Any?) = Unit
    fun e(message: String?, vararg args: Any?) = Unit
    fun e(error: Throwable?, message: String?, vararg args: Any?) = Unit
}
