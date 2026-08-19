package app.coilforphoniebox.data.repository

import app.coilforphoniebox.transport.NotConnectedException
import app.coilforphoniebox.transport.RpcErrorException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

class ProbeVerdictTest {

    @Test
    fun `an answer means the box has the call`() {
        assertEquals(true, probeVerdict(Result.success(Unit)))
    }

    /**
     * The box raises before the plugin body runs and the RPC server formats that as an error
     * reply, so this is a real answer and a free one — nothing happened on the box.
     */
    @Test
    fun `an error reply means the box does not have the call`() {
        val reply = RpcErrorException("player.ctrl.list_library_sources", "no such method")
        assertEquals(false, probeVerdict(Result.failure<Unit>(reply)))
    }

    /**
     * The case worth having a test for. A box that is switched off, busy or behind a dropped
     * socket has told us nothing about its software. Reading any of these as "unsupported"
     * would pin a capable box to the fallback path for the rest of the session.
     */
    @Test
    fun `every other failure leaves the question open`() {
        assertNull(probeVerdict(Result.failure<Unit>(TimeoutException())))
        assertNull(probeVerdict(Result.failure<Unit>(NotConnectedException())))
        assertNull(probeVerdict(Result.failure<Unit>(IOException("socket closed"))))
    }
}
