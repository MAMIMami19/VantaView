// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.browse.TmdbKeyStore
import com.vantaview.app.data.browse.isShortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TMDB key field: masked display, and telling the short API key from the long Bearer token. */
class TmdbKeyTest {

    @Test fun maskShowsLastFour() {
        assertEquals("••••3298", TmdbKeyStore.mask("0123456789abcdef0123456789ab3298"))
        assertEquals("••••wxyz", TmdbKeyStore.mask("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ3eHl6In0.wxyz"))
    }

    @Test fun maskHidesShortKeysEntirely() {
        assertEquals("••••", TmdbKeyStore.mask("abc"))
        assertEquals("••••", TmdbKeyStore.mask("12345678"))
    }

    @Test fun shortKeyIs32Alphanumerics() {
        assertTrue(isShortKey("0123456789abcdef0123456789abcdef"))
        assertTrue(isShortKey("ABCDEFGHIJ0123456789abcdefghijkl"))
    }

    @Test fun longTokenIsNotShortKey() {
        assertFalse(isShortKey("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxMjM0NTY3ODkwIn0.signature"))
        assertFalse(isShortKey("0123456789abcdef0123456789abcde"))
        assertFalse(isShortKey("0123456789abcdef0123456789abcde-"))
        assertFalse(isShortKey(""))
    }
}
