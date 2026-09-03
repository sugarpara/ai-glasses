package com.rokid.glassesbaredevsample.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameCadenceGateTest {
    @Test
    public void twentyFourToTwenty_selectsFiveOfEachSixFrames() {
        FrameCadenceGate gate = new FrameCadenceGate(24, 20);

        int submitted = 0;
        for (int frame = 0; frame < 120; frame++) {
            if (gate.shouldSubmit()) submitted++;
        }

        assertEquals(100, submitted);
    }

    @Test
    public void reset_restartsCadenceWithImmediateFrame() {
        FrameCadenceGate gate = new FrameCadenceGate(24, 20);
        assertTrue(gate.shouldSubmit());
        assertFalse(gate.shouldSubmit());

        gate.reset();

        assertTrue(gate.shouldSubmit());
        assertFalse(gate.shouldSubmit());
    }
}
