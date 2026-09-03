package com.rokid.glassesbaredevsample.webrtc;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Creates a data-only WebRTC offer and uses a local TCP socket for signaling. */
public final class WebRtcSignalingClient {
    private static final String TAG = "GlassesWebRTC";
    private static final long RETRY_DELAY_MS = 2000L;
    private static final long WORKER_STOP_TIMEOUT_MS = 5000L;
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 360;
    private static final int VIDEO_FPS = 15;

    private final Context appContext;
    private final String phoneHost;
    private final int phonePort;
    private final Listener listener;
    private final Object writerLock = new Object();
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private final List<IceCandidate> pendingLocalCandidates = new ArrayList<>();

    private volatile boolean running;
    private volatile PrintWriter writer;
    private Thread workerThread;
    private volatile Socket socket;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private org.webrtc.CapturerObserver capturerObserver;
    private boolean remoteDescriptionSet;
    private boolean localDescriptionSent;

    public WebRtcSignalingClient(Context context, String phoneHost, int phonePort) {
        this(context, phoneHost, phonePort, new Listener() {
            @Override public void onSignalingConnected() { }
            @Override public void onPeerConnected() { }
            @Override public void onPeerDisconnected() { }
        });
    }

    public WebRtcSignalingClient(
            Context context,
            String phoneHost,
            int phonePort,
            Listener listener) {
        appContext = context.getApplicationContext();
        this.phoneHost = phoneHost;
        this.phonePort = phonePort;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        initializeWebRtc();
        workerThread = new Thread(this::runClient, "WebRtcSignalingClient");
        workerThread.start();
    }

