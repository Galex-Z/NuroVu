import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// ── JNA (Java Native Access) ─────────────────────────────────────────────────
// JNA lets Java call native OS functions in DLLs without writing any C/JNI
// glue code.  We declare a Java interface whose method signatures mirror the
// Win32 API; JNA's dynamic proxy implements that interface by loading the DLL
// and forwarding each call.
//
// Required JAR on the classpath: jna-5.x.x.jar  (and jna-platform-5.x.x.jar)
// Maven coords:
//   <dependency>
//     <groupId>net.java.dev.jna</groupId>
//     <artifactId>jna-platform</artifactId>
//     <version>5.14.0</version>
//   </dependency>
//
// At runtime on non-Windows systems every JNA call is silently skipped via the
// WindowManager.isAvailable() guard, so the app still runs on macOS / Linux —
// nodes just won't focus real windows.
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.BOOL;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║     3D Force-Directed Workspace Graph — Manual Linking Edition       ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * CHANGES IN THIS VERSION
 * ───────────────────────
 * 1. CLEAN START
 *    The app opens with 3 pre-seeded, fully disconnected floating nodes.
 *    No edges are created at startup.
 *
 * 2. DISCONNECTED SPAWN
 *    "Spawn Window Node" adds a new sphere with zero edges — it just
 *    floats in space, repelled by the others via physics.
 *
 * 3. LINK MODE  (the big addition)
 *    A "🔗 Link Mode" toggle button switches the interaction layer:
 *
 *    NAVIGATE mode (default, grey button):
 *      • Mouse drag  → rotates the camera as before.
 *      • Clicking anywhere NOT on a sphere does nothing.
 *
 *    LINK mode (active, amber button):
 *      • Mouse drag is DISABLED so you don't accidentally spin while
 *        trying to click a node.
 *      • Click sphere #1  → it glows (selection highlight).
 *      • Click sphere #2  → an edge is drawn between them.
 *        - If the edge already exists, it is REMOVED instead (toggle).
 *        - After linking/unlinking the selection resets to "none".
 *      • Clicking the same sphere twice deselects it.
 *      • Clicking empty space deselects.
 *      • A status label below the button shows what to do next.
 *
 * HOW SPHERE CLICK DETECTION WORKS IN JavaFX 3D
 * ──────────────────────────────────────────────
 * JavaFX's pick system fires MouseEvent on 3D shapes when the ray from
 * the cursor intersects the shape's geometry (it uses the actual mesh,
 * not a bounding box).  We attach setOnMouseClicked() to each Sphere.
 * The handler receives the event and calls handleSphereClick(node).
 *
 * IMPORTANT: the Sphere's click handler calls event.consume() so the
 * event does NOT bubble up to the viewport's drag handler.  This stops
 * a click-on-sphere from starting a camera rotation.
 *
 * COMPILE & RUN  (unchanged from previous version)
 * ──────────────────────────────────────────────────
 * Maven:  mvn javafx:run
 *
 * Direct (Linux/Mac):
 *   export FX=/path/to/javafx-sdk/lib
 *   javac --module-path $FX --add-modules javafx.controls WorkspaceGraph3D.java
 *   java  --module-path $FX --add-modules javafx.controls WorkspaceGraph3D
 *
 * Direct (Windows):
 *   set FX=C:\javafx-sdk\lib
 *   javac --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D.java
 *   java  --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D
 */
public class WorkspaceGraph3D extends Application {

    // ══════════════════════════════════════════════════════════════════
    //  CONSTANTS
    // ══════════════════════════════════════════════════════════════════

    private static final double WORLD_RADIUS    = 200.0;
    private static final double ATTRACTION      = 0.0015;
    private static final double REPULSION       = 12_000.0;
    private static final double DAMPING         = 0.85;
    private static final double MAX_VELOCITY    = 4.0;
    private static final double SPRING_LENGTH   = 120.0;
    private static final double CAMERA_DISTANCE  = 550.0;
    private static final double ZOOM_SPEED       = 20.0;   // world-units per scroll tick
    private static final double ZOOM_MIN         = 100.0;  // closest allowed Z distance
    private static final double ZOOM_MAX         = 1800.0; // furthest allowed Z distance
    private static final double ROTATION_SPEED   = 0.4;
    private static final double PAN_SPEED        = 0.9;    // world-units per drag pixel
    private static final double NODE_RADIUS     = 18.0;
    private static final int    SCENE_W         = 1100;
    private static final int    SCENE_H         = 700;

    // Visual states for the selection highlight ring
    /** Normal emissive: none */
    private static final Color EMIT_NONE     = Color.BLACK;
    /** First-click selected: bright gold pulse */
    private static final Color EMIT_SELECTED = Color.GOLD;

    // ══════════════════════════════════════════════════════════════════
    //  SEED DATA  (start disconnected — no edges)
    // ══════════════════════════════════════════════════════════════════

    /** Pool for dynamically spawned nodes */
    private static final String[] SPAWN_COLORS = {
        "#E01E5A", "#1DB954", "#FF6B6B", "#C77DFF",
        "#48CAE4", "#F4A261", "#E9C46A", "#A8DADC"
    };
    private static final String[] SPAWN_NAMES = {
        "Spotify", "Slack", "Figma", "Discord", "Notion",
        "PyCharm", "Obsidian", "Postman", "Steam", "Finder", "Zoom"
    };

