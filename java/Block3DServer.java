/*
 * Block3DServer - opens Block3D from a browser tab.
 *
 *   Build:  javac Block3D.java Block3DServer.java
 *   Run:    java Block3DServer          (add a port number to override 8080)
 *
 * The page it serves is not a re-implementation of the block in JavaScript.
 * Every frame you see in the browser is drawn by the same Block3D.Scene
 * renderer, encoded on the server and streamed back as an image - the browser
 * only collects input and shows the result. The "Open desktop window" button
 * hands the identical Scene to Swing on the machine running this server.
 *
 * Bound to 127.0.0.1 on purpose: this process can open windows on the host,
 * so it stays off the network.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class Block3DServer {

    private static final int DEFAULT_PORT = 8080;

    private Block3DServer() { }

    public static void main(String[] args) throws IOException {
        int requested = args.length > 0 ? parsePort(args[0]) : DEFAULT_PORT;
        HttpServer server = bind(requested);
        int port = server.getAddress().getPort();

        server.createContext("/", Block3DServer::serveStatic);
        server.createContext("/frame", Block3DServer::serveFrame);
        server.createContext("/pick", Block3DServer::servePick);
        server.createContext("/launch", Block3DServer::serveLaunch);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        String url = "http://localhost:" + port + "/";
        System.out.println();
        System.out.println("  Block3D is running.");
        System.out.println("  Open  ->  " + url);
        System.out.println("  Stop  ->  Ctrl+C");
        System.out.println();
        openBrowser(url);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // fall through to the default
        }
        System.out.println("Ignoring '" + value + "' - not a port number. Using " + DEFAULT_PORT + ".");
        return DEFAULT_PORT;
    }

    /** Takes the requested port, or the next free one, so a stale copy is not fatal. */
    private static HttpServer bind(int requested) throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        IOException last = null;
        for (int port = requested; port < requested + 20; port++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
                if (port != requested) {
                    System.out.println("Port " + requested + " was busy, using " + port + " instead.");
                }
                return server;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("No free port in " + requested + ".." + (requested + 19), last);
    }

    private static void openBrowser(String url) {
        try {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return;
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI.create(url));
        } catch (Exception e) {
            System.out.println("Could not open a browser automatically - visit " + url + " yourself.");
        }
    }

    /* ---------------------------- static files ---------------------------- */

    /** web/ next to the working directory, or next to the compiled classes. */
    private static Path webRoot() {
        Path[] candidates = {
            Paths.get("web"),
            Paths.get("java", "web"),
            classesDirectory() == null ? Paths.get("web") : classesDirectory().resolve("web")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("index.html"))) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    private static Path classesDirectory() {
        try {
            Path location = Paths.get(Block3DServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path root = webRoot();
            if (root == null) {
                send(exchange, 500, "text/plain; charset=utf-8",
                        ("Could not find web/index.html.\n"
                                + "Start the server from the folder that contains it:\n"
                                + "  cd java && java Block3DServer\n").getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(file), Files.readAllBytes(file));
        } finally {
            exchange.close();
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    /* ------------------------------- frames ------------------------------- */

    private static void serveFrame(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            Block3D.Scene scene = sceneFrom(query);

            int width = (int) clamp(number(query, "w", 900), 160, 2400);
            int height = (int) clamp(number(query, "h", 650), 120, 2000);
            double scale = clamp(number(query, "scale", 1), 1, 2);
            boolean jpeg = "jpg".equals(query.get("fmt"));

            BufferedImage image = scene.renderToImage(
                    (int) Math.round(width * scale), (int) Math.round(height * scale), scale);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
            ImageIO.write(image, jpeg ? "jpg" : "png", buffer);

            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("X-Faces-Drawn", String.valueOf(scene.drawnFaces));
            send(exchange, 200, jpeg ? "image/jpeg" : "image/png", buffer.toByteArray());
        } catch (RuntimeException e) {
            send(exchange, 400, "text/plain; charset=utf-8",
                    ("Bad frame request: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    /** Builds a Scene from query parameters; anything missing keeps its default. */
    private static Block3D.Scene sceneFrom(Map<String, String> query) {
        Block3D.Scene scene = new Block3D.Scene();
        scene.rotX = number(query, "rx", scene.rotX);
        scene.rotY = number(query, "ry", scene.rotY);
        scene.rotZ = number(query, "rz", scene.rotZ);
        scene.sizeX = clamp(number(query, "sx", scene.sizeX), 0.2, 6);
        scene.sizeY = clamp(number(query, "sy", scene.sizeY), 0.2, 6);
        scene.sizeZ = clamp(number(query, "sz", scene.sizeZ), 0.2, 6);
        scene.distance = clamp(number(query, "dist", scene.distance), 3, 24);
        scene.fov = clamp(number(query, "fov", scene.fov), 0.8, 2.8);
        scene.panX = clamp(number(query, "px", 0), -2000, 2000);
        scene.panY = clamp(number(query, "py", 0), -2000, 2000);
        scene.blockColor = colour(query.get("color"), scene.blockColor);
        scene.faces = flag(query, "faces", scene.faces);
        scene.wireframe = flag(query, "wire", scene.wireframe);
        scene.lighting = flag(query, "light", scene.lighting);
        scene.culling = flag(query, "cull", scene.culling);
        scene.grid = flag(query, "grid", scene.grid);
        scene.axes = flag(query, "axes", scene.axes);
        scene.shadow = flag(query, "shadow", scene.shadow);
        scene.corners = flag(query, "corners", scene.corners);
        scene.perFaceColors = flag(query, "palette", scene.perFaceColors);
        scene.selected = (int) clamp(number(query, "sel", -1), -1, 5);
        scene.darkBackground = flag(query, "dark", scene.darkBackground);
        return scene;
    }

    private static Color colour(String value, Color fallback) {
        if (value == null) return fallback;
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) return fallback;
        try {
            return new Color(Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double number(Map<String, String> query, String key, double fallback) {
        String value = query.get(key);
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flag(Map<String, String> query, String key, boolean fallback) {
        String value = query.get(key);
        if (value == null) return fallback;
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            String key = URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    /**
     * Hit-tests a click against the block. The browser sends the point it was
     * clicked at along with the same scene parameters it asked the frame for,
     * so the face it gets back is the face the viewer actually saw.
     */
    private static void servePick(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            Block3D.Scene scene = sceneFrom(query);
            int width = (int) clamp(number(query, "w", 900), 160, 2400);
            int height = (int) clamp(number(query, "h", 650), 120, 2000);
            int face = scene.faceAt(number(query, "x", -1), number(query, "y", -1), width, height);
            scene.selected = face;

            String name = face < 0 ? "" : Block3D.Scene.FACE_NAMES[face];
            String json = "{\"face\":" + face
                    + ",\"name\":\"" + name + "\""
                    + ",\"report\":\"" + escape(scene.faceReport(face)) + "\"}";
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            send(exchange, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            send(exchange, 400, "text/plain; charset=utf-8",
                    ("Bad pick request: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    /* ----------------------------- desktop app ---------------------------- */

    private static void serveLaunch(HttpExchange exchange) throws IOException {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                sendJson(exchange, 409, false,
                        "This machine has no display, so the Swing window cannot open. "
                                + "The browser viewer above is running the same renderer.");
                return;
            }
            SwingUtilities.invokeLater(Block3D::createAndShow);
            System.out.println("Opened the Block3D desktop window.");
            sendJson(exchange, 200, true, "Block3D opened on the desktop.");
        } catch (Exception e) {
            sendJson(exchange, 500, false, "Could not open the window: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, boolean ok, String message) throws IOException {
        String json = "{\"ok\":" + ok + ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
