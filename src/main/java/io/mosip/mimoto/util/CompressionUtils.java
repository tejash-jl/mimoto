package io.mosip.mimoto.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;

import static java.util.zip.Deflater.BEST_COMPRESSION;

public class CompressionUtils
{
    private static final int BUFFER_SIZE = 1024;

    public static byte[] compress(final byte[] data) throws IOException {
        final Deflater deflater = new Deflater();
        deflater.setLevel(BEST_COMPRESSION);
        deflater.setInput(data);

        try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length))
        {
            deflater.finish();
            final byte[] buffer = new byte[BUFFER_SIZE];
            while (!deflater.finished())
            {
                final int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }

            return outputStream.toByteArray();
        }
    }
}