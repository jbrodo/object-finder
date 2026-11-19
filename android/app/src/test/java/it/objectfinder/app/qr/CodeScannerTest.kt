package it.objectfinder.app.qr

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeScannerTest {
    private fun err(code: Int) = MlKitException("Failed to scan code.", code)

    @Test
    fun `closing the scanner shows no message`() {
        assertNull(CodeScanner.failureMessage(err(MlKitException.CODE_SCANNER_CANCELLED)))
        assertNull(CodeScanner.failureMessage(err(MlKitException.CANCELLED)))
        assertNull(CodeScanner.failureMessage(err(MlKitException.CODE_SCANNER_TASK_IN_PROGRESS)))
    }

    @Test
    fun `unavailable scanner suggests the system camera`() {
        assertEquals(CodeScanner.UNAVAILABLE_MESSAGE, CodeScanner.failureMessage(err(MlKitException.CODE_SCANNER_UNAVAILABLE)))
        assertEquals(
            CodeScanner.UNAVAILABLE_MESSAGE,
            CodeScanner.failureMessage(err(MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD)),
        )
    }

    @Test
    fun `other errors get an actionable italian message, not the raw english one`() {
        val msg = CodeScanner.failureMessage(err(MlKitException.CODE_SCANNER_PIPELINE_INFERENCE_ERROR))!!
        assertTrue("Failed" !in msg && "riprova" in msg)
        assertTrue("riprova" in CodeScanner.failureMessage(IllegalStateException("x"))!!)
    }

    @Test
    fun `label has the standard four-module quiet zone`() {
        val m = QrCodes.matrix(QrCodes.payloadFor("3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"))
        (0 until 4).forEach { i -> assertTrue(m[i].none { it } && m.none { row -> row[i] }) }
    }
}
