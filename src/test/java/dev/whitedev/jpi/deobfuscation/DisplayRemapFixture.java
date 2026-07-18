package dev.whitedev.jpi.deobfuscation;

final class DisplayRemapFixture {
    String secret;

    String calculate(String value) {
        secret = value;
        return helper(value);
    }

    String helper(String value) {
        return value.trim();
    }

    DisplayRemapFixture self() {
        return this;
    }
}