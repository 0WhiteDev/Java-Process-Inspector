package dev.whitedev.jpi.integration;

public final class WatchTarget {
    public static final WatchTarget player = new WatchTarget();
    public static final WatchTarget other = new WatchTarget();
    public static int counter;
    public int health = 10;

    public static void ready() { System.out.print(""); }

    public static void main(String[] args) {
        ready();
        other.health = 1;
        player.health = 8;
        player.health = 4;
        player.health = 2;
        counter = 1;
        counter = 1;
        counter = 2;
    }
}
