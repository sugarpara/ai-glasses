package com.example.glasses.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Receives a WebRTC offer from the glasses over a local TCP signaling connection. */
public final class LocalSignalingServer {
    private static final String TAG = "StreamSignal";
    private static final String RTC_TAG = "PhoneWebRTC";

    private final Context appContext;
    private final int port;
    private final ErrorListener errorListener;
    private final ListeningListener listeningListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object writerLock = new Object();
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private final List<IceCandidate> pendingLocalCandidates = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean currentClientSignaled;
    private long nextPeerGeneration;
    private volatile long currentPeerGeneration;
    private volatile PrintWriter writer;
    private ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile String signalingLocalAddress;
    private volatile String signalingRemoteAddress;
    private Thread serverThread;
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private volatile PeerConnection peerConnection;
    private DataChannel dataChannel;
    private VideoTrack pendingRemoteVideoTrack;
    private long pendingRemoteVideoTrackGeneration;
    private VideoTrack remoteVideoTrack;
    private VideoSink remoteVideoRenderer;
    private VideoSink remoteInferenceSink;
    private final RemoteVideoStatsSink remoteVideoStatsSink = new RemoteVideoStatsSink();
    private volatile VideoStatsListener videoStatsListener = fps -> { };
    private volatile boolean remoteDescriptionSet;
    private volatile boolean localDescriptionSent;

    public LocalSignalingServer(Context context, int port) {
        this(context, port, error -> { }, () -> { });
    }

    public LocalSignalingServer(Context context, int port, ErrorListener errorListener) {
        this(context, port, errorListener, () -> { });
    }

    public LocalSignalingServer(
            Context context,
            int port,
            ErrorListener errorListener,
            ListeningListener listeningListener) {
        appContext = context.getApplicationContext();
        this.port = port;
        this.errorListener = errorListener;
        this.listeningListener = listeningListener;
    }

    public void start() {
        if (running) return;
        running = true;
        initializeWebRtc();

        serverThread = new Thread(this::runServer, "LocalSignalingServer");
        serverThread.start();
    }

    public EglBase.Context getEglBaseContext() {
        if (eglBase == null) {
            throw new IllegalStateException("WebRTC has not been initialized");
        }
        return eglBase.getEglBaseContext();
    }

    public synchronized void setRemoteVideoRenderer(VideoSink renderer) {
        if (remoteVideoTrack != null && remoteVideoRenderer != null) {
            remoteVideoTrack.removeSink(remoteVideoRenderer);
        }
        remoteVideoRenderer = renderer;
        if (remoteVideoTrack != null && remoteVideoRenderer != null) {
            remoteVideoTrack.addSink(remoteVideoRenderer);
        }
    }

    public synchronized void setRemoteInferenceSink(VideoSink inferenceSink) {
        if (remoteVideoTrack != null && remoteInferenceSink != null) {
            remoteVideoTrack.removeSink(remoteInferenceSink);
        }
        remoteInferenceSink = inferenceSink;
        if (remoteVideoTrack != null && remoteInferenceSink != null) {
            remoteVideoTrack.addSink(remoteInferenceSink);
        }
    }

    public void setVideoStatsListener(VideoStatsListener listener) {
        videoStatsListener = listener != null ? listener : fps -> { };
    }

