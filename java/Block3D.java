/*
 * Block3D - a dependency-free 3D block viewer with its own control UI.
 *
 *   Build:  javac Block3D.java
 *   Run:    java Block3D
 *
 * Everything here is plain JDK: Swing for the interface, Java2D for the
 * rasteriser. There is no scene graph and no external library - the cube is
 * eight points, six quads, a rotation matrix and a painter's-algorithm sort.
 * Scene.render() draws into any Graphics2D, which is what lets Block3DServer
 * reuse the exact same renderer headlessly and stream frames to a browser.
 */

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;

public final class Block3D {

    /* ------------------------------------------------------------------ */
    /*  Interface palette - one set of tokens shared by Swing and the web  */
    /*  launcher, so the desktop window and the browser page look alike.   */
    /* ------------------------------------------------------------------ */

    static final Color INK       = new Color(0x0E1116);
    static final Color PANEL     = new Color(0x161B22);
    static final Color PANEL_HI  = new Color(0x1F2733);
    static final Color EDGE      = new Color(0x2B3441);
    static final Color TEXT      = new Color(0xCBD5E1);
    static final Color TEXT_DIM  = new Color(0x7B8A9E);
    static final Color ACCENT    = new Color(0xFFB02E);
    static final Color BLOCK     = new Color(0x4FA3FF);

    static final Font UI_FONT      = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    static final Font UI_BOLD      = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    static final Font EYEBROW_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    static final Font MONO_FONT    = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    static final Font TITLE_FONT   = new Font(Font.SANS_SERIF, Font.BOLD, 16);