    // ══════════════════════════════════════════════════════════════════
    //  WINDOWS INTEGRATION — JNA
    //
    //  Architecture overview
    //  ─────────────────────
    //  WindowManager is a static helper class that lives inside
    //  WorkspaceGraph3D.  It:
    //    1. Declares a JNA interface (User32Extended) mapping the three
    //       Win32 functions we need.
    //    2. Loads user32.dll once via JNA's Native.load().
    //    3. Exposes isAvailable() so all call sites can skip gracefully
    //       on non-Windows or when JNA JARs are absent.
    //    4. Provides findWindow(titleSubstring) which calls EnumWindows
    //       to enumerate every open top-level window and returns the
    //       HWND of the first one whose title contains the substring
    //       (case-insensitive).
    //    5. Provides focusWindow(hwnd) which calls ShowWindow(SW_RESTORE)
    //       then SetForegroundWindow to bring an app to the front.
    //
    //  WHY EnumWindows instead of FindWindow?
    //    FindWindow requires an exact class name or exact title.  We want
    //    partial / case-insensitive matching (e.g. "chrome" should match
    //    "New Tab - Google Chrome").  EnumWindows lets us iterate all
    //    windows and apply our own matching logic.
    //
    //  HWND lifetime
    //    An HWND is a lightweight opaque integer handle issued by Windows.
    //    Storing it in GraphNode.hwnd is safe as long as the target window
    //    remains open.  If the window is closed, SetForegroundWindow will
    //    simply fail silently (returns FALSE) — no crash.
    // ══════════════════════════════════════════════════════════════════

    private static class WindowManager {

        // ── JNA interface declaration ─────────────────────────────────
        // Each method signature must exactly match the Win32 prototype.
        // W32APIOptions.DEFAULT_OPTIONS tells JNA to use the Unicode
        // (W-suffix) variants and handle String ↔ LPWSTR conversion.
        interface User32Extended extends StdCallLibrary {
            User32Extended INSTANCE = isWindows()
                ? Native.load("user32", User32Extended.class,
                              W32APIOptions.DEFAULT_OPTIONS)
                : null;

            // BOOL EnumWindows(WNDENUMPROC lpEnumFunc, LPARAM lParam)
            // Iterates all top-level windows, calling the callback for each.
            boolean EnumWindows(EnumWindowsCallback lpEnumFunc, Pointer lParam);

            // int GetWindowTextW(HWND hWnd, LPTSTR lpString, int nMaxCount)
            // Fills lpString with the window's title bar text.
            int GetWindowTextW(HWND hWnd, char[] lpString, int nMaxCount);

            // BOOL IsWindowVisible(HWND hWnd)
            boolean IsWindowVisible(HWND hWnd);

            // BOOL SetForegroundWindow(HWND hWnd)
            boolean SetForegroundWindow(HWND hWnd);

            // BOOL ShowWindow(HWND hWnd, int nCmdShow)
            boolean ShowWindow(HWND hWnd, int nCmdShow);
        }

        // Functional interface for the EnumWindows callback.
        // StdCallLibrary.StdCallCallback ensures the correct calling
        // convention (stdcall) so the stack is cleaned up correctly.
        interface EnumWindowsCallback extends StdCallLibrary.StdCallCallback {
            // Return true to continue enumeration, false to stop.
            boolean callback(HWND hwnd, Pointer lParam);
        }

        // ShowWindow constants
        static final int SW_RESTORE = 9;

        // ── Availability guard ────────────────────────────────────────
        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }

        static boolean isAvailable() {
            if (!isWindows()) return false;
            try {
                return User32Extended.INSTANCE != null;
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
                return false;
            }
        }

        /**
         * A matched window: its OS handle and the full title bar text.
         * We keep the full title so the picker dialog can show it to the user.
         */
        record WindowEntry(HWND hwnd, String title) {}

        /**
         * Returns ALL visible top-level windows with a non-empty title,
         * in EnumWindows order (roughly foreground-first / Z-order).
         * No filtering — the picker dialog handles search interactively.
         */
        static List<WindowEntry> listAllWindows() {
            if (!isAvailable()) return List.of();

            List<WindowEntry> results = new ArrayList<>();
            char[] buf = new char[512];

            User32Extended.INSTANCE.EnumWindows((hwnd, lParam) -> {
                if (!User32Extended.INSTANCE.IsWindowVisible(hwnd)) return true;
                int len = User32Extended.INSTANCE.GetWindowTextW(hwnd, buf, buf.length);
                if (len > 0) {
                    results.add(new WindowEntry(hwnd, new String(buf, 0, len)));
                }
                return true;
            }, null);

            return results;
        }

        /**
         * Enumerates ALL visible top-level windows whose title contains
         * {@code titleSubstring} (case-insensitive).
         * Kept for potential future use; spawnNewNode no longer calls it.
         */
        static List<WindowEntry> findAllWindows(String titleSubstring) {
            if (!isAvailable()) return List.of();
            String lower = titleSubstring.toLowerCase();
            List<WindowEntry> all = listAllWindows();
            List<WindowEntry> filtered = new ArrayList<>();
            for (WindowEntry e : all)
                if (e.title().toLowerCase().contains(lower)) filtered.add(e);
            return filtered;
        }