    private void initializeWebRtc() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());
        eglBase = EglBase.create();
        // The glasses firmware can crash inside the Java NetworkMonitor callback
        // after Wi-Fi changes across repeated sessions. Native enumeration is
        // sufficient for this local hotspot-only WebRTC connection.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        videoSource = factory.createVideoSource(false);
        videoSource.adaptOutputFormat(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);
        capturerObserver = videoSource.getCapturerObserver();
        localVideoTrack = factory.createVideoTrack("glasses_video", videoSource);
        localVideoTrack.setEnabled(true);
        CameraXVideoBridge.attach(capturerObserver);
        Log.i(TAG, "PeerConnectionFactory initialized with native network enumeration");
        Log.i(TAG, "Local video track initialized");
    }

    private void runClient() {
        try {
            while (running) {
                try {
                    runSession();
                } catch (Exception exception) {
                    if (running) Log.w(TAG, "Signaling connection failed; retrying", exception);
                } finally {
                    writer = null;
                    closeSocket();
                    closePeerConnection();
                }

                if (running) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            releaseWebRtcResources();
        }
    }

    private void runSession() throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(phoneHost, phonePort), 3000);
        Log.i(TAG, "Signaling connected to " + phoneHost + ":" + phonePort);
        listener.onSignalingConnected();

        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter socketWriter = new PrintWriter(socket.getOutputStream(), true)) {
            writer = socketWriter;
            createPeerConnectionAndOffer();

            String message;
            while (running && (message = reader.readLine()) != null) {
                handleSignalingMessage(message);
            }
        }
    }

    private void createPeerConnectionAndOffer() {
        remoteDescriptionSet = false;
        localDescriptionSent = false;
        pendingRemoteCandidates.clear();
        pendingLocalCandidates.clear();

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(Collections.emptyList());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(configuration, new PeerObserver());
        if (peerConnection == null) {
            throw new IllegalStateException("Failed to create PeerConnection");
        }

        peerConnection.addTrack(
                localVideoTrack,
                Collections.singletonList("glasses_stream"));
        peerConnection.setBitrate(300_000, 800_000, 1_500_000);
        Log.i(TAG, "Local video track added");

        dataChannel = peerConnection.createDataChannel("probe", new DataChannel.Init());
        attachDataChannel(dataChannel);
        Log.i(TAG, "PeerConnection and DataChannel created");

        peerConnection.createOffer(new SimpleSdpObserver("create offer") {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                peerConnection.setLocalDescription(new SimpleSdpObserver("set local offer") {
                    @Override
                    public void onSetSuccess() {
                        sendSessionDescription("offer", offer);
                        localDescriptionSent = true;
                        drainPendingLocalCandidates();
                        Log.i(TAG, "Offer sent");
                    }
                }, offer);
            }
        }, new MediaConstraints());
    }

    private void handleSignalingMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            if ("answer".equals(type)) {
                handleAnswer(json.getString("sdp"));
            } else if ("candidate".equals(type)) {
                handleRemoteCandidate(json);
            } else {
                Log.w(TAG, "Unknown signaling type: " + type);
            }
        } catch (Exception exception) {
            Log.e(TAG, "Invalid signaling message", exception);
        }
    }

    private void handleAnswer(String sdp) {
        if (peerConnection == null) return;
        SessionDescription answer =
                new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        peerConnection.setRemoteDescription(new SimpleSdpObserver("set remote answer") {
            @Override
            public void onSetSuccess() {
                remoteDescriptionSet = true;
                drainPendingRemoteCandidates();
                Log.i(TAG, "Answer applied");
            }
        }, answer);
    }

    private void handleRemoteCandidate(JSONObject json) throws Exception {
        String sdpMid = json.isNull("sdpMid") ? null : json.getString("sdpMid");
        IceCandidate candidate = new IceCandidate(
                sdpMid,
                json.getInt("sdpMLineIndex"),
                json.getString("candidate"));
        if (!candidateMatchesAddress(candidate, phoneHost)) {
            Log.i(TAG, "Remote ICE ignored outside phone hotspot interface " + candidate.sdp);
            return;
        }

        PeerConnection currentPeerConnection;
        synchronized (this) {
            currentPeerConnection = remoteDescriptionSet ? peerConnection : null;
            if (currentPeerConnection == null) {
                pendingRemoteCandidates.add(candidate);
            }
        }
        if (currentPeerConnection == null) {
            Log.i(TAG, "Remote ICE queued " + candidate.sdp);
            return;
        }
        boolean added = currentPeerConnection.addIceCandidate(candidate);
        Log.i(TAG, "Remote ICE added=" + added + " " + candidate.sdp);
    }

    private void drainPendingRemoteCandidates() {
        PeerConnection currentPeerConnection;
        List<IceCandidate> candidates;
        synchronized (this) {
            currentPeerConnection = peerConnection;
            if (currentPeerConnection == null || pendingRemoteCandidates.isEmpty()) return;
            candidates = new ArrayList<>(pendingRemoteCandidates);
            pendingRemoteCandidates.clear();
        }
        for (IceCandidate candidate : candidates) {
            boolean added = currentPeerConnection.addIceCandidate(candidate);
            Log.i(TAG, "Queued remote ICE added=" + added + " " + candidate.sdp);
        }
    }

    private void sendSessionDescription(String type, SessionDescription description) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", type);
            json.put("sdp", description.description);
            sendLine(json.toString());
        } catch (Exception exception) {
            Log.e(TAG, "Failed to encode SDP", exception);
        }
    }

    private synchronized void sendCandidate(IceCandidate candidate) {
        if (!localDescriptionSent) {
            pendingLocalCandidates.add(candidate);
            return;
        }
        emitCandidate(candidate);
    }

    private synchronized void drainPendingLocalCandidates() {
        for (IceCandidate candidate : pendingLocalCandidates) {
            emitCandidate(candidate);
        }
        pendingLocalCandidates.clear();
    }

    private void emitCandidate(IceCandidate candidate) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "candidate");
            json.put("sdpMid", candidate.sdpMid == null ? JSONObject.NULL : candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
            sendLine(json.toString());
            Log.i(TAG, "Local ICE sent " + candidate.sdp);
        } catch (Exception exception) {
            Log.e(TAG, "Failed to encode ICE candidate", exception);
        }
    }

    private void sendLine(String line) {
        synchronized (writerLock) {
            PrintWriter currentWriter = writer;
            if (currentWriter == null) {
                Log.w(TAG, "Cannot send: signaling socket is not connected");
                return;
            }
            currentWriter.println(line);
            if (currentWriter.checkError()) {
                Log.w(TAG, "Signaling write failed");
            }
        }
    }

    private void attachDataChannel(DataChannel channel) {
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                DataChannel.State state = channel.state();
                Log.i(TAG, "DataChannel state=" + state);
                if (state == DataChannel.State.OPEN) {
                    byte[] text = "GLASSES_WEBRTC_HELLO".getBytes(StandardCharsets.UTF_8);
                    channel.send(new DataChannel.Buffer(ByteBuffer.wrap(text), false));
                    Log.i(TAG, "DataChannel probe sent");
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary) return;
                ByteBuffer bytes = buffer.data;
                byte[] data = new byte[bytes.remaining()];
                bytes.get(data);
                Log.i(TAG, "DataChannel received: "
                        + new String(data, StandardCharsets.UTF_8));
            }
        });
    }

    private void closePeerConnection() {
        DataChannel dataChannelToClose;
        PeerConnection peerConnectionToClose;
        synchronized (this) {
            remoteDescriptionSet = false;
            localDescriptionSent = false;
            pendingRemoteCandidates.clear();
            pendingLocalCandidates.clear();
            dataChannelToClose = dataChannel;
            dataChannel = null;
            peerConnectionToClose = peerConnection;
            peerConnection = null;
        }

        if (dataChannelToClose != null) {
            dataChannelToClose.unregisterObserver();
            dataChannelToClose.close();
            dataChannelToClose.dispose();
        }
        if (peerConnectionToClose != null) {
            // WebRTC close callbacks may re-enter synchronized candidate handlers.
            peerConnectionToClose.close();
            peerConnectionToClose.dispose();
        }
    }

    private static boolean candidateMatchesAddress(IceCandidate candidate, String address) {
        if (address == null || address.isEmpty()) return false;
        String[] fields = candidate.sdp.trim().split("\\s+");
        return fields.length > 4 && address.equalsIgnoreCase(fields[4]);
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }

    public void stop() {
        running = false;
        closeSocket();
        Thread thread = workerThread;
        if (thread == null || thread == Thread.currentThread()) return;
        thread.interrupt();
        try {
            thread.join(WORKER_STOP_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            Log.w(TAG, "WebRTC worker did not stop before timeout; native resources retained");
        } else {
            workerThread = null;
        }
    }

    private void releaseWebRtcResources() {
        closePeerConnection();
        if (capturerObserver != null) {
            CameraXVideoBridge.detach(capturerObserver);
            capturerObserver = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
            Log.d(TAG, "Signaling state=" + state);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.i(TAG, "ICE state=" + state);
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            Log.i(TAG, "Connection state=" + state);
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                listener.onPeerConnected();
            } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED
                    || state == PeerConnection.PeerConnectionState.FAILED
                    || state == PeerConnection.PeerConnectionState.CLOSED) {
                listener.onPeerDisconnected();
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            Log.d(TAG, "ICE gathering=" + state);
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            sendCandidate(candidate);
        }

        @Override
        public void onIceCandidateError(org.webrtc.IceCandidateErrorEvent event) {
            Log.e(TAG, "ICE candidate error: " + event);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(MediaStream stream) {
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
        }

        @Override
        public void onDataChannel(DataChannel channel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        private final String operation;

        SimpleSdpObserver(String operation) {
            this.operation = operation;
        }

        @Override
        public void onCreateSuccess(SessionDescription description) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            Log.e(TAG, operation + " failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.e(TAG, operation + " failed: " + error);
        }
    }

    public interface Listener {
        void onSignalingConnected();
        void onPeerConnected();
        void onPeerDisconnected();
    }
}
