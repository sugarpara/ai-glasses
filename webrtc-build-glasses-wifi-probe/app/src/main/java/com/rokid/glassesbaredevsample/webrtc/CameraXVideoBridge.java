package com.rokid.glassesbaredevsample.webrtc;

import android.graphics.ImageFormat;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;

/** Converts CameraX YUV_420_888 frames to I420 frames consumed by WebRTC. */
public final class CameraXVideoBridge {
    private static final String TAG = "GlassesVideo";
    private static final int CAMERA_FPS = 15;
    private static final int TARGET_FPS = 15;
    private static final Object LOCK = new Object();
    private static final FrameCadenceGate FRAME_GATE =
            new FrameCadenceGate(CAMERA_FPS, TARGET_FPS);
    private static final ThreadLocal<byte[]> ROW_BUFFER =
            ThreadLocal.withInitial(() -> new byte[0]);

    private static CapturerObserver capturerObserver;
    private static boolean firstFrameLogged;

    private CameraXVideoBridge() {
    }

    public static void attach(CapturerObserver observer) {
        synchronized (LOCK) {
            if (capturerObserver != null && capturerObserver != observer) {
                capturerObserver.onCapturerStopped();
            }
            capturerObserver = observer;
            FRAME_GATE.reset();
            firstFrameLogged = false;
            observer.onCapturerStarted(true);
            Log.i(TAG, "CameraX bridge attached");
        }
    }

    public static void detach(CapturerObserver observer) {
        synchronized (LOCK) {
            if (capturerObserver != observer) return;
            capturerObserver.onCapturerStopped();
            capturerObserver = null;
            FRAME_GATE.reset();
            Log.i(TAG, "CameraX bridge detached");
        }
    }

    public static boolean submit(ImageProxy image) {
        final CapturerObserver observer;
        final long timestampNs = image.getImageInfo().getTimestamp();
        synchronized (LOCK) {
            observer = capturerObserver;
            if (observer == null) return false;
            if (!FRAME_GATE.shouldSubmit()) return false;
        }

        if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length < 3) {
            Log.w(TAG, "Unsupported CameraX image format=" + image.getFormat());
            return false;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        JavaI420Buffer i420 = JavaI420Buffer.allocate(width, height);
        VideoFrame frame = null;
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            copyPlane(
                    planes[0],
                    width,
                    height,
                    i420.getDataY(),
                    i420.getStrideY());
            copyPlane(
                    planes[1],
                    (width + 1) / 2,
                    (height + 1) / 2,
                    i420.getDataU(),
                    i420.getStrideU());
            copyPlane(
                    planes[2],
                    (width + 1) / 2,
                    (height + 1) / 2,
                    i420.getDataV(),
                    i420.getStrideV());

            int rotation = image.getImageInfo().getRotationDegrees();
            frame = new VideoFrame(i420, rotation, timestampNs);
            synchronized (LOCK) {
                if (capturerObserver != observer) return false;
            }
            observer.onFrameCaptured(frame);

            synchronized (LOCK) {
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    Log.i(TAG, "First WebRTC frame submitted size="
                            + width + "x" + height + " rotation=" + rotation);
                }
            }
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to convert CameraX frame", exception);
            return false;
        } finally {
            if (frame != null) {
                frame.release();
            } else {
                i420.release();
            }
        }
    }

    private static void copyPlane(
            ImageProxy.PlaneProxy plane,
            int width,
            int height,
            ByteBuffer destination,
            int destinationStride) {
        ByteBuffer source = plane.getBuffer().duplicate();
        int sourceBase = source.position();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int sourceRowLength = (width - 1) * pixelStride + 1;
        byte[] rowBuffer = ROW_BUFFER.get();
        if (rowBuffer.length < sourceRowLength) {
            rowBuffer = new byte[sourceRowLength];
            ROW_BUFFER.set(rowBuffer);
        }
        ByteBuffer output = destination.duplicate();

        for (int row = 0; row < height; row++) {
            int sourceRow = sourceBase + row * rowStride;
            int destinationRow = row * destinationStride;
            source.limit(source.capacity());
            source.position(sourceRow);
            source.get(rowBuffer, 0, sourceRowLength);
            output.position(destinationRow);
            if (pixelStride == 1) {
                output.put(rowBuffer, 0, width);
            } else {
                for (int column = 0; column < width; column++) {
                    output.put(destinationRow + column, rowBuffer[column * pixelStride]);
                }
            }
        }
    }
}