        /**
         * Brings the window identified by {@code hwnd} to the foreground.
         * ShowWindow(SW_RESTORE) un-minimises first; SetForegroundWindow raises it.
         */
        static void focusWindow(HWND hwnd) {
            if (!isAvailable() || hwnd == null) return;
            User32Extended.INSTANCE.ShowWindow(hwnd, SW_RESTORE);
            User32Extended.INSTANCE.SetForegroundWindow(hwnd);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphNode
    // ══════════════════════════════════════════════════════════════════

    private static class GraphNode {
        // Physics
        double x, y, z, vx, vy, vz, fx, fy, fz;

        // Scene graph
        Sphere sphere;
        Group  billboard;
        Text   label;

        final Rotate billboardRotY = new Rotate(0, Rotate.Y_AXIS);
        final Rotate billboardRotX = new Rotate(0, Rotate.X_AXIS);

        PhongMaterial material;

        String name;
        Color  baseColor;

        // ── OS window binding ─────────────────────────────────────────
        // hwnd is null for "shortcut" nodes (no matching live window found).
        // When non-null, double-clicking the sphere calls focusWindow(hwnd).
        HWND hwnd = null;
        boolean isBound() { return hwnd != null; }

        GraphNode(String name, Color baseColor, double x, double y, double z) {
            this.name = name; this.baseColor = baseColor;
            this.x = x; this.y = y; this.z = z;

            // ── Sphere ──────────────────────────────────────────────
            sphere = new Sphere(NODE_RADIUS);
            material = new PhongMaterial();
            material.setDiffuseColor(baseColor);
            material.setSpecularColor(Color.WHITE);
            material.setSpecularPower(32);
            material.setSpecularPower(32);
            sphere.setMaterial(material);

            // ── Billboard label ──────────────────────────────────────
            label = new Text(name);
            label.setFont(Font.font("Monospace", FontWeight.BOLD, 13));
            label.setFill(Color.WHITE);
            label.setStroke(Color.color(0, 0, 0, 0.55));
            label.setStrokeWidth(0.6);
            label.setTranslateX(NODE_RADIUS + 4);
            label.setTranslateY(-5);

            billboard = new Group(label);
            billboard.getTransforms().addAll(billboardRotY, billboardRotX);
        }

        void applyPosition() {
            sphere.setTranslateX(x);    sphere.setTranslateY(y);    sphere.setTranslateZ(z);
            billboard.setTranslateX(x); billboard.setTranslateY(y); billboard.setTranslateZ(z);
        }

        void updateBillboard(double ay, double ax) {
            billboardRotY.setAngle(-ay);
            billboardRotX.setAngle(-ax);
        }

        /**
         * Four visual states via PhongMaterial:
         *
         *  NORMAL        — base colour, tight specular
         *  LINK_SOURCE   — brightened toward white  (gold shimmer, first link click)
         *  DELETE_SELECT — brightened toward red    (queued for deletion)
         *  BOUND         — green tint + ring scale  (wired to a live OS window)
         */
        enum VisualState { NORMAL, LINK_SOURCE, DELETE_SELECT, BOUND }

        void applyVisualState(VisualState state) {
            switch (state) {
                case NORMAL -> {
                    material.setDiffuseColor(baseColor);
                    material.setSpecularColor(Color.WHITE);
                    material.setSpecularPower(32);
                    sphere.setScaleX(1.0); sphere.setScaleY(1.0); sphere.setScaleZ(1.0);
                }
                case LINK_SOURCE -> {
                    material.setDiffuseColor(baseColor.interpolate(Color.WHITE, 0.55));
                    material.setSpecularColor(Color.LIGHTYELLOW);
                    material.setSpecularPower(6);
                    sphere.setScaleX(1.0); sphere.setScaleY(1.0); sphere.setScaleZ(1.0);
                }
                case DELETE_SELECT -> {
                    material.setDiffuseColor(baseColor.interpolate(Color.RED, 0.55));
                    material.setSpecularColor(Color.ORANGERED);
                    material.setSpecularPower(4);
                    sphere.setScaleX(1.15); sphere.setScaleY(1.15); sphere.setScaleZ(1.15);
                }
                case BOUND -> {
                    // Subtle green tint to show "live window wired"
                    material.setDiffuseColor(baseColor.interpolate(Color.LIMEGREEN, 0.35));
                    material.setSpecularColor(Color.LIGHTGREEN);
                    material.setSpecularPower(12);
                    sphere.setScaleX(1.08); sphere.setScaleY(1.08); sphere.setScaleZ(1.08);
                }
            }
        }

        /** Convenience wrapper used by legacy link-mode code. */
        void setHighlight(boolean on) {
            applyVisualState(on ? VisualState.LINK_SOURCE : VisualState.NORMAL);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphEdge
    // ══════════════════════════════════════════════════════════════════

    private static class GraphEdge {
        GraphNode a, b;
        Cylinder  cylinder;

        GraphEdge(GraphNode a, GraphNode b) {
            this.a = a; this.b = b;
            cylinder = new Cylinder(1.5, 1);
            PhongMaterial mat = new PhongMaterial();
            mat.setDiffuseColor(Color.color(0.5, 0.7, 1.0, 0.4));
            cylinder.setMaterial(mat);
        }

        void update() {
            double dx = b.x-a.x, dy = b.y-a.y, dz = b.z-a.z;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < 0.001) return;

            cylinder.setHeight(dist);
            cylinder.setTranslateX((a.x+b.x)/2.0);
            cylinder.setTranslateY((a.y+b.y)/2.0);
            cylinder.setTranslateZ((a.z+b.z)/2.0);

            Point3D yAxis = new Point3D(0, 1, 0);
            Point3D dir   = new Point3D(dx/dist, dy/dist, dz/dist);
            Point3D axis  = yAxis.crossProduct(dir);
            double  angle = Math.toDegrees(Math.acos(
                                Math.max(-1, Math.min(1, yAxis.dotProduct(dir)))));
            cylinder.getTransforms().setAll(new Rotate(angle, axis));
        }

        /** True if this edge connects the same two nodes (in either direction). */
        boolean connects(GraphNode p, GraphNode q) {
            return (a == p && b == q) || (a == q && b == p);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  APPLICATION STATE
    // ══════════════════════════════════════════════════════════════════

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Random rng = new Random(42);

    private final Group           world3D = new Group();
    private final Rotate          rotateX = new Rotate(20,  Rotate.X_AXIS);
    private final Rotate          rotateY = new Rotate(-30, Rotate.Y_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private double mouseX, mouseY;
    private int    spawnIndex = 0;

    // ── Link-mode state ───────────────────────────────────────────────
    /** When true, clicks on spheres select/link rather than drag camera. */
    private boolean linkModeActive = false;

    /**
     * The first node clicked in a link operation.
     * null  = no node selected yet.
     * non-null = waiting for the second click.
     */
    private GraphNode linkSource = null;

    /** Status label shown under the Link Mode button */
    private Label statusLabel;

    /** The Link Mode toggle button — kept as field so we can restyle it */
    private Button linkBtn;

    /** Text input for the name of the next node to be spawned */
    private javafx.scene.control.TextField nameField;

    // ── Selection / deletion state ────────────────────────────────────
    /**
     * The currently selected node (available in BOTH modes).
     * null = nothing selected.
     * Selection is shown by a red emissive glow on the sphere.
     * Distinct from linkSource: a node can be the delete-selection in
     * Navigate mode without ever entering Link mode.
     */
    private GraphNode selectedNode = null;

    /** Delete button — disabled when nothing is selected. */
    private Button deleteBtn;

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Workspace Graph — Manual Linking");
        BorderPane root = buildUI();

        // Start with one anchor node pinned to the world origin
        addNodeAt("Root", Color.web("#58A6FF"), 0, 0, 0);

        startPhysicsLoop();

        Scene scene = new Scene(root, SCENE_W, SCENE_H, Color.web("#0D1117"));

        // Delete key fires deleteSelectedNode() from anywhere in the scene.
        // We attach it here (after Scene construction) so the handler has
        // access to the fully-built scene object.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE
                    || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                deleteSelectedNode();
            }
        });

        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        SubScene subScene = new SubScene(world3D, SCENE_W - 230, SCENE_H, true,
                                         SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);

        camera.setNearClip(0.1);
        camera.setFarClip(4000);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        subScene.setCamera(camera);

        world3D.getTransforms().addAll(rotateX, rotateY);

        AmbientLight ambient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight key  = new PointLight(Color.color(0.9, 0.95, 1.0));
        key.setTranslateX(-200); key.setTranslateY(-300); key.setTranslateZ(-200);
        PointLight fill = new PointLight(Color.color(0.2, 0.3, 0.5));
        fill.setTranslateX(200);  fill.setTranslateY(200);  fill.setTranslateZ(100);
        world3D.getChildren().addAll(ambient, key, fill);

        // The viewport StackPane is also the target for background clicks
        // (deselects any pending link source when user clicks empty space).
        StackPane viewport = new StackPane(subScene);
        viewport.setStyle("-fx-background-color: #0D1117;");
        attachMouseHandlers(viewport);

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0D1117;");
        root.setCenter(viewport);
        root.setRight(buildSidebar());
        return root;
    }

    private javafx.scene.control.ScrollPane buildSidebar() {
        VBox sidebar = new VBox(10);
        sidebar.setPrefWidth(220);
        sidebar.setPadding(new Insets(16, 14, 16, 14));
        sidebar.setStyle("-fx-background-color: #161B22;");

        // ── Title ────────────────────────────────────────────────────
        Label title = new Label("Workspace Graph 3D");
        title.setFont(Font.font("Monospace", FontWeight.BOLD, 14));
        title.setTextFill(Color.web("#58A6FF"));
        title.setWrapText(true);

        // ── Controls (compact two-column grid) ───────────────────────
        Region div1 = divider();
        Label ctrlTitle = sectionHeader("CONTROLS");

        // Each row: [shortcut badge]  [description]
        String[][] hints = {
            { "L-drag",   "Rotate camera"          },
            { "R-drag",   "Pan camera"              },
            { "Scroll",   "Zoom in / out"           },
            { "Click",    "Select node"             },
            { "2×Click",  "Focus OS window (⚡)"    },
            { "Delete",   "Remove selected"         },
        };
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(6); grid.setVgap(3);
        for (int i = 0; i < hints.length; i++) {
            Label badge = new Label(hints[i][0]);
            badge.setFont(Font.font("Monospace", FontWeight.BOLD, 9));
            badge.setTextFill(Color.web("#58A6FF"));
            badge.setMinWidth(42);
            Label desc = new Label(hints[i][1]);
            desc.setFont(Font.font("Monospace", 10));
            desc.setTextFill(Color.web("#8B949E"));
            grid.add(badge, 0, i);
            grid.add(desc,  1, i);
        }

        // ── Spawn section ────────────────────────────────────────────
        Region div2 = divider();
        Label spawnTitle = sectionHeader("SPAWN NODE");

        Label nameLabel = new Label("Node Name");
        nameLabel.setFont(Font.font("Monospace", 11));
        nameLabel.setTextFill(Color.web("#8B949E"));

        nameField = new javafx.scene.control.TextField();
        nameField.setPromptText("e.g. Notepad");
        nameField.setMaxWidth(Double.MAX_VALUE);
        nameField.setStyle("""
            -fx-background-color: #0D1117;
            -fx-text-fill: #E6EDF3;
            -fx-prompt-text-fill: #484F58;
            -fx-border-color: #30363D;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
            -fx-padding: 6 10 6 10;
            -fx-font-family: Monospace;
            -fx-font-size: 12;
            """);
        nameField.setOnAction(e -> spawnNewNode());

        Button spawnBtn = styledButton("+ Spawn Node", "#238636", "#2EA043");
        spawnBtn.setOnAction(e -> spawnNewNode());

        // ── Delete section ───────────────────────────────────────────
        Region divDel = divider();
        Label delTitle = sectionHeader("SELECTED NODE");

        deleteBtn = styledButton("🗑  Delete Selected", "#6E1010", "#9B1C1C");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(e -> deleteSelectedNode());

        // ── Link Mode toggle ─────────────────────────────────────────
        Region div3 = divider();
        Label linkTitle = sectionHeader("LINK MODE");

        linkBtn = styledButton("🔗  Link Mode: OFF", "#21262D", "#30363D");
        linkBtn.setOnAction(e -> toggleLinkMode());

        statusLabel = new Label("Enable Link Mode,\nthen click two nodes\nto connect them.\n\n⚡ = bound to live\nOS window.\n2×click to focus it.");
        statusLabel.setFont(Font.font("Monospace", 10));
        statusLabel.setTextFill(Color.web("#8B949E"));
        statusLabel.setWrapText(true);

        sidebar.getChildren().addAll(
            title,
            div1, ctrlTitle, grid,
            div2, spawnTitle, nameLabel, nameField, spawnBtn,
            divDel, delTitle, deleteBtn,
            div3, linkTitle, linkBtn, statusLabel
        );

        // Wrap in a ScrollPane so nothing is ever clipped
        javafx.scene.control.ScrollPane scroll =
            new javafx.scene.control.ScrollPane(sidebar);
        scroll.setPrefWidth(238);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("""
            -fx-background: #161B22;
            -fx-background-color: #161B22;
            -fx-border-color: #30363D;
            -fx-border-width: 0 0 0 1;
            """);
        return scroll;
    }

    // ══════════════════════════════════════════════════════════════════
    //  LINK MODE TOGGLE
    // ══════════════════════════════════════════════════════════════════

    /**
     * Switches between Navigate mode and Link mode.
     *
     * Navigate mode: normal drag-to-rotate behaviour.
     * Link mode:     drag disabled; clicks on spheres select/connect them.
     */
    private void toggleLinkMode() {
        linkModeActive = !linkModeActive;

        // Clear any pending selection when toggling off
        if (!linkModeActive) clearLinkSelection();

        // Clear delete-selection when entering link mode so the two
        // highlight colours never appear on different nodes simultaneously.
        if (linkModeActive) clearSelection();

        if (linkModeActive) {
            linkBtn.setText("🔗  Link Mode: ON");
            linkBtn.setStyle("""
                -fx-background-color: #B45309;
                -fx-text-fill: #FDE68A;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                -fx-padding: 10 14 10 14;
                """);
            setStatus("Click a node to\nselect it first.");
        } else {
            linkBtn.setText("🔗  Link Mode: OFF");
            linkBtn.setStyle("""
                -fx-background-color: #21262D;
                -fx-text-fill: #8B949E;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                -fx-padding: 10 14 10 14;
                """);
            setStatus("Enable Link Mode to\nconnect nodes.");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SPHERE CLICK HANDLER  (the core of the linking logic)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Central dispatch for all sphere clicks.
     *
     * NAVIGATE mode:  single-click selects / deselects a node for deletion.
     * LINK mode:      first click picks source, second click links/unlinks.
     *
     * The two selection concepts (selectedNode for deletion, linkSource for
     * linking) are kept intentionally separate so a node can be highlighted
     * as a link source without clearing the deletion selection, and vice versa.
     */
    private void handleSphereClick(GraphNode clicked) {
        if (linkModeActive) {
            handleLinkClick(clicked);
        } else {
            handleSelectClick(clicked);
        }
    }

    // ── Navigate-mode selection ───────────────────────────────────────

    /**
     * Clicking a sphere in Navigate mode selects it (red glow + scale-up).
     * Clicking the same sphere again deselects it.
     * Clicking a different sphere moves the selection to that node.
     */
    private void handleSelectClick(GraphNode clicked) {
        if (selectedNode == clicked) {
            // Toggle off — clicking the already-selected node deselects it
            clearSelection();
        } else {
            selectNode(clicked);
        }
    }

    /** Mark a node as the deletion target: apply red visual state, enable button. */
    private void selectNode(GraphNode node) {
        // Clear any previous selection first
        if (selectedNode != null) {
            selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
        }
        selectedNode = node;
        node.applyVisualState(GraphNode.VisualState.DELETE_SELECT);
        deleteBtn.setDisable(false);
    }

    /** Remove the deletion selection without deleting the node. */
    private void clearSelection() {
        if (selectedNode != null) {
            selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
            selectedNode = null;
        }
        deleteBtn.setDisable(true);
    }

    // ── Deletion ──────────────────────────────────────────────────────

    /**
     * Removes the currently selected node and ALL edges connected to it.
     *
     * Steps:
     *   1. Collect every edge that touches the node.
     *   2. Remove their Cylinder objects from world3D.
     *   3. Remove the edges from the edges list.
     *   4. Remove the node's Sphere and billboard Group from world3D.
     *   5. Remove the node from the nodes list.
     *   6. Reset selection state and disable the delete button.
     *
     * We iterate over a snapshot copy of the edge list so we can safely
     * remove from the live list inside the loop without a
     * ConcurrentModificationException.
     */
    private void deleteSelectedNode() {
        if (selectedNode == null) return;

        GraphNode target = selectedNode;

        // 1 & 2 — remove all connected edges (snapshot iteration)
        List<GraphEdge> toRemove = new ArrayList<>();
        for (GraphEdge e : edges) {
            if (e.a == target || e.b == target) toRemove.add(e);
        }
        for (GraphEdge e : toRemove) {
            world3D.getChildren().remove(e.cylinder);
            edges.remove(e);
        }

        // 3 — if this node was also the link source, clear that state too
        if (linkSource == target) {
            linkSource = null;
            if (linkModeActive) setStatus("Click a node to\nselect it first.");
        }

        // 4 — remove 3D objects from the scene graph
        world3D.getChildren().remove(target.sphere);
        world3D.getChildren().remove(target.billboard);

        // 5 — remove from physics list
        nodes.remove(target);

        // 6 — reset selection (selectedNode → null, button → disabled)
        selectedNode = null;
        deleteBtn.setDisable(true);
    }

    // ══════════════════════════════════════════════════════════════════
    //  LINK-MODE CLICK HANDLER  (extracted from handleSphereClick)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Two-click linking state machine (Link mode only):
     *   linkSource == null        → clicked becomes source; gold highlight.
     *   linkSource == clicked     → same node twice; deselect.
     *   linkSource != clicked     → create or remove edge between them.
     */
    private void handleLinkClick(GraphNode clicked) {
        if (linkSource == null) {
            // ── First click: select this node as the link source ─────
            linkSource = clicked;
            clicked.setHighlight(true);
            setStatus("Node \"" + clicked.name + "\"\nselected.\nNow click a second\nnode to link.");

        } else if (linkSource == clicked) {
            // ── Clicked the same node twice: deselect ────────────────
            clearLinkSelection();
            setStatus("Deselected.\nClick a node to\nselect it first.");

        } else {
            // ── Second click: link or unlink the two nodes ───────────
            GraphNode src = linkSource;
            clearLinkSelection();

            GraphEdge existing = findEdge(src, clicked);
            if (existing != null) {
                removeEdge(existing);
                setStatus("Edge removed:\n\"" + src.name + "\"\n↔ \"" + clicked.name + "\"");
            } else {
                addEdge(src, clicked);
                setStatus("Linked:\n\"" + src.name + "\"\n↔ \"" + clicked.name + "\"");
            }
        }
    }

    /** Returns the edge between a and b (either direction), or null. */
    private GraphEdge findEdge(GraphNode a, GraphNode b) {
        for (GraphEdge e : edges) if (e.connects(a, b)) return e;
        return null;
    }

    /** Removes an edge from the graph and from the 3D scene. */
    private void removeEdge(GraphEdge edge) {
        edges.remove(edge);
        world3D.getChildren().remove(edge.cylinder);
    }

    /** Clears the first-selected node highlight and resets linkSource. */
    private void clearLinkSelection() {
        if (linkSource != null) {
            linkSource.setHighlight(false);
            linkSource = null;
        }
    }

    /** Convenience: update status label text. */
    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ══════════════════════════════════════════════════════════════════
    //  MOUSE HANDLERS
    //  Drag is only forwarded to the camera when NOT in link mode.
    //  Clicking empty space in link mode deselects the pending source.
    // ══════════════════════════════════════════════════════════════════

    private void attachMouseHandlers(StackPane viewport) {

        // ── Record cursor position on any button press ────────────────
        // Both left and right drag share this baseline; the drag handler
        // reads isPrimaryButtonDown / isSecondaryButtonDown to branch.
        viewport.setOnMousePressed(e -> {
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });

        viewport.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();

            if (e.isPrimaryButtonDown()) {
                // ── LEFT DRAG → orbital rotation ─────────────────────
                // Blocked in link mode so the user can click spheres
                // without the scene spinning under their cursor.
                if (linkModeActive) return;
                rotateY.setAngle(rotateY.getAngle() + dx * ROTATION_SPEED);
                rotateX.setAngle(rotateX.getAngle() + dy * ROTATION_SPEED);

            } else if (e.isSecondaryButtonDown()) {
                // ── RIGHT DRAG → camera pan (Blender-style) ──────────
                //
                // PAN MATH EXPLAINED
                // ──────────────────
                // The camera is a child of the root Scene, NOT of world3D,
                // so its X/Y/Z axes are always fixed to the screen:
                //   camera +X  = right on screen
                //   camera +Y  = down on screen  (JavaFX Y is inverted)
                //   camera +Z  = into the screen (away from viewer)
                //
                // To pan left/right we move the camera along its local X.
                // To pan up/down we move the camera along its local Y.
                //
                // However, we also want the WORLD to appear to move — not
                // just the camera lens.  The simplest correct solution for
                // a scene where world3D is rotated is to TRANSLATE world3D
                // instead of the camera, because world3D's rotation has
                // already been applied.  Moving the world by +dx feels like
                // moving the camera by -dx (Newton's 3rd law of viewpoints).
                //
                // WHY NOT move the camera X/Y directly?
                // If we do camera.setTranslateX(camera.getTranslateX() + dx),
                // the pan direction is always screen-aligned and ignores any
                // rotation the user has applied.  After rotating 90° the
                // world's "right" is now the screen's "up", so a horizontal
                // drag would move the world in the wrong direction.
                // Translating world3D AFTER its rotation transform means the
                // movement is already in the rotated frame — it feels natural.
                //
                // Y is negated because screen Y increases downward but we
                // want dragging down to pull the world down (i.e. pan view up).
                world3D.setTranslateX(world3D.getTranslateX() + dx * PAN_SPEED);
                world3D.setTranslateY(world3D.getTranslateY() + dy * PAN_SPEED);
            }
        });

        // ── Left-click on empty space ─────────────────────────────────
        // Navigate mode: deselect any node marked for deletion.
        // Link mode:     deselect the pending link source.
        viewport.setOnMouseClicked(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (linkModeActive) {
                if (linkSource != null) {
                    clearLinkSelection();
                    setStatus("Deselected.\nClick a node to\nselect it first.");
                }
            } else {
                clearSelection();
            }
        });

        // ── Scroll → zoom ─────────────────────────────────────────────
        // The camera sits at a negative Z (behind the scene looking forward).
        // Scrolling UP (positive deltaY) moves it toward the scene  → zoom in.
        // Scrolling DOWN (negative deltaY) moves it away             → zoom out.
        // Clamped between -ZOOM_MAX and -ZOOM_MIN.
        viewport.setOnScroll(e -> {
            double newZ = camera.getTranslateZ() + e.getDeltaY() * ZOOM_SPEED / 40.0;
            newZ = Math.max(-ZOOM_MAX, Math.min(-ZOOM_MIN, newZ));
            camera.setTranslateZ(newZ);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Adds a node to the physics world and scene graph at an exact position.
     * No edges are created — all connections are made manually by the user.
     */
    private GraphNode addNodeAt(String name, Color color, double x, double y, double z) {
        GraphNode node = new GraphNode(name, color, x, y, z);
        nodes.add(node);
        world3D.getChildren().addAll(node.sphere, node.billboard);

        // ── Click handlers ───────────────────────────────────────────
        // Single click: select / link (existing behaviour).
        // Double click: focus the bound OS window (new behaviour).
        // Both handlers call e.consume() so clicks don't bubble to the
        // viewport and accidentally deselect or start a camera drag.
        node.sphere.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && node.isBound()) {
                // ── Double-click → focus the OS window ───────────────
                // Run on a daemon thread so the JNA call (which crosses
                // into kernel land) never blocks the JavaFX pulse.
                Thread t = new Thread(() -> WindowManager.focusWindow(node.hwnd),
                                      "winfocus-" + node.name);
                t.setDaemon(true);
                t.start();
            } else if (e.getClickCount() == 1) {
                // ── Single click → selection / linking ───────────────
                handleSphereClick(node);
            }
            e.consume();
        });

        return node;
    }

