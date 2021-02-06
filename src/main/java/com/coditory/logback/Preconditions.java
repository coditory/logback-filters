package com.coditory.logback;

final class Preconditions {
    private Preconditions() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static void checkArgument(boolean valid, String message, Object... args) {
        if (!valid) {
            throw new IllegalArgumentException(format(message, args));
        }
    }

    public static <T> T checkNotNull(T obj, String name) {
        if (obj == null) {
            throw new IllegalArgumentException("Expected non null: " + name);
        }
        return obj;
    }

    private static String format(String message, Object... args) {
        return String.format(message, args);
    }
}
