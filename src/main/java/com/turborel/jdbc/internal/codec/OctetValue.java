package com.turborel.jdbc.internal.codec;

import com.turborel.jdbc.TurborelException;

/**
 * The values whose body holds octets: a plug-in's value, a binary string, a reference and a JSON
 * value ([PROTOCOL Values]). Each holds its octets as {@link Octets}, which never change.
 */
public sealed interface OctetValue extends Value {
    /**
     * A plug-in's value, tag 4: its type's name, its text and its bytes. One the driver does not
     * know is kept as it came, and sent back unchanged.
     *
     * @param typeName its type's name
     * @param text its text, how a client shows it
     * @param bytes its bytes, as its type writes them
     */
    record Plugin(String typeName, String text, Octets bytes) implements OctetValue {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.PLUGIN).string(typeName).string(text).bytes(bytes);
        }
    }

    /**
     * A binary string, tag 6, of either binary type.
     *
     * @param octets its octets
     */
    record Binary(Octets octets) implements OctetValue {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.BINARY).bytes(octets);
        }
    }

    /**
     * A reference, tag 24: the octets that identify the row it references.
     *
     * @param octets its octets
     */
    record Reference(Octets octets) implements OctetValue {
        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.REFERENCE).bytes(octets);
        }
    }

    /**
     * A JSON value, tag 25: its stored form, turborel's binary tree (turborel's RULES.md D89),
     * checked whole when it is made, so a damaged one is never held or sent.
     */
    final class Json implements OctetValue {
        private final Octets stored;

        private Json(Octets stored) {
            this.stored = stored;
        }

        /**
         * A JSON value of the stored form given, checked.
         *
         * @param stored its stored form, copied
         * @return the value
         * @throws TurborelException if the octets are not a whole document of the form
         */
        public static Json ofStored(byte[] stored) throws TurborelException {
            Octets copy = Octets.of(stored);
            JsonBinary.decode(copy.shared());
            return new Json(copy);
        }

        /**
         * The JSON value of an item, encoded.
         *
         * @param item the item
         * @return the value
         * @throws TurborelException if it nests too deep or is too long
         */
        public static Json of(JsonItem item) throws TurborelException {
            return new Json(Octets.owning(JsonBinaryWriter.encode(item)));
        }

        /**
         * Its stored form.
         *
         * @return a copy of it
         */
        public byte[] stored() {
            return stored.toByteArray();
        }

        /**
         * The item its stored form holds.
         *
         * @return the item
         * @throws TurborelException never of a value made by its factories, which check it
         */
        public JsonItem item() throws TurborelException {
            return JsonBinary.decode(stored.shared());
        }

        @Override
        public void encode(Writer w) throws TurborelException {
            w.u8(Tags.JSON).bytes(stored);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Json j && j.stored.equals(stored);
        }

        @Override
        public int hashCode() {
            return stored.hashCode();
        }

        @Override
        public String toString() {
            return "Json[" + stored + "]";
        }
    }
}
