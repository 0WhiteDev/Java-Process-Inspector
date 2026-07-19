package dev.whitedev.jpi.fixture;

public final class HeapObjectFixture {
    public static final Root ROOT = new Root();

    private HeapObjectFixture() {}

    public static final class Root {
        final Child child = new Child("needle-value");
        final Object[] items = {child, "second-value"};
    }

    public static final class Child {
        final String label;
        final byte[] payload = {1, 2, 3};

        Child(String label) {
            this.label = label;
        }
    }
}
