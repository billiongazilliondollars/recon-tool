import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A small 3D block you can spin around and click on.
 *
 * Everything is drawn with plain Java2D: the cube's corners are rotated in 3D,
 * projected to 2D with a perspective divide, back-facing sides are culled, and
 * the rest are painted back-to-front. Clicking hit-tests the projected polygons
 * and opens the details of whichever face you hit in the inspector.
 *
 *   javac Block3D.java && java Block3D
 *
 * Controls: drag to rotate, scroll to zoom, click a face to inspect it.
 */
public class Block3D {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Block3D::buildUi);
    }

    private static void buildUi() {
        JFrame frame = new JFrame("3D Block Inspector");
        Inspector inspector = new Inspector();
        ScenePanel scene = new ScenePanel(inspector);

        JCheckBox spin = new JCheckBox("Auto-spin", true);
        spin.addActionListener(e -> scene.setSpinning(spin.isSelected()));

        JButton reset = new JButton("Reset view");
        reset.addActionListener(e -> scene.resetView());

        JPanel controls = new JPanel(new GridLayout(1, 2, 8, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        controls.add(spin);
        controls.add(reset);

        JPanel side = new JPanel(new BorderLayout());
        side.add(inspector, BorderLayout.CENTER);
        side.add(controls, BorderLayout.SOUTH);

        frame.setLayout(new BorderLayout());
        frame.add(scene, BorderLayout.CENTER);
        frame.add(side, BorderLayout.EAST);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        scene.requestFocusInWindow();
    }

    /** One corner of the block, in model space. */
    private record Vec3(double x, double y, double z) {
        Vec3 rotateY(double a) {
            double c = Math.cos(a), s = Math.sin(a);
            return new Vec3(x * c + z * s, y, -x * s + z * c);
        }

        Vec3 rotateX(double a) {
            double c = Math.cos(a), s = Math.sin(a);
            return new Vec3(x, y * c - z * s, y * s + z * c);
        }

        double dot(Vec3 o) {
            return x * o.x + y * o.y + z * o.z;
        }
    }

    /** One side of the block: four corner indices plus an outward normal. */
    private record Face(String name, int[] corners, Vec3 normal, Color color) {}

    /** The block itself -- geometry and the metadata the inspector reports. */
    private static final class Block {
        final String id = "block-01";
        final String material = "Anodised aluminium";
        double sizeMm = 40.0;

        final Vec3[] corners = {
            new Vec3(-1, -1, -1), new Vec3(1, -1, -1), new Vec3(1, 1, -1), new Vec3(-1, 1, -1),
            new Vec3(-1, -1, 1), new Vec3(1, -1, 1), new Vec3(1, 1, 1), new Vec3(-1, 1, 1),
        };

        final Face[] faces = {
            new Face("Front",  new int[] {0, 1, 2, 3}, new Vec3(0, 0, -1), new Color(0x4C8DF6)),
            new Face("Back",   new int[] {5, 4, 7, 6}, new Vec3(0, 0, 1),  new Color(0x2F6BD4)),
            new Face("Left",   new int[] {4, 0, 3, 7}, new Vec3(-1, 0, 0), new Color(0x7A5CF0)),
            new Face("Right",  new int[] {1, 5, 6, 2}, new Vec3(1, 0, 0),  new Color(0x00A6A6)),
            new Face("Top",    new int[] {3, 2, 6, 7}, new Vec3(0, 1, 0),  new Color(0x59C36A)),
            new Face("Bottom", new int[] {4, 5, 1, 0}, new Vec3(0, -1, 0), new Color(0xE0A32E)),
        };
    }

    /** A face after rotation + projection: what actually gets drawn and clicked. */
    private record ProjectedFace(Face face, Polygon shape, double depth, Vec3 normal) {}

    private static final class ScenePanel extends JPanel {
        private static final Vec3 LIGHT = normalize(new Vec3(-0.4, 0.8, -0.5));
        private static final double CAMERA_DISTANCE = 6.0;

        private final Block block = new Block();
        private final Inspector inspector;
        private final Timer timer;

        private double yaw = 0.6, pitch = -0.35;
        private double scale = 130;
        private boolean spinning = true;
        private int clicks = 0;

        private Face selected;
        private Face hovered;
        private List<ProjectedFace> lastFrame = List.of();
        private int dragX, dragY;

        ScenePanel(Inspector inspector) {
            this.inspector = inspector;
            setPreferredSize(new Dimension(560, 480));
            setBackground(new Color(0x14161C));

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragX = e.getX();
                    dragY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    yaw += (e.getX() - dragX) * 0.01;
                    pitch += (e.getY() - dragY) * 0.01;
                    pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch));
                    dragX = e.getX();
                    dragY = e.getY();
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    Face hit = faceAt(e.getX(), e.getY());
                    selected = hit;
                    if (hit != null) {
                        clicks++;
                    }
                    refreshInspector();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    Face hit = faceAt(e.getX(), e.getY());
                    if (hit != hovered) {
                        hovered = hit;
                        setCursor(java.awt.Cursor.getPredefinedCursor(
                                hit == null ? java.awt.Cursor.DEFAULT_CURSOR : java.awt.Cursor.HAND_CURSOR));
                        repaint();
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    scale = Math.max(50, Math.min(320, scale - e.getWheelRotation() * 12));
                    refreshInspector();
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);

            timer = new Timer(16, e -> {
                if (spinning) {
                    yaw += 0.006;
                    repaint();
                }
            });
            timer.start();
            refreshInspector();
        }

        void setSpinning(boolean on) {
            spinning = on;
            requestFocusInWindow();
        }

        void resetView() {
            yaw = 0.6;
            pitch = -0.35;
            scale = 130;
            selected = null;
            refreshInspector();
            repaint();
        }

        /** Rotate, project, cull, and sort every face for the current view. */
        private List<ProjectedFace> project() {
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            Vec3[] rotated = new Vec3[block.corners.length];
            double[] sx = new double[rotated.length];
            double[] sy = new double[rotated.length];

            for (int i = 0; i < rotated.length; i++) {
                Vec3 v = block.corners[i].rotateY(yaw).rotateX(pitch);
                rotated[i] = v;
                double perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE - v.z());
                sx[i] = cx + v.x() * scale * perspective;
                sy[i] = cy - v.y() * scale * perspective;
            }

            List<ProjectedFace> visible = new ArrayList<>();
            for (Face face : block.faces) {
                Vec3 normal = face.normal().rotateY(yaw).rotateX(pitch);
                double depth = 0;
                Polygon poly = new Polygon();
                Vec3 center = new Vec3(0, 0, 0);
                for (int idx : face.corners()) {
                    poly.addPoint((int) Math.round(sx[idx]), (int) Math.round(sy[idx]));
                    depth += rotated[idx].z();
                    center = new Vec3(center.x() + rotated[idx].x() / 4,
                                      center.y() + rotated[idx].y() / 4,
                                      center.z() + rotated[idx].z() / 4);
                }
                // Camera sits at -Z; a face points away from us when its normal
                // agrees with the direction from the camera to the face.
                Vec3 toFace = new Vec3(center.x(), center.y(), center.z() + CAMERA_DISTANCE);
                if (normal.dot(toFace) >= 0) {
                    continue;
                }
                visible.add(new ProjectedFace(face, poly, depth / face.corners().length, normal));
            }
            visible.sort(Comparator.comparingDouble(ProjectedFace::depth).reversed());
            return visible;
        }

        private Face faceAt(int x, int y) {
            // lastFrame is far-to-near, so walk it backwards to hit the nearest face first.
            for (int i = lastFrame.size() - 1; i >= 0; i--) {
                ProjectedFace pf = lastFrame.get(i);
                if (pf.shape().contains(x, y)) {
                    return pf.face();
                }
            }
            return null;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            lastFrame = project();
            for (ProjectedFace pf : lastFrame) {
                g2.setColor(shade(pf.face().color(), pf.normal()));
                g2.fill(pf.shape());

                boolean isSelected = pf.face() == selected;
                boolean isHovered = pf.face() == hovered;
                g2.setStroke(new BasicStroke(isSelected ? 3f : 1.4f));
                g2.setColor(isSelected ? Color.WHITE : isHovered ? new Color(0xC8D2E0) : new Color(0x10121A));
                g2.draw(pf.shape());
            }

            g2.setColor(new Color(0x8A93A6));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g2.drawString("Drag to rotate  ·  scroll to zoom  ·  click a face to inspect", 14, getHeight() - 14);
            g2.dispose();
        }

        /** Lambert shading: faces angled away from the light get darker. */
        private static Color shade(Color base, Vec3 normal) {
            double lambert = Math.max(0, normalize(normal).dot(LIGHT));
            double k = 0.45 + 0.55 * lambert;
            return new Color(clamp(base.getRed() * k), clamp(base.getGreen() * k), clamp(base.getBlue() * k));
        }

        private static int clamp(double v) {
            return (int) Math.max(0, Math.min(255, v));
        }

        private void refreshInspector() {
            StringBuilder sb = new StringBuilder();
            sb.append("BLOCK\n");
            sb.append(row("id", block.id));
            sb.append(row("material", block.material));
            sb.append(row("size", String.format("%.1f x %.1f x %.1f mm", block.sizeMm, block.sizeMm, block.sizeMm)));
            sb.append(row("volume", String.format("%.0f mm^3", Math.pow(block.sizeMm, 3))));
            sb.append(row("faces", block.faces.length + " (" + lastFrame.size() + " visible)"));
            sb.append("\nVIEW\n");
            sb.append(row("yaw", String.format("%.1f deg", Math.toDegrees(normalizeAngle(yaw)))));
            sb.append(row("pitch", String.format("%.1f deg", Math.toDegrees(pitch))));
            sb.append(row("zoom", String.format("%.0f px/unit", scale)));
            sb.append(row("inspections", String.valueOf(clicks)));

            sb.append("\nSELECTED FACE\n");
            if (selected == null) {
                sb.append("  (click a face on the block)\n");
            } else {
                sb.append(row("name", selected.name()));
                sb.append(row("normal", String.format("(%.0f, %.0f, %.0f)",
                        selected.normal().x(), selected.normal().y(), selected.normal().z())));
                sb.append(row("colour", String.format("#%06X", selected.color().getRGB() & 0xFFFFFF)));
                sb.append(row("area", String.format("%.0f mm^2", block.sizeMm * block.sizeMm)));
                sb.append("  corners\n");
                for (int idx : selected.corners()) {
                    Vec3 c = block.corners[idx];
                    sb.append(String.format("    v%d  (%+.0f, %+.0f, %+.0f)%n", idx, c.x(), c.y(), c.z()));
                }
            }
            inspector.setText(sb.toString());
        }

        private static String row(String key, String value) {
            return String.format("  %-12s %s%n", key, value);
        }

        private static double normalizeAngle(double a) {
            double t = a % (2 * Math.PI);
            return t > Math.PI ? t - 2 * Math.PI : t < -Math.PI ? t + 2 * Math.PI : t;
        }

        private static Vec3 normalize(Vec3 v) {
            double len = Math.sqrt(v.dot(v));
            return len == 0 ? v : new Vec3(v.x() / len, v.y() / len, v.z() / len);
        }
    }

    /** Right-hand panel that prints whatever the scene last reported. */
    private static final class Inspector extends JPanel {
        private final JTextArea text = new JTextArea();

        Inspector() {
            super(new BorderLayout());
            setPreferredSize(new Dimension(300, 480));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));

            JLabel title = new JLabel("Inspector");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
            title.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

            text.setEditable(false);
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            text.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            add(title, BorderLayout.NORTH);
            add(new JScrollPane(text), BorderLayout.CENTER);
            add(Box.createVerticalStrut(4), BorderLayout.SOUTH);
        }

        void setText(String s) {
            text.setText(s);
            text.setCaretPosition(0);
        }
    }
}
