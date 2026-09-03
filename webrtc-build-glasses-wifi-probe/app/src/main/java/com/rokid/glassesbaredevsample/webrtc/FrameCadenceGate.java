package com.rokid.glassesbaredevsample.webrtc;

/** Selects a stable target cadence from a fixed-rate camera stream. */
final class FrameCadenceGate {
    private final int sourceFps;
    private final int targetFps;
    private int accumulator;
    private boolean firstFrame = true;

    FrameCadenceGate(int sourceFps, int targetFps) {
        if (sourceFps <= 0 || targetFps <= 0 || targetFps > sourceFps) {
            throw new IllegalArgumentException("Expected 0 < targetFps <= sourceFps");
        }
        this.sourceFps = sourceFps;
        this.targetFps = targetFps;
    }

    synchronized boolean shouldSubmit() {
        if (firstFrame) {
            firstFrame = false;
            accumulator = 0;
            return true;
        }
        accumulator += targetFps;
        if (accumulator < sourceFps) return false;
        accumulator -= sourceFps;
        return true;
    }

    synchronized void reset() {
        accumulator = 0;
        firstFrame = true;
    }
}
