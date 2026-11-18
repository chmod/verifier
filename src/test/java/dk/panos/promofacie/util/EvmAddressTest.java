package dk.panos.promofacie.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvmAddressTest {

    @Test
    void lowercasesChecksummedAndTrimmedEvmAddresses() {
        assertEquals("0x2680a7f125ce3955b6a0284964f201c3250b3012",
                EvmAddress.normalize("0x2680a7f125ce3955B6A0284964f201C3250b3012"));
        assertEquals("0x2680a7f125ce3955b6a0284964f201c3250b3012",
                EvmAddress.normalize("  0x2680A7F125CE3955B6A0284964F201C3250B3012 "));
    }

    @Test
    void leavesNonEvmAddressesUntouched() {
        // Solana Base58 is case-sensitive; Cardano bech32; anything malformed
        assertEquals("7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2", EvmAddress.normalize("7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"));
        assertEquals("stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw",
                EvmAddress.normalize("stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw"));
        assertEquals("0xABC", EvmAddress.normalize("0xABC"));
        assertEquals("0X2680A7F125CE3955B6A0284964F201C3250B3012",
                EvmAddress.normalize("0X2680A7F125CE3955B6A0284964F201C3250B3012"), "only the canonical 0x prefix counts");
        assertNull(EvmAddress.normalize(null));
    }

    @Test
    void detectsEvmAddresses() {
        assertTrue(EvmAddress.isEvm("0x2680a7f125ce3955B6A0284964f201C3250b3012"));
        assertFalse(EvmAddress.isEvm("7Np41oeYqPefeNQEHSv1UDhYrehxin3NStELsSKCT4K2"));
        assertFalse(EvmAddress.isEvm(null));
    }
}
