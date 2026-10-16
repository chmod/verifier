package dk.panos.promofacie.util;

import java.util.Arrays;

/**
 * High-performance, zero-dependency Base58 codec using the Bitcoin alphabet.
 */
public final class Base58 {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final char[] ENCODED_CHARS = ALPHABET.toCharArray();
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ENCODED_CHARS.length; i++) {
            INDEXES[ENCODED_CHARS[i]] = i;
        }
    }

    private Base58() {}

    public static String encode(byte[] input) {
        if (input == null || input.length == 0) {
            return "";
        }
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        byte[] inputCopy = Arrays.copyOf(input, input.length);
        char[] encoded = new char[inputCopy.length * 2];
        int outputStart = encoded.length;
        for (int inputStart = zeros; inputStart < inputCopy.length; ) {
            encoded[--outputStart] = ENCODED_CHARS[divmod(inputCopy, inputStart, 256, 58)];
            if (inputCopy[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < encoded.length && encoded[outputStart] == ENCODED_CHARS[0]) {
            outputStart++;
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ENCODED_CHARS[0];
        }
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    public static byte[] decode(String input) {
        if (input == null || input.isEmpty()) {
            return new byte[0];
        }
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); ++i) {
            char c = input.charAt(i);
            int digit = c < 128 ? INDEXES[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Illegal character " + c + " at index " + i);
            }
            input58[i] = (byte) digit;
        }
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) {
            zeros++;
        }
        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        for (int inputStart = zeros; inputStart < input58.length; ) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] == 0) {
                inputStart++;
            }
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) {
            outputStart++;
        }
        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.length);
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = (int) number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