    /**
     * Adds a node at a random position within the world sphere.
     * Delegates to addNodeAt — all click-handler wiring lives there.
     */
    private GraphNode addNode(String name, Color color) {
        double x = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double y = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double z = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        return addNodeAt(name, color, x, y, z);
    }

    /**
     * Creates an edge between two nodes and adds its cylinder to the scene.
     * Duplicate-edge guard: silently ignores if the edge already exists.
     */
    private void addEdge(GraphNode a, GraphNode b) {
        if (findEdge(a, b) != null) return;  // no duplicate edges
        GraphEdge edge = new GraphEdge(a, b);
        edges.add(edge);
        world3D.getChildren().add(edge.cylinder);
    }

    /**
     * Spawns a disconnected node, then immediately opens the window picker
     * showing every currently open OS window so the user can decide which
     * one (if any) to bind the node to.
     *
     * The scan runs on a background thread so EnumWindows never stalls the
     * 60-fps render loop.  The picker is opened on the FX thread via
     * Platform.runLater once the list is ready.  If JNA is unavailable
     * (non-Windows or missing JARs) the node spawns silently with no picker.
     */
    private void spawnNewNode() {
        String typed = nameField.getText().trim();
        String name  = typed.isEmpty() ? "Node " + (spawnIndex + 1) : typed;
        Color  color = Color.web(SPAWN_COLORS[spawnIndex % SPAWN_COLORS.length]);
        spawnIndex++;

        GraphNode node = addNode(name, color);
        nameField.clear();

        if (linkModeActive) {
            setStatus("\"" + name + "\" spawned.\nClick it to select\nand link it.");
        }

        if (!WindowManager.isAvailable()) return;

        Thread scanner = new Thread(() -> {
            List<WindowManager.WindowEntry> all = WindowManager.listAllWindows();
            javafx.application.Platform.runLater(() -> showWindowPicker(node, all));
        }, "winscan-" + name);
        scanner.setDaemon(true);
        scanner.start();
    }

