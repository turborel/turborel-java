package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * DECFLOAT's canonical form, the one turborel writes and takes ([PROTOCOL Values]): a coefficient
 * of at most 34 digits with no trailing zero, zero as coefficient 0 and exponent 0, and an
 * adjusted exponent, the exponent plus the digits less one, from -6143 to 6144, decimal128's
 * range (turborel's {@code value/decfloat.rs}). turborel refuses a value in another form, so the
 * driver neither sends nor takes one.
 */
final class Decfloats {
    /** The most digits of a coefficient, turborel's {@code MAX_DECFLOAT_PRECISION}. */
    static final int MAX_DIGITS = 34;

    /** The least adjusted exponent, decimal128's. */
    static final long MIN_ADJUSTED = -6143;

    /** The greatest adjusted exponent, decimal128's. */
    static final long MAX_ADJUSTED = 6144;

    private Decfloats() {}

    /**
     * The canonical form of a number.
     *
     * @param number the number
     * @return its coefficient and exponent, trailing zeros taken off
     * @throws TurborelException if it needs more than 34 digits or is out of range
     */
    static Value.Decfloat canonical(BigDecimal number) throws TurborelException {
        if (number.signum() == 0) {
            return new Value.Decfloat(BigInteger.ZERO, 0);
        }
        BigDecimal stripped = number.stripTrailingZeros();
        long exponent = -(long) stripped.scale();
        int digits = stripped.precision();
        if (digits > MAX_DIGITS) {
            throw Failures.misuse(Cite.VALUES + " " + number + " needs more than DECFLOAT's 34 digits");
        }
        long adjusted = exponent + digits - 1;
        if (adjusted < MIN_ADJUSTED || adjusted > MAX_ADJUSTED) {
            throw Failures.misuse(Cite.VALUES + " " + number + " is past DECFLOAT's range of exponents");
        }
        return new Value.Decfloat(stripped.unscaledValue(), (int) exponent);
    }

    /**
     * Whether a coefficient and an exponent read are in canonical form.
     *
     * @param coefficient the coefficient
     * @param exponent the exponent
     * @return whether turborel would have written them
     */
    static boolean isCanonical(BigInteger coefficient, int exponent) {
        if (coefficient.signum() == 0) {
            return exponent == 0;
        }
        int digits = new BigDecimal(coefficient.abs()).precision();
        long adjusted = (long) exponent + digits - 1;
        return !coefficient.mod(BigInteger.TEN).equals(BigInteger.ZERO)
                && digits <= MAX_DIGITS
                && adjusted >= MIN_ADJUSTED
                && adjusted <= MAX_ADJUSTED;
    }
}
