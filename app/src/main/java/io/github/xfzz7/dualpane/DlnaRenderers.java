package io.github.xfzz7.dualpane;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two ordinary DLNA media renderers on one TV.
 * Phones discover them under the two localized renderer names.
 * This is ordinary DLNA, separate from Miracast, Lebo, and Chromecast.
 * Xiaomi already listens on UDP 1900, so the search socket is opened with port reuse.
 */
final class DlnaRenderers {
    interface Sink {
        void cast(boolean left, String uri);

        void pause(boolean left);

        void resume(boolean left);

        void stop(boolean left);

        void seekTo(boolean left, long positionMs);

        void setVolume(boolean left, float volume);

        Status status(boolean left);
    }

    static final class Status {
        final String state;
        final long positionMs;
        final long durationMs;
        final float volume;
        final String uri;

        Status(String state, long positionMs, long durationMs, float volume, String uri) {
            this.state = state;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.volume = volume;
            this.uri = uri == null ? "" : uri;
        }
    }

    private static final String TAG = "DualPane";
    private static final String SERVER = "Linux/3.10 UPnP/1.0 DualPane/1.0";
    private static final String GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String SINK_PROTO =
            "http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:video/vnd.dlna.mpeg-tts:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:application/dash+xml:*,http-get:*:audio/mpeg:*,http-get:*:*:*";
    private static final Pattern RES = Pattern.compile(
            "<res\\b[^>]*>([^<]+)</res>", Pattern.CASE_INSENSITIVE);

    private final Context app;
    private final Sink sink;
    private final Renderer left;
    private final Renderer right;
    private WifiManager.MulticastLock multicastLock;
    private MulticastSocket ssdp;
    private ExecutorService replies;
    private volatile boolean running;

    DlnaRenderers(Context context, Sink sink) {
        this.app = context.getApplicationContext();
        this.sink = sink;
        this.left = new Renderer(true, this.app.getString(R.string.dlna_device_left), 8191);
        this.right = new Renderer(false, this.app.getString(R.string.dlna_device_right), 8192);
    }

    void start(SharedPreferences prefs) {
        if (running) {
            return;
        }
        running = true;
        left.udn = udn(prefs, "dlna_udn_left");
        right.udn = udn(prefs, "dlna_udn_right");
        acquireMulticast();
        replies = Executors.newFixedThreadPool(4);
        left.open();
        right.open();
        openSsdp();
        Thread announce = new Thread(this::announceLoop, "dual-ssdp");
        announce.setDaemon(true);
        announce.start();
        Log.i(TAG, "dlna " + left.friendly + " :" + left.port + " " + right.friendly + " :" + right.port);
    }

    void stop() {
        running = false;
        announce(true);
        if (ssdp != null) {
            ssdp.close();
        }
        left.close();
        right.close();
        if (replies != null) {
            replies.shutdownNow();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private void acquireMulticast() {
        try {
            Object service = app.getSystemService(Context.WIFI_SERVICE);
            if (service instanceof WifiManager) {
                multicastLock = ((WifiManager) service).createMulticastLock("dualpane-dlna");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "multicast lock " + e.getMessage());
        }
    }

    private static String udn(SharedPreferences prefs, String key) {
        String saved = prefs.getString(key, "");
        if (saved == null || !saved.startsWith("uuid:")) {
            saved = "uuid:" + UUID.randomUUID();
            prefs.edit().putString(key, saved).apply();
        }
        return saved;
    }

    private void openSsdp() {
        String ip = Lan.lanIp();
        InetAddress[] bindAddresses = bindAddresses(ip);
        Exception last = null;
        for (InetAddress bindAddress : bindAddresses) {
            try {
                MulticastSocket socket = new MulticastSocket(null);
                socket.setReuseAddress(true);
                enableReusePort(socket);
                socket.bind(new InetSocketAddress(bindAddress, SSDP_PORT));
                socket.setTimeToLive(4);
                socket.setSoTimeout(1000);
                NetworkInterface iface = lanInterface();
                InetAddress group = InetAddress.getByName(GROUP);
                if (iface != null) {
                    socket.setNetworkInterface(iface);
                    socket.joinGroup(new InetSocketAddress(group, SSDP_PORT), iface);
                } else {
                    socket.joinGroup(group);
                }
                ssdp = socket;
                Thread listen = new Thread(this::listenSsdp, "dual-ssdp-in");
                listen.setDaemon(true);
                listen.start();
                Log.i(TAG, "ssdp listening on " + bindAddress.getHostAddress());
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "ssdp bind " + bindAddress.getHostAddress() + " " + e.getMessage());
            }
        }
        Log.e(TAG, "ssdp bind failed, notify only", last);
        try {
            MulticastSocket sendOnly = new MulticastSocket();
            sendOnly.setTimeToLive(4);
            NetworkInterface iface = lanInterface();
            if (iface != null) {
                sendOnly.setNetworkInterface(iface);
            }
            ssdp = sendOnly;
        } catch (Exception sendError) {
            Log.e(TAG, "ssdp send failed", sendError);
        }
    }

    private static InetAddress[] bindAddresses(String ip) {
        try {
            InetAddress wildcard = InetAddress.getByName("0.0.0.0");
            if (ip == null) {
                return new InetAddress[]{wildcard};
            }
            return new InetAddress[]{wildcard, InetAddress.getByName(ip)};
        } catch (Exception e) {
            return new InetAddress[0];
        }
    }

    private static void enableReusePort(MulticastSocket socket) {
        try {
            // Xiaomi already binds UDP 1900. Reuse lets this app hear the same searches.
            // 15 is SO_REUSEPORT. Do not wrap the descriptor: closing that wrapper kills the socket.
            Method method = java.net.DatagramSocket.class.getDeclaredMethod("getFileDescriptor$");
            method.setAccessible(true);
            FileDescriptor fd = (FileDescriptor) method.invoke(socket);
            if (fd == null || !fd.valid()) {
                Log.w(TAG, "SO_REUSEPORT fd not ready");
                return;
            }
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, 15, 1);
        } catch (Exception e) {
            Log.w(TAG, "SO_REUSEPORT " + e.getMessage());
        }
    }