    /** Bind a node to a specific WindowEntry and apply the BOUND visual. */
    private void bindNode(GraphNode node, WindowManager.WindowEntry entry) {
        node.hwnd = entry.hwnd();
        node.applyVisualState(GraphNode.VisualState.BOUND);
        node.label.setText(node.name + " ⚡");
    }

    /**
     * Opens a modal window-picker dialog.
     *
     * Layout (top to bottom):
     *   ┌─────────────────────────────────┐
     *   │  Header: node name + count      │
     *   │  Search box (filters list live) │
     *   ├─────────────────────────────────┤
     *   │  Scrollable list of windows     │
     *   │    [icon]  Full title           │
     *   │    ...                          │
     *   ├─────────────────────────────────┤
     *   │  [ Don't bind to any window ]   │
     *   └─────────────────────────────────┘
     *
     * The search box uses a TextField listener that rebuilds the visible
     * rows on every keystroke, filtering by case-insensitive substring.
     * The full list is never mutated — filtering works on a view over it.
     *
     * If JNA is unavailable, all is empty and the picker shows a
     * "No windows found" message instead of rows.
     */
    private void showWindowPicker(GraphNode node,
                                  List<WindowManager.WindowEntry> all) {
        javafx.stage.Stage picker = new javafx.stage.Stage();
        picker.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        picker.setTitle("Bind \"" + node.name + "\" to a window");
        picker.setResizable(true);
        picker.setMinWidth(460);
        picker.setMinHeight(320);

        // ── Header ───────────────────────────────────────────────────
        Label header = new Label("Select a window to bind to  \"" + node.name + "\"");
        header.setFont(Font.font("Monospace", FontWeight.BOLD, 13));
        header.setTextFill(Color.web("#58A6FF"));
        header.setPadding(new Insets(14, 16, 4, 16));

        Label subheader = new Label(all.size() + " open windows detected");
        subheader.setFont(Font.font("Monospace", 11));
        subheader.setTextFill(Color.web("#484F58"));
        subheader.setPadding(new Insets(0, 16, 10, 16));

        // ── Search box ───────────────────────────────────────────────
        javafx.scene.control.TextField search =
            new javafx.scene.control.TextField();
        search.setPromptText("Type to filter windows…");
        search.setStyle("""
            -fx-background-color: #161B22;
            -fx-text-fill: #E6EDF3;
            -fx-prompt-text-fill: #484F58;
            -fx-border-color: #30363D;
            -fx-border-radius: 0;
            -fx-background-radius: 0;
            -fx-padding: 8 12 8 12;
            -fx-font-family: Monospace;
            -fx-font-size: 12;
            """);

        // ── Scrollable rows container ─────────────────────────────────
        VBox rowsBox = new VBox(0);
        rowsBox.setStyle("-fx-background-color: #0D1117;");

        javafx.scene.control.ScrollPane scroll =
            new javafx.scene.control.ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(
            javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(
            javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("""
            -fx-background: #0D1117;
            -fx-background-color: #0D1117;
            -fx-border-color: #30363D;
            -fx-border-width: 1 0 1 0;
            """);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        // Styles reused for every row button
        String rowNormal = """
            -fx-background-color: #0D1117;
            -fx-text-fill: #E6EDF3;
            -fx-border-color: #21262D;
            -fx-border-width: 0 0 1 0;
            -fx-background-radius: 0;
            -fx-alignment: CENTER_LEFT;
            -fx-padding: 9 14 9 14;
            -fx-cursor: hand;
            -fx-font-family: Monospace;
            -fx-font-size: 12;
            """;
        String rowHover = rowNormal
            .replace("#0D1117;", "#161B22;")
            .replace("#E6EDF3;", "#58A6FF;");

        // Build a row button for one WindowEntry
        java.util.function.Function<WindowManager.WindowEntry, Button> makeRow = entry -> {
            Button btn = new Button(entry.title());
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setStyle(rowNormal);
            btn.setOnMouseEntered(e -> btn.setStyle(rowHover));
            btn.setOnMouseExited(e  -> btn.setStyle(rowNormal));
            btn.setOnAction(e -> {
                bindNode(node, entry);
                picker.close();
            });
            return btn;
        };

        // Populate or filter rows based on the current search text
        Runnable rebuildRows = () -> {
            String filter = search.getText().trim().toLowerCase();
            rowsBox.getChildren().clear();

            List<WindowManager.WindowEntry> visible = filter.isEmpty()
                ? all
                : all.stream()
                     .filter(e -> e.title().toLowerCase().contains(filter))
                     .toList();

            if (visible.isEmpty()) {
                Label none = new Label(
                    all.isEmpty() ? "No open windows detected."
                                  : "No windows match \"" + filter + "\".");
                none.setFont(Font.font("Monospace", 12));
                none.setTextFill(Color.web("#484F58"));
                none.setPadding(new Insets(16));
                rowsBox.getChildren().add(none);
            } else {
                for (WindowManager.WindowEntry e : visible)
                    rowsBox.getChildren().add(makeRow.apply(e));
            }

            // Update the count in the subheader as the filter changes
            subheader.setText(visible.size() + " of " + all.size()
                              + " windows shown");
        };

        // Initial population (no filter)
        rebuildRows.run();

        // Rebuild on every keystroke
        search.textProperty().addListener((obs, oldVal, newVal) ->
            rebuildRows.run());

        // ── Don't bind button ─────────────────────────────────────────
        Button noBind = new Button("Don't bind to any window");
        noBind.setMaxWidth(Double.MAX_VALUE);
        noBind.setStyle("""
            -fx-background-color: #161B22;
            -fx-text-fill: #484F58;
            -fx-border-color: transparent;
            -fx-background-radius: 0;
            -fx-padding: 10 14 10 14;
            -fx-cursor: hand;
            -fx-font-family: Monospace;
            -fx-font-size: 11;
            """);
        noBind.setOnMouseEntered(e -> noBind.setStyle(noBind.getStyle()
            .replace("#484F58;", "#8B949E;")));
        noBind.setOnMouseExited(e -> noBind.setStyle(noBind.getStyle()
            .replace("#8B949E;", "#484F58;")));
        noBind.setOnAction(e -> picker.close());

        // ── Assemble ──────────────────────────────────────────────────
        VBox root = new VBox(0, header, subheader, search, scroll, noBind);
        root.setStyle("-fx-background-color: #0D1117;");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 480, 460);
        scene.setFill(Color.web("#0D1117"));
        picker.setScene(scene);

        // Auto-focus the search box so the user can type immediately
        javafx.application.Platform.runLater(search::requestFocus);
        picker.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  PHYSICS LOOP
    // ══════════════════════════════════════════════════════════════════

    private void startPhysicsLoop() {
        new AnimationTimer() {
            @Override public void handle(long now) {
                physicsTick();
                syncSceneGraph();
            }
        }.start();
    }

    private void physicsTick() {
        int n = nodes.size();
        for (GraphNode nd : nodes) { nd.fx = 0; nd.fy = 0; nd.fz = 0; }

        // Repulsion: Coulomb  F = k / r²
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                GraphNode a = nodes.get(i), b = nodes.get(j);
                double dx = a.x-b.x, dy = a.y-b.y, dz = a.z-b.z;
                double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
                double f = REPULSION / (dist * dist);
                double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
                a.fx+=fx; a.fy+=fy; a.fz+=fz;
                b.fx-=fx; b.fy-=fy; b.fz-=fz;
            }
        }

        // Attraction: Hooke  F = k * (dist - rest)
        for (GraphEdge e : edges) {
            double dx = e.b.x-e.a.x, dy = e.b.y-e.a.y, dz = e.b.z-e.a.z;
            double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
            double f = ATTRACTION * (dist - SPRING_LENGTH);
            double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
            e.a.fx+=fx; e.a.fy+=fy; e.a.fz+=fz;
            e.b.fx-=fx; e.b.fy-=fy; e.b.fz-=fz;
        }

        // Euler integrate + damp + clamp
        for (GraphNode nd : nodes) {
            nd.vx = (nd.vx + nd.fx) * DAMPING;
            nd.vy = (nd.vy + nd.fy) * DAMPING;
            nd.vz = (nd.vz + nd.fz) * DAMPING;
            double spd = Math.sqrt(nd.vx*nd.vx + nd.vy*nd.vy + nd.vz*nd.vz);
            if (spd > MAX_VELOCITY) {
                double s = MAX_VELOCITY / spd;
                nd.vx*=s; nd.vy*=s; nd.vz*=s;
            }
            nd.x += nd.vx; nd.y += nd.vy; nd.z += nd.vz;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SCENE GRAPH SYNC
    // ══════════════════════════════════════════════════════════════════

    private void syncSceneGraph() {
        double ay = rotateY.getAngle(), ax = rotateX.getAngle();
        for (GraphNode nd : nodes) {
            nd.applyPosition();
            nd.updateBillboard(ay, ax);
        }
        for (GraphEdge edge : edges) edge.update();
    }

    // ══════════════════════════════════════════════════════════════════
    //  SMALL UI HELPERS
    // ══════════════════════════════════════════════════════════════════

    private Region divider() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: #30363D;");
        return r;
    }

    private Label sectionHeader(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        l.setTextFill(Color.web("#8B949E"));
        return l;
    }

    private Button styledButton(String text, String bg, String bgHover) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        String base  = "-fx-background-color:%s;-fx-text-fill:#E6EDF3;"
                     + "-fx-background-radius:6;-fx-cursor:hand;"
                     + "-fx-padding:10 14 10 14;".formatted(bg);
        String hover = base.replace(bg, bgHover);
        btn.setStyle(base);
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e  -> btn.setStyle(base));
        return btn;
    }
}
