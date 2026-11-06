package com.trustsy.verify

import com.trustsy.verify.internal.flow.FlowLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionLinkTest {
    private val key = "AQAgAAEAAlftwPm3Tckm0oDXKB2LUpCcqfYNbSihX2L4bKTH6NB6"
    private val token = "ceb76355-da07-470e-903b-a1f8d5f3a2b1"

    @Test
    fun `a code round-trips to its token`() {
        val code = FlowLink.encode(token)!!
        assertEquals(22, code.length)
        assertEquals(token, FlowLink.decode(code))
    }

    @Test
    fun `matches an independent base64url implementation`() {
        // Expected values from Python's base64.urlsafe_b64encode / uuid.UUID.
        assertEquals("ceb76355-da07-470e-901d-40dc8ed20b85", FlowLink.decode("zrdjVdoHRw6QHUDcjtILhQ"))
        assertEquals("zrdjVdoHRw6QO6H41fOisQ", FlowLink.encode(token))
    }

    @Test
    fun `a code with non-zero trailing bits is refused`() {
        // The same 128 bits with the 4 spare bits set: one of the fifteen other
        // spellings of the token, which the backend never emits.
        val code = FlowLink.encode(token)!!
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet.indexOf(code.last())
        val other = code.dropLast(1) + alphabet[last or 0x01]
        assert(other != code)
        assertNull(FlowLink.decode(other))
    }

    @Test
    fun `malformed codes are refused`() {
        assertNull(FlowLink.decode("short"))
        assertNull(FlowLink.decode("zrdjVdoHRw6QHUDcjtILh+")) // '+' is not base64url
        assertNull(FlowLink.decode("zrdjVdoHRw6QHUDcjtILhQA")) // 23 chars
    }

    @Test
    fun `u links use the link origin as the API base`() {
        val code = FlowLink.encode(token)!!
        val session = TrustsyVerify.Session.fromLink("https://verify.example.com/u/$code", key)!!
        assertEquals("https://verify.example.com", session.apiBaseUrl)
        assertEquals(token, session.token)
    }

    @Test
    fun `ports are kept in the origin`() {
        val code = FlowLink.encode(token)!!
        val session = TrustsyVerify.Session.fromLink("http://10.0.2.2:5299/u/$code", key, allowInsecure = true)!!
        assertEquals("http://10.0.2.2:5299", session.apiBaseUrl)
    }

    @Test
    fun `verify links take the token query`() {
        val session = TrustsyVerify.Session.fromLink("https://v.example.com/verify?lang=th&token=${token.uppercase()}", key)!!
        assertEquals(token, session.token)
        assertEquals("https://v.example.com", session.apiBaseUrl)
    }

    @Test
    fun `other links are refused`() {
        val code = FlowLink.encode(token)!!
        assertNull(TrustsyVerify.Session.fromLink("https://v.example.com/resume/$code", key))
        assertNull(TrustsyVerify.Session.fromLink("https://v.example.com/verify?token=not-a-uuid", key))
        assertNull(TrustsyVerify.Session.fromLink("ftp://v.example.com/u/$code", key))
        assertNull(TrustsyVerify.Session.fromLink("not a url", key))
        assertNull(TrustsyVerify.Session.fromLink("https://v.example.com/u/${code}x", key))
    }

    @Test
    fun `http is refused unless insecure is allowed`() {
        val code = FlowLink.encode(token)!!
        assertNull(TrustsyVerify.Session.fromLink("http://v.example.com/u/$code", key))
    }

    @Test
    fun `the session never prints its token`() {
        val session = TrustsyVerify.Session("https://v.example.com", token, key)
        assert(token !in session.toString())
    }
}