    private Block3D() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Block3D::createAndShow);
    }

    static void createAndShow() {
        applyTheme();
        Scene scene = new Scene();
        Viewport viewport = new Viewport(scene);
        ControlPanel controls = new ControlPanel(scene, viewport);
        viewport.setControls(controls);

        JScrollPane rail = new JScrollPane(controls,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rail.setPreferredSize(new Dimension(302, 640));
        rail.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, EDGE));
        rail.getViewport().setBackground(PANEL);
        rail.getVerticalScrollBar().setUnitIncrement(16);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(INK);
        root.add(header(), BorderLayout.NORTH);
        root.add(viewport, BorderLayout.CENTER);
        root.add(rail, BorderLayout.EAST);
        root.add(viewport.status, BorderLayout.SOUTH);

        JFrame frame = new JFrame("Block3D - 3D block studio");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(menuBar(frame, scene, viewport, controls));
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(900, 620));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        viewport.requestFocusInWindow();
        viewport.start();
    }

    static void applyTheme() {
        UIManager.put("Panel.background", PANEL);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", UI_FONT);
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.font", UI_FONT);
        UIManager.put("RadioButton.background", PANEL);
        UIManager.put("RadioButton.foreground", TEXT);
        UIManager.put("RadioButton.font", UI_FONT);
        UIManager.put("Slider.background", PANEL);
        UIManager.put("Button.background", PANEL_HI);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.font", UI_BOLD);
        UIManager.put("MenuBar.background", PANEL);
        UIManager.put("Menu.background", PANEL);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.background", PANEL);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("ScrollPane.background", PANEL);
        UIManager.put("ScrollBar.background", PANEL);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", PANEL_HI);
        UIManager.put("ToolTip.foreground", TEXT);
    }

    /** Title strip: a flat gradient bar with the product name and a hint. */
    static JComponent header() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, PANEL_HI, getWidth(), 0, PANEL));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(ACCENT);
                g2.fillRect(0, 0, 3, getHeight());
                g2.setColor(EDGE);
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(10, 16, 10, 16));

        JLabel title = new JLabel("BLOCK3D");
        title.setFont(TITLE_FONT);
        title.setForeground(TEXT);
        JLabel hint = new JLabel("drag to tumble  ·  wheel to zoom  ·  right-drag to pan  ·  space to spin");
        hint.setFont(UI_FONT);
        hint.setForeground(TEXT_DIM);
        hint.setHorizontalAlignment(SwingConstants.RIGHT);

        bar.add(title, BorderLayout.WEST);
        bar.add(hint, BorderLayout.EAST);
        return bar;
    }

    static JMenuBar menuBar(JFrame frame, Scene scene, Viewport viewport, ControlPanel controls) {
        JMenuBar bar = new JMenuBar();
        bar.setBackground(PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, EDGE));

        JMenu file = new JMenu("File");
        JMenuItem save = new JMenuItem("Save image…");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        save.addActionListener(e -> savePng(frame, scene, viewport.getWidth(), viewport.getHeight()));
        JMenuItem quit = new JMenuItem("Close window");
        quit.addActionListener(e -> frame.dispose());
        file.add(save);
        file.addSeparator();
        file.add(quit);

        JMenu view = new JMenu("View");
        JMenuItem reset = new JMenuItem("Reset view");
        reset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0));
        reset.addActionListener(e -> {
            scene.reset();
            controls.syncFromScene();
            viewport.requestRender();
        });
        JMenuItem spin = new JMenuItem("Toggle auto-spin");
        spin.addActionListener(e -> {
            scene.spin = !scene.spin;
            controls.syncFromScene();
            viewport.requestRender();
        });
        view.add(reset);
        view.add(spin);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About Block3D");
        about.addActionListener(e -> JOptionPane.showMessageDialog(frame,
                "Block3D\n\nA software-rendered 3D block with a Swing control panel.\n"
                        + "Six quads, flat shading, painter's-algorithm depth sort.\n"
                        + "No external libraries - plain JDK only.\n\n"
                        + "Run Block3DServer to drive the same renderer from a browser.",
                "About Block3D", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);

        bar.add(file);
        bar.add(view);
        bar.add(help);
        return bar;
    }

    static void savePng(Component parent, Scene scene, int w, int h) {
        int width = Math.max(320, w);
        int height = Math.max(240, h);
        BufferedImage image = scene.renderToImage(width * 2, height * 2, 2.0);
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("block3d.png"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        try {
            ImageIO.write(image, "png", chooser.getSelectedFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent, "Could not save the image: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /* ================================================================== */
    /*  Scene - the renderer. Holds every parameter the UI can change and  */
    /*  knows how to draw itself into any Graphics2D.                      */
    /* ================================================================== */

    static final class Scene {

        /** Unit cube, corners at ±1. Scaled per-axis by the size fields. */
        private static final double[][] CORNERS = {
            {-1, -1, -1}, { 1, -1, -1}, { 1,  1, -1}, {-1,  1, -1},
            {-1, -1,  1}, { 1, -1,  1}, { 1,  1,  1}, {-1,  1,  1}
        };

        /** Six quads, wound counter-clockwise seen from outside the block. */
        private static final int[][] QUADS = {
            {4, 5, 6, 7}, {1, 0, 3, 2}, {5, 1, 2, 6},
            {0, 4, 7, 3}, {7, 6, 2, 3}, {0, 1, 5, 4}
        };

        /** Per-face tint used when lighting is switched off. */
        private static final double[] FLAT_TINT = {1.00, 0.58, 0.82, 0.68, 1.18, 0.46};

        /** Face identity, in QUADS order, for picking and the inspector. */
        static final String[] FACE_NAMES = {"Front", "Back", "Right", "Left", "Top", "Bottom"};
        static final Color[] FACE_COLORS = {
            new Color(0x4C8DF6), new Color(0x2F6BD4), new Color(0x00A6A6),
            new Color(0x7A5CF0), new Color(0x59C36A), new Color(0xE0A32E)
        };
        private static final double[][] FACE_NORMALS = {
            {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}
        };
        /** The block is modelled at 20 mm per world unit, so it has real sizes. */
        static final double MM_PER_UNIT = 20.0;
        static final String BLOCK_ID = "block-01";
        static final String MATERIAL = "Anodised aluminium";

        private static final int[][] WIRES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6},
            {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        /** Key light, pointing from the block toward the lamp. */
        private static final double[] LIGHT = normalise(-0.42, 0.80, 0.52);
        /** Direction sunlight travels, used to cast the floor shadow. */
        private static final double[] SUN = normalise(0.34, -1.0, -0.22);

        // Orientation, in degrees.
        double rotX = 22, rotY = 34, rotZ = 0;
        // Block dimensions in world units.
        double sizeX = 2.0, sizeY = 2.0, sizeZ = 2.0;
        // Camera.
        double distance = 7.0, fov = 1.55, panX = 0, panY = 0;
        // Appearance.
        Color blockColor = BLOCK;
        boolean faces = true, wireframe = true, lighting = true, culling = true;
        boolean grid = true, axes = true, shadow = true, corners = false;
        boolean darkBackground = true;
        // Animation.
        boolean spin = true;
        double spinSpeed = 24;
        /** Six named colours instead of one shaded hue. */
        boolean perFaceColors = false;
        /** Index into QUADS, or -1. Set by clicking a face. */
        int selected = -1;
        int hovered = -1;

        /** Faces that survived culling on the last frame - shown in the status bar. */
        int drawnFaces = 0;

        void reset() {
            rotX = 22; rotY = 34; rotZ = 0;
            sizeX = 2.0; sizeY = 2.0; sizeZ = 2.0;
            distance = 7.0; fov = 1.55; panX = 0; panY = 0;
            blockColor = BLOCK;
            faces = true; wireframe = true; lighting = true; culling = true;
            grid = true; axes = true; shadow = true; corners = false;
            spin = true; spinSpeed = 24;
            perFaceColors = false;
            selected = -1; hovered = -1;
        }

        void tumble(double dx, double dy) {
            rotY = wrap(rotY + dx);
            rotX = wrap(rotX + dy);
        }

        void advance(double seconds) {
            rotY = wrap(rotY + spinSpeed * seconds);
        }

        static double wrap(double degrees) {
            double d = degrees % 360;
            if (d > 180) d -= 360;
            if (d < -180) d += 360;
            return d;
        }

        /* ----------------------------- maths ----------------------------- */

        private static double[] normalise(double x, double y, double z) {
            double length = Math.sqrt(x * x + y * y + z * z);
            return new double[] {x / length, y / length, z / length};
        }

        /** Row-major 3x3 for Rx · Ry · Rz. */
        private double[] orientation() {
            double a = Math.toRadians(rotX), b = Math.toRadians(rotY), c = Math.toRadians(rotZ);
            double sa = Math.sin(a), ca = Math.cos(a);
            double sb = Math.sin(b), cb = Math.cos(b);
            double sc = Math.sin(c), cc = Math.cos(c);
            return new double[] {
                cb * cc,                  -cb * sc,                  sb,
                sa * sb * cc + ca * sc,   -sa * sb * sc + ca * cc,  -sa * cb,
                -ca * sb * cc + sa * sc,   ca * sb * sc + sa * cc,   ca * cb
            };
        }

        private static double[] transform(double[] m, double x, double y, double z) {
            return new double[] {
                m[0] * x + m[1] * y + m[2] * z,
                m[3] * x + m[4] * y + m[5] * z,
                m[6] * x + m[7] * y + m[8] * z
            };
        }

        /** Perspective divide. Returns null for anything at or behind the lens. */
        private double[] project(double[] p, double focal, double cx, double cy) {
            double depth = distance - p[2];
            if (depth < 0.2) return null;
            double s = focal / depth;
            return new double[] {cx + p[0] * s, cy - p[1] * s};
        }

        /** The block rests on the grid, so its shadow is a contact shadow. */
        private double floorY() {
            return -sizeY / 2;
        }

        /* ---------------------------- rendering --------------------------- */

        BufferedImage renderToImage(int width, int height, double scale) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.scale(scale, scale);
            render(g, (int) Math.round(width / scale), (int) Math.round(height / scale));
            g.dispose();
            return image;
        }

        void render(Graphics2D g, int width, int height) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // The floor end of the gradient is the lighter one: a studio backdrop,
            // and the only thing a cast shadow can actually darken.
            Color top = darkBackground ? new Color(0x0A0E14) : new Color(0xF2F5F9);
            Color bottom = darkBackground ? new Color(0x1B2431) : new Color(0xC3CEDC);
            g.setPaint(new GradientPaint(0, 0, top, 0, height, bottom));
            g.fillRect(0, 0, width, height);

            double focal = Math.min(width, height) * fov;
            double cx = width / 2.0 + panX;
            double cy = height / 2.0 + panY;
            double[] m = orientation();
            double ground = floorY();

            if (grid) drawGrid(g, m, focal, cx, cy, ground);
            if (shadow) drawShadow(g, m, focal, cx, cy, ground);
            // Axes go down before the block so solid faces occlude the stubs
            // that run through the middle of it.
            if (axes) drawAxes(g, m, focal, cx, cy);
            drawBlock(g, m, focal, cx, cy);
        }

        private void drawGrid(Graphics2D g, double[] m, double focal, double cx, double cy, double ground) {
            int half = 6;
            g.setStroke(new BasicStroke(1f));
            for (int i = -half; i <= half; i++) {
                int alpha = i == 0 ? 90 : 38 - Math.abs(i) * 4;
                Color line = darkBackground
                        ? new Color(0x7B, 0x8A, 0x9E, Math.max(10, alpha))
                        : new Color(0x35, 0x43, 0x55, Math.max(12, alpha));
                g.setColor(line);
                worldLine(g, m, focal, cx, cy, i, ground, -half, i, ground, half);
                worldLine(g, m, focal, cx, cy, -half, ground, i, half, ground, i);
            }
        }

        private void worldLine(Graphics2D g, double[] m, double focal, double cx, double cy,
                               double x1, double y1, double z1, double x2, double y2, double z2) {
            double[] a = project(transform(m, x1, y1, z1), focal, cx, cy);
            double[] b = project(transform(m, x2, y2, z2), focal, cx, cy);
            if (a == null || b == null) return;
            g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
        }

        /** Drops the eight corners onto the floor plane along SUN, then hulls them. */
        private void drawShadow(Graphics2D g, double[] m, double focal, double cx, double cy, double ground) {
            List<double[]> points = new ArrayList<>();
            for (double[] corner : CORNERS) {
                double[] p = transform(m, corner[0] * sizeX / 2, corner[1] * sizeY / 2, corner[2] * sizeZ / 2);
                double t = (ground - p[1]) / SUN[1];
                if (t <= 0) continue;
                double[] screen = project(new double[] {p[0] + SUN[0] * t, ground, p[2] + SUN[2] * t}, focal, cx, cy);
                if (screen != null) points.add(screen);
            }
            List<double[]> hull = convexHull(points);
            if (hull.size() < 3) return;

            double mx = 0, my = 0;
            for (double[] p : hull) { mx += p[0]; my += p[1]; }
            mx /= hull.size();
            my /= hull.size();

            // Two passes: a soft halo, then the core, which reads as a penumbra.
            g.setColor(new Color(0, 0, 0, darkBackground ? 52 : 34));
            g.fill(hullPath(hull, mx, my, 1.16));
            g.setColor(new Color(0, 0, 0, darkBackground ? 112 : 70));
            g.fill(hullPath(hull, mx, my, 1.0));
        }

        private static Path2D.Double hullPath(List<double[]> hull, double mx, double my, double scale) {
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < hull.size(); i++) {
                double x = mx + (hull.get(i)[0] - mx) * scale;
                double y = my + (hull.get(i)[1] - my) * scale;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.closePath();
            return path;
        }

        /** Model-space corner i, scaled to the block's dimensions. */
        double[] corner(int i) {
            return new double[] {
                CORNERS[i][0] * sizeX / 2, CORNERS[i][1] * sizeY / 2, CORNERS[i][2] * sizeZ / 2
            };
        }

        private void drawBlock(Graphics2D g, double[] m, double focal, double cx, double cy) {
            double[][] world = new double[8][];
            double[][] screen = new double[8][];
            for (int i = 0; i < 8; i++) {
                double[] c = corner(i);
                world[i] = transform(m, c[0], c[1], c[2]);
                screen[i] = project(world[i], focal, cx, cy);
            }

            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < QUADS.length; i++) order.add(i);
            // Painter's algorithm: smaller z sits further from a camera on +z.
            order.sort(Comparator.comparingDouble(i -> centroid(world, QUADS[i])[2]));

            Color edgeColor = darkBackground
                    ? blend(blockColor, Color.WHITE, 0.55)
                    : blend(blockColor, Color.BLACK, 0.45);
            drawnFaces = 0;

            for (int index : order) {
                int[] quad = QUADS[index];
                double[] normal = faceNormal(world, quad);
                double[] middle = centroid(world, quad);
                double[] toCamera = {-middle[0], -middle[1], distance - middle[2]};
                boolean towardsCamera = dot(normal, toCamera) > 0;
                // A wireframe is meant to be see-through: culling only applies
                // while there are solid faces to hide things behind.
                if (culling && faces && !towardsCamera) continue;
                if (screen[quad[0]] == null || screen[quad[1]] == null
                        || screen[quad[2]] == null || screen[quad[3]] == null) continue;

                Path2D.Double path = new Path2D.Double();
                for (int i = 0; i < 4; i++) {
                    double[] p = screen[quad[i]];
                    if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
                }
                path.closePath();
                drawnFaces++;

                if (faces) {
                    g.setColor(shade(index, normal, toCamera, towardsCamera));
                    g.fill(path);
                }
                if (wireframe || !faces) {
                    g.setStroke(new BasicStroke(faces ? 1.2f : 1.6f));
                    g.setColor(perFaceColors && faces ? blend(base(index), Color.WHITE, 0.5) : edgeColor);
                    g.draw(path);
                }
                if (index == selected || index == hovered) {
                    boolean pinned = index == selected;
                    g.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), pinned ? 34 : 20));
                    g.fill(path);
                    g.setStroke(new BasicStroke(pinned ? 2.4f : 1.4f));
                    g.setColor(ACCENT);
                    g.draw(path);
                }
            }

            // With culling off and faces on, the back edges are buried; draw the
            // full cage so the shape still reads.
            if (!culling && faces && wireframe) {  // faces on, culling off: back edges are buried
                g.setStroke(new BasicStroke(1f));
                g.setColor(new Color(edgeColor.getRed(), edgeColor.getGreen(), edgeColor.getBlue(), 90));
                for (int[] wire : WIRES) {
                    if (screen[wire[0]] == null || screen[wire[1]] == null) continue;
                    g.draw(new Line2D.Double(screen[wire[0]][0], screen[wire[0]][1],
                            screen[wire[1]][0], screen[wire[1]][1]));
                }
            }

            if (corners) {
                g.setColor(ACCENT);
                for (double[] p : screen) {
                    if (p == null) continue;
                    g.fill(new Ellipse2D.Double(p[0] - 3, p[1] - 3, 6, 6));
                }
            }
        }

        /** The face's unlit colour: one hue for the block, or its own. */
        Color base(int faceIndex) {
            return perFaceColors ? FACE_COLORS[faceIndex] : blockColor;
        }

        private Color shade(int faceIndex, double[] normal, double[] toCamera, boolean towardsCamera) {
            if (!lighting) {
                return perFaceColors ? base(faceIndex) : scale(blockColor, FLAT_TINT[faceIndex]);
            }
            double[] n = towardsCamera ? normal : new double[] {-normal[0], -normal[1], -normal[2]};
            double lambert = Math.max(0, dot(unit(n), LIGHT));
            double k = 0.30 + 0.70 * lambert;
            Color base = scale(base(faceIndex), k);
            // A narrow highlight where the half-vector lines up with the face.
            double[] view = unit(toCamera);
            double[] half = unit(new double[] {LIGHT[0] + view[0], LIGHT[1] + view[1], LIGHT[2] + view[2]});
            double spec = Math.pow(Math.max(0, dot(unit(n), half)), 28) * 0.45;
            return blend(base, Color.WHITE, spec);
        }

        private void drawAxes(Graphics2D g, double[] m, double focal, double cx, double cy) {
            double reach = Math.max(sizeX, Math.max(sizeY, sizeZ)) / 2 + 1.0;
            double[] origin = project(transform(m, 0, 0, 0), focal, cx, cy);
            if (origin == null) return;
            Color[] colors = {new Color(0xFF5C6E), new Color(0x63D68F), new Color(0x5AA9FF)};
            String[] names = {"X", "Y", "Z"};
            double[][] ends = {{reach, 0, 0}, {0, reach, 0}, {0, 0, reach}};
            g.setStroke(new BasicStroke(1.6f));
            g.setFont(EYEBROW_FONT);
            for (int i = 0; i < 3; i++) {
                double[] end = project(transform(m, ends[i][0], ends[i][1], ends[i][2]), focal, cx, cy);
                if (end == null) continue;
                g.setColor(colors[i]);
                g.draw(new Line2D.Double(origin[0], origin[1], end[0], end[1]));
                g.drawString(names[i], (float) end[0] + 4, (float) end[1] - 2);
            }
        }

        /**
         * The visible face under a point, or -1. Only front-facing quads can be
         * hit - you can click what you can see - and the nearest one wins.
         */
        int faceAt(double px, double py, int width, int height) {
            double focal = Math.min(width, height) * fov;
            double cx = width / 2.0 + panX;
            double cy = height / 2.0 + panY;
            double[] m = orientation();
            double[][] world = new double[8][];
            double[][] screen = new double[8][];
            for (int i = 0; i < 8; i++) {
                double[] c = corner(i);
                world[i] = transform(m, c[0], c[1], c[2]);
                screen[i] = project(world[i], focal, cx, cy);
            }

            int best = -1;
            double nearest = -Double.MAX_VALUE;
            for (int index = 0; index < QUADS.length; index++) {
                int[] quad = QUADS[index];
                double[] middle = centroid(world, quad);
                double[] toCamera = {-middle[0], -middle[1], distance - middle[2]};
                if (dot(faceNormal(world, quad), toCamera) <= 0) continue;

                Path2D.Double path = new Path2D.Double();
                boolean clipped = false;
                for (int i = 0; i < 4; i++) {
                    double[] p = screen[quad[i]];
                    if (p == null) { clipped = true; break; }
                    if (i == 0) path.moveTo(p[0], p[1]); else path.lineTo(p[0], p[1]);
                }
                if (clipped) continue;
                path.closePath();
                if (path.contains(px, py) && middle[2] > nearest) {
                    nearest = middle[2];
                    best = index;
                }
            }
            return best;
        }

        /** Width and height of a face, in world units. */
        double[] faceExtent(int index) {
            switch (index) {
                case 0: case 1: return new double[] {sizeX, sizeY};
                case 2: case 3: return new double[] {sizeZ, sizeY};
                default: return new double[] {sizeX, sizeZ};
            }
        }

        static String hex(Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }

        /** What the inspector shows for a face - also the body of /pick. */
        String faceReport(int index) {
            if (index < 0 || index >= QUADS.length) {
                return "No face selected.\n\nClick a side of the block to read its\n"
                        + "normal, colour, size and corner coordinates.";
            }
            double[] extent = faceExtent(index);
            double[] n = FACE_NORMALS[index];
            StringBuilder text = new StringBuilder();
            text.append(FACE_NAMES[index]).append(" face\n\n");
            text.append(String.format("normal    (%.0f, %.0f, %.0f)%n", n[0], n[1], n[2]));
            text.append(String.format("colour    %s%n", hex(base(index))));
            text.append(String.format("size      %.1f × %.1f mm%n",
                    extent[0] * MM_PER_UNIT, extent[1] * MM_PER_UNIT));
            text.append(String.format("area      %.0f mm²%n%n",
                    extent[0] * extent[1] * MM_PER_UNIT * MM_PER_UNIT));
            text.append("corners (mm)\n");
            for (int i : QUADS[index]) {
                double[] c = corner(i);
                text.append(String.format("  %+6.1f %+6.1f %+6.1f%n",
                        c[0] * MM_PER_UNIT, c[1] * MM_PER_UNIT, c[2] * MM_PER_UNIT));
            }
            text.append(String.format("%nblock     %s%n", BLOCK_ID));
            text.append(String.format("material  %s%n", MATERIAL));
            text.append(String.format("size      %.0f × %.0f × %.0f mm",
                    sizeX * MM_PER_UNIT, sizeY * MM_PER_UNIT, sizeZ * MM_PER_UNIT));
            return text.toString();
        }

        /* --------------------------- small helpers -------------------------- */

        private static double[] centroid(double[][] world, int[] quad) {
            double x = 0, y = 0, z = 0;
            for (int i : quad) { x += world[i][0]; y += world[i][1]; z += world[i][2]; }
            return new double[] {x / quad.length, y / quad.length, z / quad.length};
        }

        private static double[] faceNormal(double[][] world, int[] quad) {
            double[] a = world[quad[0]], b = world[quad[1]], c = world[quad[2]];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
        }

        private static double dot(double[] a, double[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }

        private static double[] unit(double[] v) {
            double length = Math.sqrt(dot(v, v));
            if (length == 0) return new double[] {0, 0, 1};
            return new double[] {v[0] / length, v[1] / length, v[2] / length};
        }

        private static List<double[]> convexHull(List<double[]> input) {
            if (input.size() < 3) return input;
            List<double[]> points = new ArrayList<>(input);
            points.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
            List<double[]> hull = new ArrayList<>();
            for (int pass = 0; pass < 2; pass++) {
                int start = hull.size();
                for (double[] p : points) {
                    while (hull.size() >= start + 2
                            && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                        hull.remove(hull.size() - 1);
                    }
                    hull.add(p);
                }
                hull.remove(hull.size() - 1);
                java.util.Collections.reverse(points);
            }
            return hull;
        }

        private static double cross(double[] o, double[] a, double[] b) {
            return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
        }
    }

    static Color scale(Color base, double k) {
        return new Color(clamp(base.getRed() * k), clamp(base.getGreen() * k), clamp(base.getBlue() * k));
    }

    static Color blend(Color a, Color b, double t) {
        double u = Math.max(0, Math.min(1, t));
        return new Color(
                clamp(a.getRed() + (b.getRed() - a.getRed()) * u),
                clamp(a.getGreen() + (b.getGreen() - a.getGreen()) * u),
                clamp(a.getBlue() + (b.getBlue() - a.getBlue()) * u));
    }

    static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    /* ================================================================== */
    /*  Viewport - the drawing surface plus mouse, wheel and key handling  */
    /* ================================================================== */

    static final class Viewport extends JPanel {

        final Scene scene;
        final JLabel status = new JLabel(" ");
        private ControlPanel controls;
        private final Timer clock;
        private long lastTick = System.nanoTime();
        private long lastStatus = 0;
        private boolean dirty = true;
        private int frames = 0;
        private double fps = 0;
        private java.awt.Point drag;
        private boolean panning;
        private boolean moved;

        Viewport(Scene scene) {
            this.scene = scene;
            setPreferredSize(new Dimension(760, 620));
            setBackground(INK);
            setFocusable(true);

            status.setFont(MONO_FONT);
            status.setForeground(TEXT_DIM);
            status.setOpaque(true);
            status.setBackground(PANEL);
            status.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, EDGE),
                    new EmptyBorder(5, 14, 5, 14)));

            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    drag = e.getPoint();
                    moved = false;
                    panning = SwingUtilities.isRightMouseButton(e) || e.isShiftDown();
                    requestFocusInWindow();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    drag = null;
                    // A press that never moved is a click on a face, not a tumble.
                    if (!moved && !panning) select(scene.faceAt(e.getX(), e.getY(), getWidth(), getHeight()));
                }
                @Override public void mouseMoved(MouseEvent e) {
                    int face = scene.faceAt(e.getX(), e.getY(), getWidth(), getHeight());
                    if (face == scene.hovered) return;
                    scene.hovered = face;
                    setCursor(java.awt.Cursor.getPredefinedCursor(
                            face < 0 ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.HAND_CURSOR));
                    requestRender();
                }
                @Override public void mouseExited(MouseEvent e) {
                    if (scene.hovered < 0) return;
                    scene.hovered = -1;
                    requestRender();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (drag == null) return;
                    int dx = e.getX() - drag.x;
                    int dy = e.getY() - drag.y;
                    drag = e.getPoint();
                    if (dx != 0 || dy != 0) moved = true;
                    if (panning) {
                        scene.panX += dx;
                        scene.panY += dy;
                    } else {
                        scene.tumble(dx * 0.45, dy * 0.45);
                    }
                    syncControls();
                    requestRender();
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom(e.getPreciseWheelRotation() * 0.4);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            installKeys();

            clock = new Timer(16, e -> tick());
        }

        void setControls(ControlPanel controls) {
            this.controls = controls;
        }

        void start() {
            clock.start();
        }

        void requestRender() {
            dirty = true;
        }

        void zoom(double delta) {
            scene.distance = Math.max(3.0, Math.min(24.0, scene.distance + delta));
            syncControls();
            requestRender();
        }

        private void syncControls() {
            if (controls != null) controls.syncFromScene();
        }

        /** Pins a face in the inspector; clicking empty space clears it. */
        void select(int face) {
            scene.selected = face;
            if (controls != null) controls.showInspector();
            requestRender();
        }

        private void installKeys() {
            bind("LEFT", () -> scene.rotY = Scene.wrap(scene.rotY - 5));
            bind("RIGHT", () -> scene.rotY = Scene.wrap(scene.rotY + 5));
            bind("UP", () -> scene.rotX = Scene.wrap(scene.rotX - 5));
            bind("DOWN", () -> scene.rotX = Scene.wrap(scene.rotX + 5));
            bind("EQUALS", () -> zoom(-0.5));
            bind("MINUS", () -> zoom(0.5));
            bind("SPACE", () -> scene.spin = !scene.spin);
            bind("R", scene::reset);
        }

        private void bind(String key, Runnable action) {
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
            getActionMap().put(key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    action.run();
                    syncControls();
                    requestRender();
                }
            });
        }

        private void tick() {
            long now = System.nanoTime();
            double seconds = (now - lastTick) / 1_000_000_000.0;
            lastTick = now;
            if (scene.spin) {
                scene.advance(seconds);
                syncControls();
                dirty = true;
            }
            if (dirty) {
                dirty = false;
                repaint();
            }
            if (now - lastStatus > 250_000_000L) {
                double elapsed = (now - lastStatus) / 1_000_000_000.0;
                if (lastStatus != 0) fps = frames / elapsed;
                frames = 0;
                lastStatus = now;
                updateStatus();
            }
        }

        private void updateStatus() {
            status.setText(String.format(
                    "rot %+.0f° %+.0f° %+.0f°     size %.2f × %.2f × %.2f     camera %.1f     faces drawn %d/6     %s",
                    scene.rotX, scene.rotY, scene.rotZ,
                    scene.sizeX, scene.sizeY, scene.sizeZ,
                    scene.distance, scene.drawnFaces,
                    scene.spin ? String.format("%.0f fps", fps) : "paused"));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            frames++;
            scene.render((Graphics2D) g.create(), getWidth(), getHeight());
        }
    }

    /* ================================================================== */
    /*  ControlPanel - the block's own UI set                             */
    /* ================================================================== */

    static final class ControlPanel extends JPanel {

        private final Scene scene;
        private final Viewport viewport;
        private boolean syncing;

        private final LabeledSlider rotX, rotY, rotZ, speed;
        private final LabeledSlider sizeX, sizeY, sizeZ;
        private final LabeledSlider distance, fov;
        private final JCheckBox spin, lighting, culling, corners, grid, axes, shadow, light, palette;
        private final JTextArea inspector = new JTextArea();
        private final JRadioButton solid, wire, both;
        private final Swatch swatch = new Swatch(BLOCK);

        ControlPanel(Scene scene, Viewport viewport) {
            this.scene = scene;
            this.viewport = viewport;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(PANEL);
            setBorder(new EmptyBorder(14, 16, 18, 16));

            add(section("Orientation"));
            rotX = slider("Pitch (X)", -180, 180, (int) scene.rotX, 1, "°", v -> scene.rotX = v);
            rotY = slider("Yaw (Y)", -180, 180, (int) scene.rotY, 1, "°", v -> scene.rotY = v);
            rotZ = slider("Roll (Z)", -180, 180, (int) scene.rotZ, 1, "°", v -> scene.rotZ = v);
            spin = check("Auto-spin", scene.spin, v -> scene.spin = v);
            speed = slider("Spin speed", 2, 120, (int) scene.spinSpeed, 1, "°/s", v -> scene.spinSpeed = v);

            add(section("Dimensions"));
            sizeX = slider("Width", 20, 600, (int) (scene.sizeX * 100), 0.01, "", v -> scene.sizeX = v / 100.0);
            sizeY = slider("Height", 20, 600, (int) (scene.sizeY * 100), 0.01, "", v -> scene.sizeY = v / 100.0);
            sizeZ = slider("Depth", 20, 600, (int) (scene.sizeZ * 100), 0.01, "", v -> scene.sizeZ = v / 100.0);

            add(section("Camera"));
            distance = slider("Distance", 30, 240, (int) (scene.distance * 10), 0.1, "", v -> scene.distance = v / 10.0);
            fov = slider("Field of view", 80, 280, (int) (scene.fov * 100), 0.01, "", v -> scene.fov = v / 100.0);

            add(section("Surface"));
            solid = new JRadioButton("Solid");
            wire = new JRadioButton("Wireframe");
            both = new JRadioButton("Solid + edges", true);
            ButtonGroup modes = new ButtonGroup();
            for (JRadioButton button : new JRadioButton[] {solid, wire, both}) {
                modes.add(button);
                button.setBackground(PANEL);
                button.setForeground(TEXT);
                button.setFocusPainted(false);
                button.addActionListener(e -> {
                    if (syncing) return;
                    scene.faces = !wire.isSelected();
                    scene.wireframe = !solid.isSelected();
                    viewport.requestRender();
                });
                add(stretch(button));
            }
            lighting = check("Directional light", scene.lighting, v -> scene.lighting = v);
            culling = check("Hide back faces", scene.culling, v -> scene.culling = v);
            corners = check("Mark corners", scene.corners, v -> scene.corners = v);
            palette = check("Colour each face", scene.perFaceColors, v -> {
                scene.perFaceColors = v;
                showInspector();
            });

            JButton colour = new JButton("Block colour", swatch);
            colour.setFocusPainted(false);
            colour.setHorizontalAlignment(SwingConstants.LEFT);
            colour.setIconTextGap(10);
            colour.addActionListener(e -> {
                Color picked = JColorChooser.showDialog(this, "Block colour", scene.blockColor);
                if (picked == null) return;
                scene.blockColor = picked;
                swatch.color = picked;
                colour.repaint();
                viewport.requestRender();
            });
            add(Box.createVerticalStrut(8));
            add(stretch(colour));

            add(section("Stage"));
            grid = check("Floor grid", scene.grid, v -> scene.grid = v);
            shadow = check("Cast shadow", scene.shadow, v -> scene.shadow = v);
            axes = check("Axis gizmo", scene.axes, v -> scene.axes = v);
            light = check("Light background", !scene.darkBackground, v -> scene.darkBackground = !v);

            add(section("Inspector"));
            inspector.setEditable(false);
            inspector.setFocusable(false);
            // Without this, replacing the text drags the whole rail to the caret.
            ((DefaultCaret) inspector.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            inspector.setFont(MONO_FONT);
            inspector.setForeground(TEXT);
            inspector.setBackground(INK);
            inspector.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(EDGE), new EmptyBorder(9, 10, 9, 10)));
            add(stretch(inspector));
            showInspector();

            add(section("Session"));
            JButton reset = new JButton("Reset view");
            reset.setFocusPainted(false);
            reset.addActionListener(e -> {
                scene.reset();
                swatch.color = scene.blockColor;
                syncFromScene();
                showInspector();
                viewport.requestRender();
            });
            JButton save = new JButton("Save image…");
            save.setFocusPainted(false);
            save.addActionListener(e ->
                    savePng(this, scene, viewport.getWidth(), viewport.getHeight()));
            add(Box.createVerticalStrut(6));
            add(stretch(reset));
            add(Box.createVerticalStrut(6));
            add(stretch(save));
            add(Box.createVerticalGlue());
        }

        /** Redraws the face read-out; the rail re-lays-out because text grows. */
        void showInspector() {
            inspector.setText(scene.faceReport(scene.selected));
            inspector.setMaximumSize(new Dimension(Integer.MAX_VALUE, inspector.getPreferredSize().height));
            revalidate();
        }

        /** Pushes scene state back into the widgets after a drag, key or reset. */
        void syncFromScene() {
            syncing = true;
            rotX.set((int) Math.round(scene.rotX));
            rotY.set((int) Math.round(scene.rotY));
            rotZ.set((int) Math.round(scene.rotZ));
            speed.set((int) Math.round(scene.spinSpeed));
            sizeX.set((int) Math.round(scene.sizeX * 100));
            sizeY.set((int) Math.round(scene.sizeY * 100));
            sizeZ.set((int) Math.round(scene.sizeZ * 100));
            distance.set((int) Math.round(scene.distance * 10));
            fov.set((int) Math.round(scene.fov * 100));
            spin.setSelected(scene.spin);
            lighting.setSelected(scene.lighting);
            culling.setSelected(scene.culling);
            corners.setSelected(scene.corners);
            grid.setSelected(scene.grid);
            axes.setSelected(scene.axes);
            shadow.setSelected(scene.shadow);
            light.setSelected(!scene.darkBackground);
            palette.setSelected(scene.perFaceColors);
            if (scene.faces && scene.wireframe) both.setSelected(true);
            else if (scene.faces) solid.setSelected(true);
            else wire.setSelected(true);
            syncing = false;
        }

        private LabeledSlider slider(String name, int min, int max, int value,
                                     double scale, String suffix, IntConsumer apply) {
            LabeledSlider control = new LabeledSlider(name, min, max, value, scale, suffix, v -> {
                if (syncing) return;
                apply.accept(v);
                viewport.requestRender();
            });
            add(stretch(control));
            return control;
        }

        private JCheckBox check(String text, boolean selected, Consumer<Boolean> apply) {
            JCheckBox box = new JCheckBox(text, selected);
            box.setBackground(PANEL);
            box.setForeground(TEXT);
            box.setFocusPainted(false);
            box.addActionListener(e -> {
                if (syncing) return;
                apply.accept(box.isSelected());
                viewport.requestRender();
            });
            add(stretch(box));
            return box;
        }

        private JComponent section(String title) {
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(16, 0, 6, 0));
            JLabel label = new JLabel(title.toUpperCase());
            label.setFont(EYEBROW_FONT);
            label.setForeground(ACCENT);
            row.add(label, BorderLayout.WEST);
            row.add(new Rule(), BorderLayout.CENTER);
            add(stretch(row));
            return row;
        }

        private static JComponent stretch(JComponent component) {
            component.setAlignmentX(LEFT_ALIGNMENT);
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
            return component;
        }
    }

    /** Label, live value readout and slider as one stacked unit. */
    static final class LabeledSlider extends JPanel {

        final JSlider slider;
        private final JLabel readout = new JLabel();
        private final double scale;
        private final String suffix;

        LabeledSlider(String name, int min, int max, int value,
                      double scale, String suffix, IntConsumer onChange) {
            super(new BorderLayout(0, 2));
            this.scale = scale;
            this.suffix = suffix;
            setOpaque(false);
            setBorder(new EmptyBorder(5, 0, 7, 0));

            JLabel label = new JLabel(name);
            label.setFont(UI_FONT);
            label.setForeground(TEXT_DIM);
            readout.setFont(MONO_FONT);
            readout.setForeground(ACCENT);
            readout.setHorizontalAlignment(SwingConstants.RIGHT);

            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(label, BorderLayout.WEST);
            top.add(readout, BorderLayout.EAST);

            slider = new JSlider(min, max, Math.max(min, Math.min(max, value)));
            slider.setOpaque(false);
            slider.setForeground(ACCENT);
            slider.setFocusable(false);
            slider.addChangeListener(e -> {
                refresh();
                onChange.accept(slider.getValue());
            });

            add(top, BorderLayout.NORTH);
            add(slider, BorderLayout.CENTER);
            refresh();
        }

        void set(int value) {
            slider.setValue(Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), value)));
        }

        private void refresh() {
            double shown = slider.getValue() * scale;
            readout.setText(scale == 1
                    ? slider.getValue() + suffix
                    : String.format("%.2f%s", shown, suffix));
        }
    }

    /** Hairline that fills the space beside a section title. */
    static final class Rule extends JComponent {
        Rule() {
            setPreferredSize(new Dimension(10, 1));
        }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(EDGE);
            g.fillRect(0, getHeight() / 2, getWidth(), 1);
        }
    }

    /** Colour chip used on the block-colour button. */
    static final class Swatch implements javax.swing.Icon {
        Color color;
        Swatch(Color color) {
            this.color = color;
        }
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, 14, 14, 4, 4);
            g2.setColor(EDGE);
            g2.drawRoundRect(x, y, 13, 13, 4, 4);
            g2.dispose();
        }
    }
}
