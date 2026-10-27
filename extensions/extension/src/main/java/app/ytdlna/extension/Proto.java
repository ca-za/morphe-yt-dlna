package app.ytdlna.extension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal protobuf wire format reader. The app's proto classes are obfuscated,
 * so streams are read from the serialized bytes by field number instead.
 */
final class Proto {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int BYTES = 2;
    static final int FIXED32 = 5;

    static final class Field {
        final int number;
        final int wireType;
        final long varint;
        final byte[] data;
        /** Byte range of the whole field (key and value) in the parsed message. */
        int start, end;

        Field(int number, int wireType, long varint, byte[] data) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.data = data;
        }

        String string() {
            return data == null ? "" : new String(data, StandardCharsets.UTF_8);
        }
    }

    private Proto() {
    }

    /** Parses one message level. Throws IllegalArgumentException on malformed input. */
    static List<Field> parse(byte[] buf) {
        List<Field> fields = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            int start = pos[0];
            long key = readVarint(buf, pos);
            int number = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            switch (wireType) {
                case VARINT:
                    fields.add(new Field(number, wireType, readVarint(buf, pos), null));
                    break;
                case FIXED64:
                    fields.add(new Field(number, wireType, 0, slice(buf, pos, 8)));
                    break;
                case BYTES:
                    int length = (int) readVarint(buf, pos);
                    fields.add(new Field(number, wireType, 0, slice(buf, pos, length)));
                    break;
                case FIXED32:
                    fields.add(new Field(number, wireType, 0, slice(buf, pos, 4)));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported wire type " + wireType);
            }
            Field field = fields.get(fields.size() - 1);
            field.start = start;
            field.end = pos[0];
        }
        return fields;
    }

    static Field first(List<Field> fields, int number) {
        for (Field f : fields) {
            if (f.number == number) return f;
        }
        return null;
    }

    static String string(List<Field> fields, int number) {
        Field f = first(fields, number);
        return f == null || f.wireType != BYTES ? "" : f.string();
    }

    static long varint(List<Field> fields, int number) {
        Field f = first(fields, number);
        return f == null || f.wireType != VARINT ? 0 : f.varint;
    }

    private static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= buf.length) throw new IllegalArgumentException("Truncated varint");
            byte b = buf[pos[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("Varint too long");
    }

    private static byte[] slice(byte[] buf, int[] pos, int length) {
        if (length < 0 || pos[0] + length > buf.length) {
            throw new IllegalArgumentException("Truncated field");
        }
        byte[] out = new byte[length];
        System.arraycopy(buf, pos[0], out, 0, length);
        pos[0] += length;
        return out;
    }
}