    private void initializeWebRtc() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());
        eglBase = EglBase.create();

        // ConnectivityManager does not expose Android's tethering/AP interface
        // as a normal Network. Let native WebRTC enumerate local interfaces so
        // the phone hotspot address can be gathered as an ICE candidate.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        Log.i(RTC_TAG, "PeerConnectionFactory initialized; native network enumeration enabled");
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            Log.i(TAG, "Listening on port " + port);
            mainHandler.post(listeningListener::onListening);

            while (running) {
                try {
                    currentClientSignaled = false;
                    clientSocket = serverSocket.accept();
                    signalingLocalAddress = clientSocket.getLocalAddress().getHostAddress();
                    signalingRemoteAddress = clientSocket.getInetAddress().getHostAddress();
                    Log.i(TAG, "Glasses connected: remote=" + signalingRemoteAddress
                            + " local=" + signalingLocalAddress);
                    handleClient(clientSocket);
                } catch (Exception exception) {
                    if (running) {
                        Log.w(TAG, "Glasses signaling session ended", exception);
                    }
                } finally {
                    boolean notifyDisconnected = running && currentClientSignaled;
                    writer = null;
                    closeClientSocket();
                    signalingLocalAddress = null;
                    signalingRemoteAddress = null;
                    closePeerConnection();
                    if (notifyDisconnected) {
                        mainHandler.post(listeningListener::onClientDisconnected);
                    }
                }
            }
        } catch (Exception exception) {
            if (running) {
                Log.e(TAG, "Server failed", exception);
                notifyError(exception);
            }
        }
    }

    private void notifyError(Throwable error) {
        mainHandler.post(() -> errorListener.onError(error));
    }

    private void handleClient(Socket socket) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter socketWriter = new PrintWriter(socket.getOutputStream(), true)) {
            writer = socketWriter;
            String message;
            while (running && (message = reader.readLine()) != null) {
                currentClientSignaled = true;
                Log.d(TAG, "Received signaling message");
                handleSignalingMessage(message);
            }
        }
    }

    private void handleSignalingMessage(String message) {
        final JSONObject json;
        try {
            json = new JSONObject(message);
        } catch (Exception exception) {
            // Keep the original text probe available for network diagnostics.
            Log.i(TAG, "Received text probe: " + message);
            sendLine("PHONE_ACK " + message);
            return;
        }

        try {
            String type = json.getString("type");
            if ("offer".equals(type)) {
                handleOffer(json.getString("sdp"));
            } else if ("candidate".equals(type)) {
                handleRemoteCandidate(json);
            } else {
                Log.w(TAG, "Unknown signaling type: " + type);
            }
        } catch (Exception exception) {
            Log.e(TAG, "Failed to process signaling message", exception);
        }
    }

    private void handleOffer(String sdp) {
        createPeerConnection();
        SessionDescription offer =
                new SessionDescription(SessionDescription.Type.OFFER, sdp);
        peerConnection.setRemoteDescription(new SimpleSdpObserver("set remote offer") {
            @Override
            public void onSetSuccess() {
                remoteDescriptionSet = true;
                drainPendingRemoteCandidates();
                createAnswer();
            }
        }, offer);
    }

    private void createAnswer() {
        Log.i(RTC_TAG, "Creating answer");
        peerConnection.createAnswer(new SimpleSdpObserver("create answer") {
            @Override
            public void onCreateSuccess(SessionDescription answer) {
                Log.i(RTC_TAG, "Answer created; setting local description");
                peerConnection.setLocalDescription(new SimpleSdpObserver("set local answer") {
                    @Override
                    public void onSetSuccess() {
                        localDescriptionSent = true;
                        sendSessionDescription("answer", answer);
                        Log.i(RTC_TAG, "Answer sent");
                        attachPendingRemoteVideoTrack();
                        drainPendingLocalCandidates();
                    }
                }, answer);
            }
        }, new MediaConstraints());
    }

    private void handleRemoteCandidate(JSONObject json) throws Exception {
        String sdpMid = json.isNull("sdpMid") ? null : json.getString("sdpMid");
        IceCandidate candidate = new IceCandidate(
                sdpMid,
                json.getInt("sdpMLineIndex"),
                json.getString("candidate"));
        if (!candidateMatchesAddress(candidate, signalingRemoteAddress)) {
            Log.i(RTC_TAG, "Remote ICE ignored outside signaling network " + candidate.sdp);
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
            Log.i(RTC_TAG, "Remote ICE queued " + candidate.sdp);
            return;
        }
        boolean added = currentPeerConnection.addIceCandidate(candidate);
        Log.i(RTC_TAG, "Remote ICE added=" + added + " " + candidate.sdp);
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
            Log.i(RTC_TAG, "Queued remote ICE added=" + added + " " + candidate.sdp);
        }
    }

    private void createPeerConnection() {
        closePeerConnection();

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(Collections.emptyList());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        long peerGeneration = ++nextPeerGeneration;
        PeerConnection newPeerConnection =
                factory.createPeerConnection(configuration, new PeerObserver(peerGeneration));
        if (newPeerConnection == null) {
            throw new IllegalStateException("Failed to create PeerConnection");
        }
        synchronized (this) {
            peerConnection = newPeerConnection;
            currentPeerGeneration = peerGeneration;
        }
        Log.i(RTC_TAG, "PeerConnection created generation=" + peerGeneration);
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

    private void sendCandidate(IceCandidate candidate) {
        if (!candidateMatchesAddress(candidate, signalingLocalAddress)) {
            Log.i(RTC_TAG, "Local ICE ignored outside hotspot interface " + candidate.sdp);
            return;
        }
        synchronized (this) {
            if (!localDescriptionSent) {
                pendingLocalCandidates.add(candidate);
                return;
            }
        }
        emitCandidate(candidate);
    }

    private void drainPendingLocalCandidates() {
        List<IceCandidate> candidates;
        synchronized (this) {
            if (pendingLocalCandidates.isEmpty()) return;
            candidates = new ArrayList<>(pendingLocalCandidates);
            pendingLocalCandidates.clear();
        }
        for (IceCandidate candidate : candidates) {
            emitCandidate(candidate);
        }
    }

    private void emitCandidate(IceCandidate candidate) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "candidate");
            json.put("sdpMid", candidate.sdpMid == null ? JSONObject.NULL : candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
            sendLine(json.toString());
            Log.i(RTC_TAG, "Local ICE sent " + candidate.sdp);
        } catch (Exception exception) {
            Log.e(TAG, "Failed to encode ICE candidate", exception);
        }
    }

    private void sendLine(String line) {
        synchronized (writerLock) {
            PrintWriter currentWriter = writer;
            if (currentWriter == null) {
                Log.w(TAG, "Cannot send: signaling client is not connected");
                return;
            }
            currentWriter.println(line);
            if (currentWriter.checkError()) {
                Log.w(TAG, "Signaling write failed");
            }
        }
    }

    private void attachDataChannel(DataChannel channel) {
        dataChannel = channel;
        channel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                Log.i(RTC_TAG, "DataChannel state=" + channel.state());
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary) return;
                ByteBuffer bytes = buffer.data;
                byte[] data = new byte[bytes.remaining()];
                bytes.get(data);
                String text = new String(data, StandardCharsets.UTF_8);
                Log.i(RTC_TAG, "DataChannel received: " + text);
                byte[] ack = "PHONE_WEBRTC_ACK".getBytes(StandardCharsets.UTF_8);
                channel.send(new DataChannel.Buffer(ByteBuffer.wrap(ack), false));
            }
        });
    }

    private void closePeerConnection() {
        VideoTrack videoTrackToClose;
        VideoSink rendererToRemove;
        VideoSink inferenceSinkToRemove;
        DataChannel dataChannelToClose;
        PeerConnection peerConnectionToClose;
        synchronized (this) {
            remoteDescriptionSet = false;
            localDescriptionSent = false;
            pendingRemoteCandidates.clear();
            pendingLocalCandidates.clear();
            videoTrackToClose = remoteVideoTrack;
            rendererToRemove = remoteVideoRenderer;
            inferenceSinkToRemove = remoteInferenceSink;
            remoteVideoTrack = null;
            pendingRemoteVideoTrack = null;
            pendingRemoteVideoTrackGeneration = 0L;
            dataChannelToClose = dataChannel;
            dataChannel = null;
            peerConnectionToClose = peerConnection;
            peerConnection = null;
            currentPeerGeneration = 0L;
        }

        if (videoTrackToClose != null) {
            if (rendererToRemove != null) {
                videoTrackToClose.removeSink(rendererToRemove);
            }
            if (inferenceSinkToRemove != null) {
                videoTrackToClose.removeSink(inferenceSinkToRemove);
            }
            videoTrackToClose.removeSink(remoteVideoStatsSink);
            remoteVideoStatsSink.reset();
        }
        if (dataChannelToClose != null) {
            dataChannelToClose.unregisterObserver();
            dataChannelToClose.close();
            dataChannelToClose.dispose();
        }
        if (peerConnectionToClose != null) {
            // WebRTC close callbacks may re-enter synchronized state handlers.
            peerConnectionToClose.close();
            peerConnectionToClose.dispose();
        }
    }

    private static boolean candidateMatchesAddress(IceCandidate candidate, String address) {
        if (address == null || address.isEmpty()) return false;
        String[] fields = candidate.sdp.trim().split("\\s+");
        return fields.length > 4 && address.equalsIgnoreCase(fields[4]);
    }

    private void closeClientSocket() {
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (Exception ignored) {
        }
        clientSocket = null;
    }

    /** Ends only the current glasses client while keeping port 8888 listening. */
    public void resetClientSession() {
        if (!running || clientSocket == null) return;
        Log.i(TAG, "Resetting current glasses signaling session");
        closeClientSocket();
    }

    public void stop() {
        running = false;
        closeClientSocket();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        closePeerConnection();
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    private synchronized void rememberRemoteVideoTrack(
            MediaStreamTrack track,
            long peerGeneration) {
        if (!(track instanceof VideoTrack)) return;
        if (peerGeneration != currentPeerGeneration) {
            Log.i("PhoneVideo", "Ignoring video track from stale peer generation=" + peerGeneration);
            return;
        }
        VideoTrack videoTrack = (VideoTrack) track;
        if (remoteVideoTrack != null && remoteVideoTrack.id().equals(videoTrack.id())) return;
        if (pendingRemoteVideoTrack != null
                && pendingRemoteVideoTrack.id().equals(videoTrack.id())) return;
        pendingRemoteVideoTrack = videoTrack;
        pendingRemoteVideoTrackGeneration = peerGeneration;
        Log.i("PhoneVideo", "Remote video track discovered id=" + videoTrack.id());
        if (localDescriptionSent) attachPendingRemoteVideoTrack();
    }

    private synchronized void attachPendingRemoteVideoTrack() {
        VideoTrack videoTrack = pendingRemoteVideoTrack;
        if (videoTrack == null) return;
        long peerGeneration = pendingRemoteVideoTrackGeneration;
        pendingRemoteVideoTrack = null;
        pendingRemoteVideoTrackGeneration = 0L;
        Log.i("PhoneVideo", "Scheduling remote video sink attachment");
        mainHandler.post(() -> attachRemoteVideoTrackOnMainThread(videoTrack, peerGeneration));
    }

    private synchronized void attachRemoteVideoTrackOnMainThread(
            VideoTrack videoTrack,
            long peerGeneration) {
        if (!running || peerConnection == null || peerGeneration != currentPeerGeneration) {
            Log.w("PhoneVideo", "Skipped video sink attachment because session is closed");
            return;
        }
        if (remoteVideoTrack != null) {
            remoteVideoTrack.removeSink(remoteVideoStatsSink);
        }
        remoteVideoTrack = videoTrack;
        remoteVideoStatsSink.reset();
        Log.i("PhoneVideo", "Attaching remote video sink id=" + videoTrack.id());
        remoteVideoTrack.addSink(remoteVideoStatsSink);
        if (remoteVideoRenderer != null) {
            remoteVideoTrack.addSink(remoteVideoRenderer);
        }
        if (remoteInferenceSink != null) {
            remoteVideoTrack.addSink(remoteInferenceSink);
        }
        Log.i("PhoneVideo", "Remote video track attached id=" + videoTrack.id());
    }

    private final class PeerObserver implements PeerConnection.Observer {
        private final long peerGeneration;

        PeerObserver(long peerGeneration) {
            this.peerGeneration = peerGeneration;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState state) {
            Log.d(RTC_TAG, "Signaling state=" + state);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            Log.i(RTC_TAG, "ICE state=" + state);
            if (state == PeerConnection.IceConnectionState.FAILED
                    || state == PeerConnection.IceConnectionState.CLOSED) {
                releaseFailedSignalingSession("ICE state=" + state, peerGeneration);
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            Log.i(RTC_TAG, "Connection state=" + state);
            if (state == PeerConnection.PeerConnectionState.FAILED
                    || state == PeerConnection.PeerConnectionState.CLOSED) {
                releaseFailedSignalingSession("connection state=" + state, peerGeneration);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            Log.d(RTC_TAG, "ICE gathering=" + state);
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            sendCandidate(candidate);
        }

        @Override
        public void onIceCandidateError(org.webrtc.IceCandidateErrorEvent event) {
            Log.e(RTC_TAG, "ICE candidate error: " + event);
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
            Log.i(RTC_TAG, "Remote DataChannel received: " + channel.label());
            attachDataChannel(channel);
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
            rememberRemoteVideoTrack(receiver.track(), peerGeneration);
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            rememberRemoteVideoTrack(transceiver.getReceiver().track(), peerGeneration);
        }
    }

    private void releaseFailedSignalingSession(String reason, long peerGeneration) {
        if (peerGeneration != currentPeerGeneration) {
            Log.i(
                    TAG,
                    "Ignoring stale peer callback generation=" + peerGeneration
                            + " current=" + currentPeerGeneration
                            + " reason=" + reason);
            return;
        }
        Log.w(TAG, "Releasing stale glasses signaling session: " + reason);
        closeClientSocket();
    }

    private final class RemoteVideoStatsSink implements VideoSink {
        private int frameCount;
        private long windowStartNs;
        private boolean firstFrameLogged;

        @Override
        public synchronized void onFrame(VideoFrame frame) {
            long now = System.nanoTime();
            if (windowStartNs == 0L) windowStartNs = now;
            frameCount++;

            if (!firstFrameLogged) {
                firstFrameLogged = true;
                Log.i(
                        "PhoneVideo",
                        "Remote video first frame buffer="
                                + frame.getBuffer().getWidth() + "x"
                                + frame.getBuffer().getHeight()
                                + " rotation=" + frame.getRotation()
                                + " displayed=" + frame.getRotatedWidth() + "x"
                                + frame.getRotatedHeight());
            }

            long elapsedNs = now - windowStartNs;
            if (elapsedNs >= 1_000_000_000L) {
                double fps = frameCount * 1_000_000_000.0 / elapsedNs;
                Log.i(
                        "PhoneVideo",
                        String.format(
                                Locale.US,
                                "Remote video FPS=%.1f buffer=%dx%d rotation=%d",
                                fps,
                                frame.getBuffer().getWidth(),
                                frame.getBuffer().getHeight(),
                                frame.getRotation()));
                mainHandler.post(() -> videoStatsListener.onRemoteFps(fps));
                frameCount = 0;
                windowStartNs = now;
            }
        }

        synchronized void reset() {
            frameCount = 0;
            windowStartNs = 0L;
            firstFrameLogged = false;
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
            Log.e(RTC_TAG, operation + " failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.e(RTC_TAG, operation + " failed: " + error);
        }
    }

    public interface ErrorListener {
        void onError(Throwable error);
    }

    public interface ListeningListener {
        void onListening();

        default void onClientDisconnected() {
        }
    }

    public interface VideoStatsListener {
        void onRemoteFps(double fps);
    }
}
