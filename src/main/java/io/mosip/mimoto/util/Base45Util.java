package io.mosip.mimoto.util;

import nl.minvws.encoding.Base45;

public class Base45Util {
    public static String encode(byte[] bytes) {
        return Base45.getEncoder().encodeToString(bytes);
    }
}
