package dev.whitedev.jpi.agent.cfg;

final class CfgFixture {
    int classify(int value) {
        if (value < 0) return -1;
        int sum = 0;
        for (int index = 0; index < value; index++) sum += index;
        if ((sum & 1) == 0) return sum;
        throw new IllegalStateException("odd");
    }
}

