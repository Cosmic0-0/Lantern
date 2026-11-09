package app.lantern.sources

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves quickjs-kt's native library loads on the laptop's JVM, which every other test here depends on. */
class QuickJsJvmTest {
    @Test
    fun evaluatesJavaScript() = runBlocking {
        val qjs = QuickJs.create(Dispatchers.Default)
        try {
            assertEquals(2, qjs.evaluate<Int>("1 + 1"))
        } finally {
            qjs.close()
        }
    }
}