    private void listenSsdp() {
        byte[] buf = new byte[2048];
        while (running && ssdp != null && !ssdp.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                ssdp.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "ssdp recv " + e.getMessage());
                }
                continue;
            }
            String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            if (!text.startsWith("M-SEARCH")) {
                continue;
            }
            String st = header(text, "ST");
            InetAddress from = packet.getAddress();
            int port = packet.getPort();
            replies.execute(() -> replySearch(from, port, st));
        }
    }

    private void replySearch(InetAddress from, int port, String st) {
        if (from == null || st == null || st.isEmpty()) {
            return;
        }
        String wanted = st.trim();
        for (Renderer renderer : new Renderer[]{left, right}) {
            if (!renderer.alive) {
                continue;
            }
            if ("ssdp:all".equalsIgnoreCase(wanted) || "upnp:rootdevice".equalsIgnoreCase(wanted)) {
                sendSearch(from, port, renderer, "upnp:rootdevice", renderer.udn + "::upnp:rootdevice");
                sendSearch(from, port, renderer, MEDIA_RENDERER, renderer.udn + "::" + MEDIA_RENDERER);
                sendSearch(from, port, renderer, AVT, renderer.udn + "::" + AVT);
            } else if (wanted.startsWith("urn:schemas-upnp-org:device:MediaRenderer")) {
                sendSearch(from, port, renderer, wanted, renderer.udn + "::" + wanted);
            } else if (AVT.equals(wanted) || RCS.equals(wanted) || CMS.equals(wanted)) {
                sendSearch(from, port, renderer, wanted, renderer.udn + "::" + wanted);
            } else if (wanted.equals(renderer.udn)) {
                sendSearch(from, port, renderer, renderer.udn, renderer.udn);
            }
        }
    }

    private void sendSearch(InetAddress to, int port, Renderer renderer, String st, String usn) {
        String ip = Lan.lanIp();
        if (ip == null || ssdp == null) {
            return;
        }
        String body = "HTTP/1.1 200 OK\r\n"
                + "CACHE-CONTROL: max-age=1800\r\n"
                + "DATE: " + httpDate() + "\r\n"
                + "EXT:\r\n"
                + "LOCATION: " + renderer.location(ip) + "\r\n"
                + "SERVER: " + SERVER + "\r\n"
                + "ST: " + st + "\r\n"
                + "USN: " + usn + "\r\n"
                + "CONTENT-LENGTH: 0\r\n\r\n";
        send(body, new InetSocketAddress(to, port));
    }

    private void announceLoop() {
        announce(false);
        long next = System.currentTimeMillis() + 800;
        int burst = 1;
        while (running) {
            long now = System.currentTimeMillis();
            if (now >= next) {
                announce(false);
                burst++;
                next = now + (burst < 3 ? 800 : 15000);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void announce(boolean bye) {
        String ip = Lan.lanIp();
        if (ip == null || ssdp == null) {
            return;
        }
        for (Renderer renderer : new Renderer[]{left, right}) {
            if (!renderer.alive && !bye) {
                continue;
            }
            String[][] targets = {
                    {"upnp:rootdevice", renderer.udn + "::upnp:rootdevice"},
                    {renderer.udn, renderer.udn},
                    {MEDIA_RENDERER, renderer.udn + "::" + MEDIA_RENDERER},
                    {AVT, renderer.udn + "::" + AVT},
                    {RCS, renderer.udn + "::" + RCS},
                    {CMS, renderer.udn + "::" + CMS},
            };
            for (String[] target : targets) {
                String body = "NOTIFY * HTTP/1.1\r\n"
                        + "HOST: " + GROUP + ":" + SSDP_PORT + "\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: " + renderer.location(ip) + "\r\n"
                        + "NT: " + target[0] + "\r\n"
                        + "NTS: ssdp:" + (bye ? "byebye" : "alive") + "\r\n"
                        + "SERVER: " + SERVER + "\r\n"
                        + "USN: " + target[1] + "\r\n\r\n";
                send(body, new InetSocketAddress(GROUP, SSDP_PORT));
            }
        }
    }

    private void send(String body, InetSocketAddress to) {
        if (ssdp == null || ssdp.isClosed()) {
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            ssdp.send(new DatagramPacket(bytes, bytes.length, to));
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "ssdp send " + e.getMessage());
            }
        }
    }

    private static NetworkInterface lanInterface() {
        try {
            String ip = Lan.lanIp();
            if (ip == null) {
                return null;
            }
            return NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
        } catch (Exception e) {
            return null;
        }
    }

    private final class Renderer {
        final boolean leftSide;
        final String friendly;
        final int port;
        String udn = "";
        volatile String meta = "";
        volatile boolean alive;
        private ServerSocket http;
        private final ExecutorService clients = Executors.newFixedThreadPool(3);

        Renderer(boolean leftSide, String friendly, int port) {
            this.leftSide = leftSide;
            this.friendly = friendly;
            this.port = port;
        }

        String location(String ip) {
            return "http://" + ip + ":" + port + "/description.xml";
        }

        void open() {
            try {
                http = new ServerSocket();
                http.setReuseAddress(true);
                http.bind(new InetSocketAddress(port));
                alive = true;
                Thread accept = new Thread(this::loop, "dual-dmr-" + port);
                accept.setDaemon(true);
                accept.start();
            } catch (Exception e) {
                alive = false;
                Log.e(TAG, friendly + " port " + port + " failed", e);
            }
        }

        void close() {
            alive = false;
            try {
                if (http != null) {
                    http.close();
                }
            } catch (Exception ignored) {
            }
            clients.shutdownNow();
        }

        private void loop() {
            while (running && alive) {
                try {
                    Socket client = http.accept();
                    clients.execute(() -> handle(client));
                } catch (Exception e) {
                    if (running && alive) {
                        Log.w(TAG, friendly + " accept " + e.getMessage());
                    }
                }
            }
        }

        private void handle(Socket client) {
            try {
                client.setSoTimeout(8000);
                InputStream in = client.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int prev = -1;
                while (head.size() < 16384) {
                    int b = in.read();
                    if (b < 0) {
                        break;
                    }
                    head.write(b);
                    if (prev == '\r' && b == '\n'
                            && head.toString(StandardCharsets.ISO_8859_1.name()).contains("\r\n\r\n")) {
                        break;
                    }
                    prev = b;
                }
                String header = head.toString(StandardCharsets.ISO_8859_1.name());
                if (header.toLowerCase(Locale.US).contains("expect: 100-continue")) {
                    client.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                }
                byte[] body = readBody(in, header);
                String start = header.isEmpty() ? "" : header.substring(0, header.indexOf('\r'));
                String[] parts = start.split(" ");
                String method = parts.length > 0 ? parts[0].toUpperCase(Locale.US) : "";
                String target = parts.length > 1 ? parts[1] : "/";
                int q = target.indexOf('?');
                String path = q >= 0 ? target.substring(0, q) : target;
                String payload = new String(body, StandardCharsets.UTF_8);
                if ("SUBSCRIBE".equals(method) || "UNSUBSCRIBE".equals(method)) {
                    rejectEventing(client);
                    return;
                }
                if ("GET".equals(method) || "HEAD".equals(method)) {
                    serveGet(client, path, "HEAD".equals(method));
                    return;
                }
                if ("POST".equals(method)) {
                    serveSoap(client, path, header(header, "SOAPACTION"), payload);
                    return;
                }
                writeBytes(client, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                // Malformed requests and transient OOM must not kill this worker silently.
                Log.w(TAG, friendly + " http " + t);
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void serveGet(Socket client, String path, boolean head) throws Exception {
            String ip = Lan.lanIp();
            byte[] body;
            String type;
            if ("/description.xml".equals(path) || "/".equals(path) || "/device.xml".equals(path)) {
                body = description(ip == null ? "127.0.0.1" : ip).getBytes(StandardCharsets.UTF_8);
                type = "text/xml; charset=\"utf-8\"";
            } else if (path.endsWith("AVTransport.xml")) {
                body = avTransportScpd().getBytes(StandardCharsets.UTF_8);
                type = "text/xml; charset=\"utf-8\"";
            } else if (path.endsWith("RenderingControl.xml")) {
                body = renderingScpd().getBytes(StandardCharsets.UTF_8);
                type = "text/xml; charset=\"utf-8\"";
            } else if (path.endsWith("ConnectionManager.xml")) {
                body = connectionScpd().getBytes(StandardCharsets.UTF_8);
                type = "text/xml; charset=\"utf-8\"";
            } else {
                writeBytes(client, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            writeBytes(client, 200, type, head ? new byte[0] : body, body.length);
        }

        private void rejectEventing(Socket client) throws Exception {
            // No GENA implementation: a 412 tells control points to fall back to polling
            // instead of pretending a subscription exists and never sending an event.
            String head = "HTTP/1.1 412 Precondition Failed\r\n"
                    + "SERVER: " + SERVER + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            client.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
        }

        private void serveSoap(Socket client, String path, String soapAction, String payload) throws Exception {
            String action = actionName(soapAction);
            if (action.isEmpty()) {
                action = actionFromBody(payload);
            }
            Map<String, String> args = soapArgs(payload);
            String service = serviceFor(path, soapAction);
            Log.i(TAG, friendly + " " + action);
            String inner = dispatch(action, args);
            if (inner == null) {
                writeBytes(client, 500, "text/xml; charset=\"utf-8\"", fault(service, 401, "Invalid Action"));
                return;
            }
            writeBytes(client, 200, "text/xml; charset=\"utf-8\"", envelope(service, action, inner));
        }

        private String dispatch(String action, Map<String, String> args) {
            switch (action) {
                case "SetAVTransportURI":
                case "SetNextAVTransportURI":
                    if ("SetAVTransportURI".equals(action)) {
                        String uri = pickUri(args.get("CurrentURI"), args.get("CurrentURIMetaData"));
                        if (uri.isEmpty()) {
                            return "";
                        }
                        meta = args.get("CurrentURIMetaData") == null ? "" : args.get("CurrentURIMetaData");
                        Log.i(TAG, friendly + " cast " + safeUri(uri));
                        sink.cast(leftSide, uri);
                    }
                    return "";
                case "Play":
                    sink.resume(leftSide);
                    return "";
                case "Pause":
                    sink.pause(leftSide);
                    return "";
                case "Stop":
                    sink.stop(leftSide);
                    return "";
                case "Seek":
                    sink.seekTo(leftSide, parseTime(args.get("Target")));
                    return "";
                case "Next":
                case "Previous":
                    return "";
                case "GetTransportInfo":
                    return "<CurrentTransportState>" + state() + "</CurrentTransportState>"
                            + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                            + "<CurrentSpeed>1</CurrentSpeed>";
                case "GetPositionInfo": {
                    Status now = sink.status(leftSide);
                    return "<Track>1</Track>"
                            + "<TrackDuration>" + relTime(now.durationMs) + "</TrackDuration>"
                            + "<TrackMetaData>" + xml(metaOrDidl(now.uri)) + "</TrackMetaData>"
                            + "<TrackURI>" + xml(now.uri) + "</TrackURI>"
                            + "<RelTime>" + relTime(now.positionMs) + "</RelTime>"
                            + "<AbsTime>" + relTime(now.positionMs) + "</AbsTime>"
                            + "<RelCount>2147483647</RelCount>"
                            + "<AbsCount>2147483647</AbsCount>";
                }
                case "GetMediaInfo":
                case "GetMediaInfo_Ext": {
                    Status now = sink.status(leftSide);
                    String duration = now.durationMs > 0 ? relTime(now.durationMs) : "NOT_IMPLEMENTED";
                    return "<NrTracks>" + (now.uri.isEmpty() ? 0 : 1) + "</NrTracks>"
                            + "<MediaDuration>" + duration + "</MediaDuration>"
                            + "<CurrentURI>" + xml(now.uri) + "</CurrentURI>"
                            + "<CurrentURIMetaData>" + xml(metaOrDidl(now.uri)) + "</CurrentURIMetaData>"
                            + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                            + "<PlayMedium>" + (now.uri.isEmpty() ? "NONE" : "NETWORK") + "</PlayMedium>"
                            + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                            + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
                }
                case "GetDeviceCapabilities":
                    return "<PlayMedia>NONE,NETWORK</PlayMedia>"
                            + "<RecMedia>NOT_IMPLEMENTED</RecMedia>"
                            + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>";
                case "GetTransportSettings":
                    return "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>";
                case "GetCurrentTransportActions":
                    return "<Actions>Play,Pause,Stop,Seek</Actions>";
                case "GetVolume":
                case "GetVolumeDB": {
                    int volume = Math.round(sink.status(leftSide).volume * 100f);
                    return "<CurrentVolume>" + volume + "</CurrentVolume>";
                }
                case "SetVolume":
                case "SetVolumeDB":
                    sink.setVolume(leftSide, parseVolume(args.get("DesiredVolume")));
                    return "";
                case "GetMute":
                    return "<CurrentMute>" + (sink.status(leftSide).volume <= 0.001f ? 1 : 0) + "</CurrentMute>";
                case "SetMute":
                    boolean mute = "1".equals(args.get("Mute")) || "true".equalsIgnoreCase(args.get("Mute"));
                    sink.setVolume(leftSide, mute ? 0f : Math.max(0.35f, sink.status(leftSide).volume));
                    return "";
                case "ListPresets":
                    return "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>";
                case "SelectPreset":
                    return "";
                case "GetProtocolInfo":
                    return "<Source></Source><Sink>" + xml(SINK_PROTO) + "</Sink>";
                case "GetCurrentConnectionIDs":
                    return "<ConnectionIDs>0</ConnectionIDs>";
                case "GetCurrentConnectionInfo":
                    return "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>"
                            + "<ProtocolInfo>http-get:*:*:*</ProtocolInfo>"
                            + "<PeerConnectionManager></PeerConnectionManager>"
                            + "<PeerConnectionID>-1</PeerConnectionID>"
                            + "<Direction>Input</Direction><Status>OK</Status>";
                default:
                    return null;
            }
        }

        private String state() {
            String value = sink.status(leftSide).state;
            if (value == null || value.isEmpty()) {
                return "NO_MEDIA_PRESENT";
            }
            return value;
        }

        private String metaOrDidl(String uri) {
            if (meta != null && !meta.isEmpty()) {
                return meta;
            }
            if (uri == null || uri.isEmpty()) {
                return "";
            }
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                    + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                    + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                    + "<dc:title>" + friendly + "</dc:title>"
                    + "<upnp:class>object.item.videoItem</upnp:class>"
                    + "<res protocolInfo=\"http-get:*:*:*\">" + uri.replace("&", "&amp;") + "</res>"
                    + "</item></DIDL-Lite>";
        }

        private String description(String ip) {
            String base = "http://" + ip + ":" + port;
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                    + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                    + "<device>"
                    + "<deviceType>" + MEDIA_RENDERER + "</deviceType>"
                    + "<friendlyName>" + xml(friendly) + "</friendlyName>"
                    + "<manufacturer>DualPane</manufacturer>"
                    + "<manufacturerURL>" + base + "/</manufacturerURL>"
                    + "<modelDescription>Dual pane DLNA renderer</modelDescription>"
                    + "<modelName>DualPane</modelName>"
                    + "<modelNumber>1</modelNumber>"
                    + "<modelURL>" + base + "/</modelURL>"
                    + "<serialNumber>" + (leftSide ? "left" : "right") + "</serialNumber>"
                    + "<UDN>" + udn + "</UDN>"
                    + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"
                    + "<serviceList>"
                    + service(AVT, "AVTransport")
                    + service(RCS, "RenderingControl")
                    + service(CMS, "ConnectionManager")
                    + "</serviceList>"
                    + "<presentationURL>" + base + "/</presentationURL>"
                    + "</device></root>";
        }

        private String service(String type, String name) {
            return "<service><serviceType>" + type + "</serviceType>"
                    + "<serviceId>urn:upnp-org:serviceId:" + name + "</serviceId>"
                    + "<SCPDURL>/upnp/scpd/" + name + ".xml</SCPDURL>"
                    + "<controlURL>/upnp/control/" + name + "</controlURL>"
                    + "<eventSubURL>/upnp/event/" + name + "</eventSubURL>"
                    + "</service>";
        }
    }

    private static byte[] readBody(InputStream in, String header) throws Exception {
        String lower = header.toLowerCase(Locale.US);
        if (lower.contains("transfer-encoding:") && lower.contains("chunked")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (out.size() < MAX_BODY_BYTES) {
                String line = readLine(in);
                if (line == null) {
                    break;
                }
                int semi = line.indexOf(';');
                String sizeText = (semi >= 0 ? line.substring(0, semi) : line).trim();
                if (sizeText.isEmpty()) {
                    continue;
                }
                int size;
                try {
                    size = Integer.parseInt(sizeText, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (size == 0) {
                    break;
                }
                // A chunk header alone must never be able to allocate an unbounded array.
                if (size < 0 || size > MAX_CHUNK_BYTES || out.size() + (long) size > MAX_BODY_BYTES) {
                    throw new IOException("chunk too large");
                }
                byte[] chunk = new byte[size];
                readFully(in, chunk);
                out.write(chunk);
                readLine(in);
            }
            return out.toByteArray();
        }
        int length = contentLength(header);
        if (length <= 0) {
            return new byte[0];
        }
        length = Math.min(length, MAX_BODY_BYTES);
        byte[] body = new byte[length];
        readFully(in, body);
        return body;
    }

    private static void readFully(InputStream in, byte[] body) throws Exception {
        int read = 0;
        while (read < body.length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < 4096) {
            int b = in.read();
            if (b < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static int contentLength(String header) {
        String value = header(header, "CONTENT-LENGTH");
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String header(String message, String name) {
        String want = name.toLowerCase(Locale.US);
        for (String line : message.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.US).equals(want)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private static String actionName(String soapAction) {
        if (soapAction == null) {
            return "";
        }
        String cleaned = soapAction.replace("\"", "").trim();
        int hash = cleaned.lastIndexOf('#');
        if (hash >= 0 && hash + 1 < cleaned.length()) {
            return cleaned.substring(hash + 1).trim();
        }
        return "";
    }

    private static String actionFromBody(String body) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(body));
            boolean inBody = false;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String name = local(parser.getName());
                    if ("Body".equals(name)) {
                        inBody = true;
                    } else if (inBody) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static Map<String, String> soapArgs(String body) {
        Map<String, String> args = new LinkedHashMap<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(body));
            String current = null;
            int depth = 0;
            int argDepth = -1;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    String name = local(parser.getName());
                    if (depth == 4) {
                        current = name;
                        argDepth = depth;
                        args.putIfAbsent(name, "");
                    }
                } else if (event == XmlPullParser.TEXT && current != null && depth == argDepth) {
                    String text = parser.getText();
                    if (text != null) {
                        args.put(current, args.get(current) + text);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (depth == argDepth) {
                        current = null;
                        argDepth = -1;
                    }
                    depth--;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "soap parse " + e.getMessage());
        }
        for (Map.Entry<String, String> entry : args.entrySet()) {
            entry.setValue(entry.getValue().trim());
        }
        return args;
    }

    private static String local(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String serviceFor(String path, String soapAction) {
        String source = ((path == null ? "" : path) + " " + (soapAction == null ? "" : soapAction)).toLowerCase(Locale.US);
        if (source.contains("renderingcontrol")) {
            return RCS;
        }
        if (source.contains("connectionmanager")) {
            return CMS;
        }
        return AVT;
    }

    private static String pickUri(String current, String meta) {
        String uri = current == null ? "" : current.trim();
        String fromMeta = extractRes(meta);
        if (uri.isEmpty()) {
            return allowed(fromMeta) ? fromMeta : "";
        }
        if (!allowed(uri)) {
            return "";
        }
        String lower = uri.toLowerCase(Locale.US);
        boolean direct = lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4")
                || lower.contains(".flv") || lower.contains(".ts") || lower.contains(".mkv");
        if (!direct && fromMeta != null && allowed(fromMeta)) {
            String metaLower = fromMeta.toLowerCase(Locale.US);
            if (metaLower.contains(".m3u8") || metaLower.contains(".mpd") || metaLower.contains(".mp4")) {
                return fromMeta;
            }
        }
        return uri;
    }

    private static boolean allowed(String uri) {
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.US);
        // No media3-exoplayer-rtsp dependency: accepting rtsp:// would crash the player thread.
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String extractRes(String meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Matcher matcher = RES.matcher(meta);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim().replace("&amp;", "&");
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty() || "NOT_IMPLEMENTED".equals(value)) {
            return 0;
        }
        try {
            String[] parts = value.trim().split(":");
            if (parts.length == 3) {
                double seconds = Double.parseDouble(parts[2]);
                return (long) ((Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L) * 1000L
                        + seconds * 1000.0);
            }
            return (long) (Double.parseDouble(value) * 1000.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float parseVolume(String value) {
        try {
            return Math.max(0f, Math.min(1f, Integer.parseInt(value.trim()) / 100f));
        } catch (Exception e) {
            return 1f;
        }
    }

    private static String relTime(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
    }

    private static String safeUri(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }

    private static String xml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static byte[] envelope(String service, String action, String inner) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + "Response xmlns:u=\"" + service + "\">"
                + inner
                + "</u:" + action + "Response></s:Body></s:Envelope>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fault(String service, int code, String text) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"" + service + "\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + text + "</errorDescription></UPnPError></detail>"
                + "</s:Fault></s:Body></s:Envelope>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeBytes(Socket client, int status, String type, byte[] body) throws Exception {
        writeBytes(client, status, type, body, body.length);
    }

    private static void writeBytes(Socket client, int status, String type, byte[] body, int length) throws Exception {
        String reason = status == 200 ? "OK" : status == 500 ? "Internal Server Error" : "Not Found";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "SERVER: " + SERVER + "\r\n"
                + "DATE: " + httpDate() + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private static String httpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    private static String avTransportScpd() {
        return scpd(
                act("SetAVTransportURI",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in CurrentURI AVTransportURI",
                        "in CurrentURIMetaData AVTransportURIMetaData")
                        + act("SetNextAVTransportURI",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in NextURI NextAVTransportURI",
                        "in NextURIMetaData NextAVTransportURIMetaData")
                        + act("GetMediaInfo",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out NrTracks NumberOfTracks",
                        "out MediaDuration CurrentMediaDuration",
                        "out CurrentURI AVTransportURI",
                        "out CurrentURIMetaData AVTransportURIMetaData",
                        "out NextURI NextAVTransportURI",
                        "out NextURIMetaData NextAVTransportURIMetaData",
                        "out PlayMedium PlaybackStorageMedium",
                        "out RecordMedium RecordStorageMedium",
                        "out WriteStatus RecordMediumWriteStatus")
                        + act("GetTransportInfo",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out CurrentTransportState TransportState",
                        "out CurrentTransportStatus TransportStatus",
                        "out CurrentSpeed TransportPlaySpeed")
                        + act("GetPositionInfo",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out Track CurrentTrack",
                        "out TrackDuration CurrentTrackDuration",
                        "out TrackMetaData CurrentTrackMetaData",
                        "out TrackURI CurrentTrackURI",
                        "out RelTime RelativeTimePosition",
                        "out AbsTime AbsoluteTimePosition",
                        "out RelCount RelativeCounterPosition",
                        "out AbsCount AbsoluteCounterPosition")
                        + act("GetDeviceCapabilities",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out PlayMedia PossiblePlaybackStorageMedia",
                        "out RecMedia PossibleRecordStorageMedia",
                        "out RecQualityModes PossibleRecordQualityModes")
                        + act("GetTransportSettings",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out PlayMode CurrentPlayMode",
                        "out RecQualityMode CurrentRecordQualityMode")
                        + act("Stop", "in InstanceID A_ARG_TYPE_InstanceID")
                        + act("Play", "in InstanceID A_ARG_TYPE_InstanceID", "in Speed TransportPlaySpeed")
                        + act("Pause", "in InstanceID A_ARG_TYPE_InstanceID")
                        + act("Seek",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in Unit A_ARG_TYPE_SeekMode",
                        "in Target A_ARG_TYPE_SeekTarget")
                        + act("Next", "in InstanceID A_ARG_TYPE_InstanceID")
                        + act("Previous", "in InstanceID A_ARG_TYPE_InstanceID")
                        + act("GetCurrentTransportActions",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "out Actions CurrentTransportActions"),
                values("TransportState", "string", "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")
                        + values("TransportStatus", "string", "OK", "ERROR_OCCURRED")
                        + svar("CurrentTransportActions", "string")
                        + svar("PlaybackStorageMedium", "string")
                        + svar("RecordStorageMedium", "string")
                        + svar("PossiblePlaybackStorageMedia", "string")
                        + svar("PossibleRecordStorageMedia", "string")
                        + svar("PossibleRecordQualityModes", "string")
                        + svar("CurrentPlayMode", "string")
                        + svar("TransportPlaySpeed", "string")
                        + svar("NumberOfTracks", "ui4")
                        + svar("CurrentTrack", "ui4")
                        + svar("CurrentTrackDuration", "string")
                        + svar("CurrentMediaDuration", "string")
                        + svar("CurrentTrackURI", "string")
                        + svar("CurrentTrackMetaData", "string")
                        + svar("AVTransportURI", "string")
                        + svar("AVTransportURIMetaData", "string")
                        + svar("NextAVTransportURI", "string")
                        + svar("NextAVTransportURIMetaData", "string")
                        + svar("RelativeTimePosition", "string")
                        + svar("AbsoluteTimePosition", "string")
                        + svar("RelativeCounterPosition", "i4")
                        + svar("AbsoluteCounterPosition", "i4")
                        + svar("CurrentRecordQualityMode", "string")
                        + svar("RecordMediumWriteStatus", "string")
                        + values("A_ARG_TYPE_SeekMode", "string", "REL_TIME", "TRACK_NR")
                        + svar("A_ARG_TYPE_SeekTarget", "string")
                        + svar("A_ARG_TYPE_InstanceID", "ui4")
                        + svar("LastChange", "string"));
    }

    private static String renderingScpd() {
        return scpd(
                act("ListPresets", "in InstanceID A_ARG_TYPE_InstanceID", "out CurrentPresetNameList PresetNameList")
                        + act("SelectPreset", "in InstanceID A_ARG_TYPE_InstanceID", "in PresetName A_ARG_TYPE_PresetName")
                        + act("GetMute",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in Channel A_ARG_TYPE_Channel",
                        "out CurrentMute Mute")
                        + act("SetMute",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in Channel A_ARG_TYPE_Channel",
                        "in Mute Mute")
                        + act("GetVolume",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in Channel A_ARG_TYPE_Channel",
                        "out CurrentVolume Volume")
                        + act("SetVolume",
                        "in InstanceID A_ARG_TYPE_InstanceID",
                        "in Channel A_ARG_TYPE_Channel",
                        "in DesiredVolume Volume"),
                svar("Mute", "boolean")
                        + svar("Volume", "ui2")
                        + svar("PresetNameList", "string")
                        + values("A_ARG_TYPE_Channel", "string", "Master")
                        + svar("A_ARG_TYPE_InstanceID", "ui4")
                        + svar("A_ARG_TYPE_PresetName", "string")
                        + svar("LastChange", "string"));
    }

    private static String connectionScpd() {
        return scpd(
                act("GetProtocolInfo", "out Source SourceProtocolInfo", "out Sink SinkProtocolInfo")
                        + act("GetCurrentConnectionIDs", "out ConnectionIDs CurrentConnectionIDs")
                        + act("GetCurrentConnectionInfo",
                        "in ConnectionID A_ARG_TYPE_ConnectionID",
                        "out RcsID A_ARG_TYPE_RcsID",
                        "out AVTransportID A_ARG_TYPE_AVTransportID",
                        "out ProtocolInfo A_ARG_TYPE_ProtocolInfo",
                        "out PeerConnectionManager A_ARG_TYPE_ConnectionManager",
                        "out PeerConnectionID A_ARG_TYPE_ConnectionID",
                        "out Direction A_ARG_TYPE_Direction",
                        "out Status A_ARG_TYPE_ConnectionStatus"),
                svar("SourceProtocolInfo", "string")
                        + svar("SinkProtocolInfo", "string")
                        + svar("CurrentConnectionIDs", "string")
                        + svar("A_ARG_TYPE_ConnectionStatus", "string")
                        + svar("A_ARG_TYPE_ConnectionManager", "string")
                        + values("A_ARG_TYPE_Direction", "string", "Input", "Output")
                        + svar("A_ARG_TYPE_ProtocolInfo", "string")
                        + svar("A_ARG_TYPE_ConnectionID", "i4")
                        + svar("A_ARG_TYPE_RcsID", "i4")
                        + svar("A_ARG_TYPE_AVTransportID", "i4"));
    }

    private static String scpd(String actions, String states) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>" + actions + "</actionList>"
                + "<serviceStateTable>" + states + "</serviceStateTable></scpd>";
    }

    private static String act(String name, String... args) {
        StringBuilder xml = new StringBuilder();
        xml.append("<action><name>").append(name).append("</name><argumentList>");
        for (String arg : args) {
            String[] parts = arg.split(" ");
            xml.append("<argument><name>").append(parts[1]).append("</name><direction>")
                    .append(parts[0]).append("</direction><relatedStateVariable>")
                    .append(parts[2]).append("</relatedStateVariable></argument>");
        }
        xml.append("</argumentList></action>");
        return xml.toString();
    }

    private static String svar(String name, String type) {
        return "<stateVariable sendEvents=\"no\"><name>" + name + "</name><dataType>" + type + "</dataType></stateVariable>";
    }

    private static String values(String name, String type, String... allowed) {
        StringBuilder xml = new StringBuilder();
        xml.append("<stateVariable sendEvents=\"no\"><name>").append(name)
                .append("</name><dataType>").append(type).append("</dataType><allowedValueList>");
        for (String value : allowed) {
            xml.append("<allowedValue>").append(value).append("</allowedValue>");
        }
        xml.append("</allowedValueList></stateVariable>");
        return xml.toString();
    }
}
