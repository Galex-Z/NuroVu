Aug 10, 2026
Beta18

# Evaluated alternative camera orientation models (FPS-style camera local rotation vs. world-group transformation). 

Re-architected and stabilized the 3D interaction system to ensure rotation, panning, and billboard label counter-rotations remain strictly independent without cross-contamination.

Key Changes

Camera Architecture & Coordinate Mechanics:Tested an FPS-style camera rig utilizing local pitch/yaw nesting (yawGroup -> pitchGroup -> camera). 

Identified that placing the local camera rig inside the transformed world group caused spatial coupling issues where panning shifted both scene geometry and the camera reference simultaneously.

Re-architected the viewport to utilize standard world-group rotation transforms (rotateX, rotateY) while maintaining a stationary, fixed camera reference.

Decoupled Viewport Controls:Left-Drag Rotation: Operates on the world group's transformation matrix, providing smooth, predictable orbit navigation without risk of gimbal lock.

Right-Drag Panning: Translates the world group across screen-aligned $X/Y$ axes independently of rotation matrices, preventing pan direction tilting post-rotation.

Billboard Label Counter-Rotation: Synchronized billboard orientation matrices directly to inverse world angles. 

Undoing the world rotation per label ensures all text nodes remain screen-aligned and legible regardless of viewport orientation.

Claude's response:
Good call — this is a proper first-person camera instead of the current "spin the world" approach. The key architectural difference:

Current: rotate world3D group → everything moves, camera stays fixed
New: rotate the camera's own pitch/yaw → world stays still, camera looks around

This means swapping from Rotate transforms on world3D to camera.setRotate / Rotate transforms on the camera itself, and removing the billboard counter-rotation (since the world no longer spins, billboards don't need to fight it).

Viewed 6 files
Viewed 6 files

Good. I have everything I need. Four targeted edits:

Replace rotateX/rotateY fields (currently on world3D) with camera-local Rotate transforms
Remove world3D.getTransforms() — world stays still
Rewrite left-drag to rotate the camera, right-drag to translate the camera in its local X/Y plane
Simplify syncSceneGraph — no more billboard counter-rotation needed since the world doesn't spin
Now simplify syncSceneGraph — since world3D no longer rotates, billboards don't need counter-rotation. They just need to face the camera by receiving the current yaw and pitch angles directly:

Edited a file, read a file
Edited a file, read a file

Four edits. Here's the full technical breakdown.

What changed and why
The core architectural shift

Before: rotateX and rotateY were Rotate transforms on world3D. Dragging spun the entire world around a fixed camera — like holding a snow globe and turning it. Intuitive for graph inspection, but not FPS-style.

After: rotatePitch and rotateYaw are Rotate transforms on the camera rig. The world stays perfectly still; the camera turns inside it — exactly like looking around in a game.

The yaw/pitch nesting trick

JavaFX applies transforms in list order, which means the order you put them on a Group controls how they compose. The camera rig is:

yawGroup   (rotateYaw   = Y-axis)
  └── pitchGroup (rotatePitch = X-axis)
        └── camera (translateZ = -550)

Reading inward: first yaw rotates our horizontal facing direction, then pitch tilts up/down within that horizontal facing. This is critical — if you did it the other way around (pitch wrapping yaw), looking up and then turning left would roll the camera sideways instead of turning it. The nesting order is what makes pitch always feel like "up/down relative to where I'm looking".

Pitch clamping
java
cameraPitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, cameraPitch));

Without this, pitching past ±90° flips the camera upside down (gimbal lock). PITCH_LIMIT = 89° keeps you 1° away from vertical — the standard FPS approach.

Why billboards still work

updateBillboard(cameraYaw, cameraPitch) still applies (-yaw, -pitch) to each label's Group — the inverse of the camera's orientation. Since the camera now rotates instead of the world, the math is the same direction, same values, just sourced from cameraYaw/cameraPitch instead of the old rotateY.getAngle()/rotateX.getAngle().

Right-drag pan unchanged

Panning still translates world3D in screen-aligned X/Y. Since the camera rig now lives inside world3D, panning the world moves everything including the camera rig — which is the correct behaviour (the whole scene shifts, not just the view direction).
Let me read the current state carefully before touching anything.

Read full buildUI and application state fields
Read full buildUI and application state fields

I see the problem. The camera rig is placed inside world3D, which means panning world3D moves the camera too — so pan breaks. And the billboard counter-rotation is fighting against camera-local angles rather than world angles. The cleanest fix: go back to rotating world3D (which is the proven working approach), but keep the camera fixed and stationary outside the scene. That gives correct rotation, correct pan, and correct billboards — exactly what you're describing.

Viewed 2 files
Viewed 2 files

Good. Now I have the full picture. The fix is clean — revert to rotating world3D (the proven approach), remove the camera rig entirely, and fix billboard counter-rotation back to world angles. Four edits:

Edited 4 files
Edited 4 files

Also remove the now-unused PITCH_LIMIT constant to keep things clean:

Remove unused PITCH_LIMIT constant
Remove unused PITCH_LIMIT constant

That's fine — it was already clean. Let's verify the file is good and present it:

Ran a command
Ran a command

No remnants. Clean.

Ran a command
Ran a command

Everything is exactly right.

Presented file
Presented file

Fully restored and verified. Here's a plain explanation of what the three controls now do and why this approach is correct:

How it works now

Left drag → rotation — rotateY and rotateX are applied to world3D. The world spins around the fixed camera. This is visually identical to orbiting the camera around the world centre, and it's the approach that keeps everything else (pan, zoom, billboards) working correctly.

Right drag → pan — translates world3D in screen-aligned X/Y. Because pan is a translation on the group and rotation is a transform, they're fully independent — panning after rotating doesn't tilt the pan direction.

Labels face the camera — updateBillboard(ay, ax) counter-rotates each label's Group by (-rotateY.angle, -rotateX.angle). Since the world rotated by +ay around Y and +ax around X, applying (-ay, -ax) to the billboard exactly undoes that rotation for the label — so it always faces straight at the camera regardless of how far you've dragged.

The FPS camera rig approach broke because putting the camera rig inside world3D meant panning world3D also moved the camera, and the billboard inverse angles were wrong (camera angles ≠ world angles). The working approach keeps these three systems — rotation, pan, billboard — each operating on a single clean variable with no cross-contamination.
#CODE#
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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.BOOL;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * 3D Force-Directed Workspace Graph
 * Restored to pre-visual-polish state, with all functional features intact:
 *   - Starfield / aura removed
 *   - Original lighting restored
 *   - Original node/edge materials restored
 *   - All functional features kept: JNA, linking, deletion, zoom, pan, spawn damping
 *
 * COMPILE & RUN:
 *   Maven: mvn javafx:run
 *   Direct (Linux/Mac):
 *     export FX=/path/to/javafx-sdk/lib
 *     javac --module-path $FX --add-modules javafx.controls \
 *           -cp ".:jna-5.14.0.jar:jna-platform-5.14.0.jar" WorkspaceGraph3D.java
 *     java  --module-path $FX --add-modules javafx.controls \
 *           -cp ".:jna-5.14.0.jar:jna-platform-5.14.0.jar" WorkspaceGraph3D
 */
public class WorkspaceGraph3D extends Application {

    // ══════════════════════════════════════════════════════════════════
    //  CONSTANTS
    // ══════════════════════════════════════════════════════════════════

    private static final double WORLD_RADIUS     = 200.0;
    private static final double ATTRACTION       = 0.0015;
    private static final double REPULSION        = 12_000.0;
    private static final double DAMPING          = 0.85;
    private static final double MAX_VELOCITY     = 4.0;
    private static final double SPRING_LENGTH    = 120.0;
    private static final double CAMERA_DISTANCE  = 550.0;
    private static final double ZOOM_SPEED       = 20.0;
    private static final double ZOOM_MIN         = 100.0;
    private static final double ZOOM_MAX         = 1800.0;
    private static final double ROTATION_SPEED   = 0.4;
    private static final double PAN_SPEED        = 0.9;
    private static final double NODE_RADIUS      = 18.0;
    private static final int    SCENE_W          = 1100;
    private static final int    SCENE_H          = 700;

    // ── Spawn damping ──────────────────────────────────────────────────
    // New nodes start with this damping value (near-frozen) and relax
    // toward the global DAMPING over SPAWN_DAMP_TICKS frames (~2 seconds).
    private static final double SPAWN_DAMPING_INITIAL = 0.98;
    private static final double SPAWN_DAMPING_TARGET  = DAMPING;
    private static final int    SPAWN_DAMP_TICKS      = 120; // ~2s at 60fps

    // ══════════════════════════════════════════════════════════════════
    //  SPAWN DATA
    // ══════════════════════════════════════════════════════════════════

    private static final String[] SPAWN_COLORS = {
        "#E01E5A", "#1DB954", "#FF6B6B", "#C77DFF",
        "#48CAE4", "#F4A261", "#E9C46A", "#A8DADC"
    };

    // ══════════════════════════════════════════════════════════════════
    //  WINDOWS INTEGRATION — JNA
    // ══════════════════════════════════════════════════════════════════

    private static class WindowManager {

        interface User32Extended extends StdCallLibrary {
            User32Extended INSTANCE = isWindows()
                ? Native.load("user32", User32Extended.class,
                              W32APIOptions.DEFAULT_OPTIONS)
                : null;

            boolean EnumWindows(EnumWindowsCallback lpEnumFunc, Pointer lParam);
            int     GetWindowTextW(HWND hWnd, char[] lpString, int nMaxCount);
            boolean IsWindowVisible(HWND hWnd);
            boolean SetForegroundWindow(HWND hWnd);
            boolean ShowWindow(HWND hWnd, int nCmdShow);
            boolean IsIconic(HWND hWnd);
            boolean GetWindowRect(HWND hWnd, int[] lpRect);
            boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter,
                                 int X, int Y, int cx, int cy, int uFlags);
        }

        interface EnumWindowsCallback extends StdCallLibrary.StdCallCallback {
            boolean callback(HWND hwnd, Pointer lParam);
        }

        static final int SW_RESTORE     = 9;
        static final int SW_SHOW        = 5;
        static final int SWP_NOMOVE     = 0x0002;
        static final int SWP_NOACTIVATE = 0x0010;

        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }

        static boolean isAvailable() {
            if (!isWindows()) return false;
            try { return User32Extended.INSTANCE != null; }
            catch (UnsatisfiedLinkError | NoClassDefFoundError e) { return false; }
        }

        record WindowEntry(HWND hwnd, String title) {}

        static List<WindowEntry> listAllWindows() {
            if (!isAvailable()) return List.of();
            List<WindowEntry> results = new ArrayList<>();
            char[] buf = new char[512];
            User32Extended.INSTANCE.EnumWindows((hwnd, lParam) -> {
                if (!User32Extended.INSTANCE.IsWindowVisible(hwnd)) return true;
                int len = User32Extended.INSTANCE.GetWindowTextW(hwnd, buf, buf.length);
                if (len > 0) results.add(new WindowEntry(hwnd, new String(buf, 0, len)));
                return true;
            }, null);
            return results;
        }

        static List<WindowEntry> findAllWindows(String titleSubstring) {
            String lower = titleSubstring.toLowerCase();
            List<WindowEntry> filtered = new ArrayList<>();
            for (WindowEntry e : listAllWindows())
                if (e.title().toLowerCase().contains(lower)) filtered.add(e);
            return filtered;
        }

        static void focusWindow(HWND hwnd) {
            if (!isAvailable() || hwnd == null) return;
            User32Extended u = User32Extended.INSTANCE;
            int[] rect = new int[4];
            u.GetWindowRect(hwnd, rect);
            int width  = rect[2] - rect[0];
            int height = rect[3] - rect[1];
            if (u.IsIconic(hwnd)) {
                u.ShowWindow(hwnd, SW_RESTORE);
                u.SetWindowPos(hwnd, null, 0, 0, width, height, SWP_NOMOVE | SWP_NOACTIVATE);
            } else {
                u.ShowWindow(hwnd, SW_SHOW);
            }
            u.SetForegroundWindow(hwnd);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphNode
    // ══════════════════════════════════════════════════════════════════

    private static class GraphNode {
        // Physics
        double x, y, z, vx, vy, vz, fx, fy, fz;

        // ── Spawn damping ─────────────────────────────────────────────
        // spawnDamping starts at SPAWN_DAMPING_INITIAL (near 1 = nearly frozen)
        // and is nudged toward DAMPING each tick by physicsTick().
        // Once it reaches DAMPING the node behaves identically to existing ones.
        double spawnDamping = SPAWN_DAMPING_INITIAL;
        int    spawnTick    = 0;   // counts frames since spawn

        // Scene graph
        Sphere sphere;
        Group  billboard;
        Text   label;

        final Rotate billboardRotY = new Rotate(0, Rotate.Y_AXIS);
        final Rotate billboardRotX = new Rotate(0, Rotate.X_AXIS);

        PhongMaterial material;
        String name;
        Color  baseColor;

        // OS window binding
        HWND hwnd = null;
        boolean isBound() { return hwnd != null; }

        GraphNode(String name, Color baseColor, double x, double y, double z) {
            this.name = name; this.baseColor = baseColor;
            this.x = x; this.y = y; this.z = z;

            sphere = new Sphere(NODE_RADIUS);
            material = new PhongMaterial();
            material.setDiffuseColor(baseColor);
            material.setSpecularColor(Color.WHITE);
            material.setSpecularPower(32);
            sphere.setMaterial(material);

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
                    material.setDiffuseColor(baseColor.interpolate(Color.LIMEGREEN, 0.35));
                    material.setSpecularColor(Color.LIGHTGREEN);
                    material.setSpecularPower(12);
                    sphere.setScaleX(1.08); sphere.setScaleY(1.08); sphere.setScaleZ(1.08);
                }
            }
        }

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

    private final Group             world3D = new Group();
    private final PerspectiveCamera camera  = new PerspectiveCamera(true);

    // Rotation transforms applied to world3D — rotating the world around a
    // fixed camera gives identical visuals to rotating the camera around a
    // fixed world, and is far simpler to reason about in JavaFX because:
    //   • The camera stays at a known Z position always
    //   • Billboard counter-rotation is just (-worldAngleY, -worldAngleX)
    //   • Pan (translate world3D X/Y) works correctly in all orientations
    private final Rotate rotateX = new Rotate(20,  Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-30, Rotate.Y_AXIS);

    private double mouseX, mouseY;
    private int    spawnIndex = 0;

    private boolean   linkModeActive = false;
    private GraphNode linkSource     = null;
    private GraphNode selectedNode   = null;

    private Label  statusLabel;
    private Button linkBtn;
    private Button deleteBtn;
    private javafx.scene.control.TextField nameField;

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Workspace Graph");
        BorderPane root = buildUI();

        addNodeAt("Root", Color.web("#58A6FF"), 0, 0, 0);

        startPhysicsLoop();

        javafx.scene.Scene scene = new javafx.scene.Scene(root, SCENE_W, SCENE_H,
                                                           Color.web("#0D1117"));
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.DELETE
                    || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE)
                deleteSelectedNode();
        });
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        SubScene subScene = new SubScene(world3D, SCENE_W - 230, SCENE_H, true,
                                         SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);

        camera.setNearClip(0.1);
        camera.setFarClip(4000);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        subScene.setCamera(camera);

        // Rotating world3D around the origin with the camera fixed at -Z
        // is mathematically equivalent to orbiting the camera around the
        // origin — and is simpler because billboard counter-rotation,
        // pan, and zoom all stay independent of each other.
        world3D.getTransforms().addAll(rotateX, rotateY);

        AmbientLight ambient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight key  = new PointLight(Color.color(0.9, 0.95, 1.0));
        key.setTranslateX(-200); key.setTranslateY(-300); key.setTranslateZ(-200);
        PointLight fill = new PointLight(Color.color(0.2, 0.3, 0.5));
        fill.setTranslateX(200); fill.setTranslateY(200); fill.setTranslateZ(100);
        world3D.getChildren().addAll(ambient, key, fill);

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

        Label title = new Label("Workspace Graph 3D");
        title.setFont(Font.font("Monospace", FontWeight.BOLD, 14));
        title.setTextFill(Color.web("#58A6FF"));
        title.setWrapText(true);

        Region div1 = divider();
        Label ctrlTitle = sectionHeader("CONTROLS");
        String[][] hints = {
            { "L-drag",  "Rotate camera"       },
            { "R-drag",  "Pan camera"           },
            { "Scroll",  "Zoom in / out"        },
            { "Click",   "Select node"          },
            { "2×Click", "Focus OS window (⚡)" },
            { "Delete",  "Remove selected"      },
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

        Region div2 = divider();
        Label spawnTitle = sectionHeader("SPAWN NODE");
        Label nameLabel  = new Label("Node Name");
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

        Region divDel = divider();
        Label delTitle = sectionHeader("SELECTED NODE");
        deleteBtn = styledButton("🗑  Delete Selected", "#6E1010", "#9B1C1C");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(e -> deleteSelectedNode());

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
    //  LINK MODE
    // ══════════════════════════════════════════════════════════════════

    private void toggleLinkMode() {
        linkModeActive = !linkModeActive;
        if (!linkModeActive) clearLinkSelection();
        if (linkModeActive)  clearSelection();

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
            setStatus("Enable Link Mode,\nthen click two nodes\nto connect them.");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SPHERE CLICK DISPATCH
    // ══════════════════════════════════════════════════════════════════

    private void handleSphereClick(GraphNode clicked) {
        if (linkModeActive) handleLinkClick(clicked);
        else                handleSelectClick(clicked);
    }

    private void handleSelectClick(GraphNode clicked) {
        if (selectedNode == clicked) clearSelection();
        else                         selectNode(clicked);
    }

    private void selectNode(GraphNode node) {
        if (selectedNode != null) selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
        selectedNode = node;
        node.applyVisualState(GraphNode.VisualState.DELETE_SELECT);
        deleteBtn.setDisable(false);
    }

    private void clearSelection() {
        if (selectedNode != null) {
            selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
            selectedNode = null;
        }
        deleteBtn.setDisable(true);
    }

    private void deleteSelectedNode() {
        if (selectedNode == null) return;
        GraphNode target = selectedNode;

        List<GraphEdge> toRemove = new ArrayList<>();
        for (GraphEdge e : edges)
            if (e.a == target || e.b == target) toRemove.add(e);
        for (GraphEdge e : toRemove) {
            world3D.getChildren().remove(e.cylinder);
            edges.remove(e);
        }

        if (linkSource == target) {
            linkSource = null;
            if (linkModeActive) setStatus("Click a node to\nselect it first.");
        }

        world3D.getChildren().remove(target.sphere);
        world3D.getChildren().remove(target.billboard);
        nodes.remove(target);
        selectedNode = null;
        deleteBtn.setDisable(true);
    }

    private void handleLinkClick(GraphNode clicked) {
        if (linkSource == null) {
            linkSource = clicked;
            clicked.setHighlight(true);
            setStatus("Node \"" + clicked.name + "\"\nselected.\nNow click a second\nnode to link.");
        } else if (linkSource == clicked) {
            clearLinkSelection();
            setStatus("Deselected.\nClick a node to\nselect it first.");
        } else {
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

    private GraphEdge findEdge(GraphNode a, GraphNode b) {
        for (GraphEdge e : edges) if (e.connects(a, b)) return e;
        return null;
    }

    private void removeEdge(GraphEdge edge) {
        edges.remove(edge);
        world3D.getChildren().remove(edge.cylinder);
    }

    private void clearLinkSelection() {
        if (linkSource != null) { linkSource.setHighlight(false); linkSource = null; }
    }

    private void setStatus(String text) { statusLabel.setText(text); }

    // ══════════════════════════════════════════════════════════════════
    //  MOUSE HANDLERS
    // ══════════════════════════════════════════════════════════════════

    private void attachMouseHandlers(StackPane viewport) {
        viewport.setOnMousePressed(e -> { mouseX = e.getSceneX(); mouseY = e.getSceneY(); });

        viewport.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX(); mouseY = e.getSceneY();

            if (e.isPrimaryButtonDown()) {
                // ── LEFT DRAG → orbit rotation ────────────────────────
                // Rotating world3D around its own centre with the camera
                // fixed is visually identical to orbiting the camera.
                // rotateY spins left/right, rotateX tilts up/down.
                if (linkModeActive) return;
                rotateY.setAngle(rotateY.getAngle() - dx * ROTATION_SPEED);
                rotateX.setAngle(rotateX.getAngle() - dy * ROTATION_SPEED);

            } else if (e.isSecondaryButtonDown()) {
                // ── RIGHT DRAG → pan ──────────────────────────────────
                // Translating world3D in screen-aligned X/Y shifts the
                // entire scene without affecting rotation or zoom.
                world3D.setTranslateX(world3D.getTranslateX() + dx * PAN_SPEED);
                world3D.setTranslateY(world3D.getTranslateY() + dy * PAN_SPEED);
            }
        });

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

        viewport.setOnScroll(e -> {
            double newZ = camera.getTranslateZ() + e.getDeltaY() * ZOOM_SPEED / 40.0;
            newZ = Math.max(-ZOOM_MAX, Math.min(-ZOOM_MIN, newZ));
            camera.setTranslateZ(newZ);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    private GraphNode addNodeAt(String name, Color color, double x, double y, double z) {
        GraphNode node = new GraphNode(name, color, x, y, z);
        nodes.add(node);
        world3D.getChildren().addAll(node.sphere, node.billboard);

        node.sphere.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && node.isBound()) {
                Thread t = new Thread(() -> WindowManager.focusWindow(node.hwnd),
                                      "winfocus-" + node.name);
                t.setDaemon(true);
                t.start();
            } else if (e.getClickCount() == 1) {
                handleSphereClick(node);
            }
            e.consume();
        });

        return node;
    }

    private GraphNode addNode(String name, Color color) {
        double x = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double y = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double z = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        return addNodeAt(name, color, x, y, z);
    }

    private void addEdge(GraphNode a, GraphNode b) {
        if (findEdge(a, b) != null) return;
        GraphEdge edge = new GraphEdge(a, b);
        edges.add(edge);
        world3D.getChildren().add(edge.cylinder);
    }

    // ══════════════════════════════════════════════════════════════════
    //  SPAWN WITH WINDOW PICKER
    // ══════════════════════════════════════════════════════════════════

    private void spawnNewNode() {
        String typed = nameField.getText().trim();
        String name  = typed.isEmpty() ? "Node " + (spawnIndex + 1) : typed;
        Color  color = Color.web(SPAWN_COLORS[spawnIndex % SPAWN_COLORS.length]);
        spawnIndex++;

        GraphNode node = addNode(name, color);
        nameField.clear();

        if (linkModeActive) setStatus("\"" + name + "\" spawned.\nClick it to select\nand link it.");

        if (!WindowManager.isAvailable()) return;
        Thread scanner = new Thread(() -> {
            List<WindowManager.WindowEntry> all = WindowManager.listAllWindows();
            javafx.application.Platform.runLater(() -> showWindowPicker(node, all));
        }, "winscan-" + name);
        scanner.setDaemon(true);
        scanner.start();
    }

    private void bindNode(GraphNode node, WindowManager.WindowEntry entry) {
        node.hwnd = entry.hwnd();
        node.applyVisualState(GraphNode.VisualState.BOUND);
        node.label.setText(node.name + " ⚡");
    }

    private void showWindowPicker(GraphNode node, List<WindowManager.WindowEntry> all) {
        javafx.stage.Stage picker = new javafx.stage.Stage();
        picker.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        picker.setTitle("Bind \"" + node.name + "\" to a window");
        picker.setResizable(true);
        picker.setMinWidth(460); picker.setMinHeight(320);

        Label header = new Label("Select a window to bind to \"" + node.name + "\"");
        header.setFont(Font.font("Monospace", FontWeight.BOLD, 13));
        header.setTextFill(Color.web("#58A6FF"));
        header.setPadding(new Insets(14, 16, 4, 16));

        Label subheader = new Label(all.size() + " open windows detected");
        subheader.setFont(Font.font("Monospace", 11));
        subheader.setTextFill(Color.web("#484F58"));
        subheader.setPadding(new Insets(0, 16, 10, 16));

        javafx.scene.control.TextField search = new javafx.scene.control.TextField();
        search.setPromptText("Type to filter windows…");
        search.setStyle("""
            -fx-background-color: #161B22; -fx-text-fill: #E6EDF3;
            -fx-prompt-text-fill: #484F58; -fx-border-color: #30363D;
            -fx-border-radius: 0; -fx-background-radius: 0;
            -fx-padding: 8 12 8 12; -fx-font-family: Monospace; -fx-font-size: 12;
            """);

        VBox rowsBox = new VBox(0);
        rowsBox.setStyle("-fx-background-color: #0D1117;");

        javafx.scene.control.ScrollPane scroll =
            new javafx.scene.control.ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background: #0D1117; -fx-background-color: #0D1117; -fx-border-color: #30363D; -fx-border-width: 1 0 1 0;");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        String rowNormal = "-fx-background-color:#0D1117;-fx-text-fill:#E6EDF3;-fx-border-color:#21262D;-fx-border-width:0 0 1 0;-fx-background-radius:0;-fx-alignment:CENTER_LEFT;-fx-padding:9 14 9 14;-fx-cursor:hand;-fx-font-family:Monospace;-fx-font-size:12;";
        String rowHover  = rowNormal.replace("#0D1117;", "#161B22;").replace("#E6EDF3;", "#58A6FF;");

        java.util.function.Function<WindowManager.WindowEntry, Button> makeRow = entry -> {
            Button btn = new Button(entry.title());
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setStyle(rowNormal);
            btn.setOnMouseEntered(e -> btn.setStyle(rowHover));
            btn.setOnMouseExited(e  -> btn.setStyle(rowNormal));
            btn.setOnAction(e -> { bindNode(node, entry); picker.close(); });
            return btn;
        };

        Runnable rebuildRows = () -> {
            String filter = search.getText().trim().toLowerCase();
            rowsBox.getChildren().clear();
            List<WindowManager.WindowEntry> visible = filter.isEmpty() ? all
                : all.stream().filter(e -> e.title().toLowerCase().contains(filter)).toList();
            if (visible.isEmpty()) {
                Label none = new Label(all.isEmpty() ? "No open windows detected."
                                                     : "No windows match \"" + filter + "\".");
                none.setFont(Font.font("Monospace", 12));
                none.setTextFill(Color.web("#484F58"));
                none.setPadding(new Insets(16));
                rowsBox.getChildren().add(none);
            } else {
                for (WindowManager.WindowEntry e : visible) rowsBox.getChildren().add(makeRow.apply(e));
            }
            subheader.setText(visible.size() + " of " + all.size() + " windows shown");
        };
        rebuildRows.run();
        search.textProperty().addListener((obs, o, n) -> rebuildRows.run());

        Button noBind = new Button("Don't bind to any window");
        noBind.setMaxWidth(Double.MAX_VALUE);
        noBind.setStyle("-fx-background-color:#161B22;-fx-text-fill:#484F58;-fx-border-color:transparent;-fx-background-radius:0;-fx-padding:10 14 10 14;-fx-cursor:hand;-fx-font-family:Monospace;-fx-font-size:11;");
        noBind.setOnAction(e -> picker.close());

        VBox root = new VBox(0, header, subheader, search, scroll, noBind);
        root.setStyle("-fx-background-color: #0D1117;");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

        javafx.scene.Scene scene = new javafx.scene.Scene(root, 480, 460);
        scene.setFill(Color.web("#0D1117"));
        picker.setScene(scene);
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

        // Repulsion
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                GraphNode a = nodes.get(i), b = nodes.get(j);
                double dx = a.x-b.x, dy = a.y-b.y, dz = a.z-b.z;
                double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
                double f  = REPULSION / (dist * dist);
                double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
                a.fx+=fx; a.fy+=fy; a.fz+=fz;
                b.fx-=fx; b.fy-=fy; b.fz-=fz;
            }
        }

        // Attraction
        for (GraphEdge e : edges) {
            double dx = e.b.x-e.a.x, dy = e.b.y-e.a.y, dz = e.b.z-e.a.z;
            double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
            double f  = ATTRACTION * (dist - SPRING_LENGTH);
            double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
            e.a.fx+=fx; e.a.fy+=fy; e.a.fz+=fz;
            e.b.fx-=fx; e.b.fy-=fy; e.b.fz-=fz;
        }

        // Integrate — each node uses its own spawnDamping which relaxes to DAMPING
        for (GraphNode nd : nodes) {
            // Step spawn damping toward global DAMPING over SPAWN_DAMP_TICKS frames
            if (nd.spawnTick < SPAWN_DAMP_TICKS) {
                nd.spawnTick++;
                double progress = (double) nd.spawnTick / SPAWN_DAMP_TICKS; // 0 → 1
                nd.spawnDamping = SPAWN_DAMPING_INITIAL
                    + (SPAWN_DAMPING_TARGET - SPAWN_DAMPING_INITIAL) * progress;
            } else {
                nd.spawnDamping = SPAWN_DAMPING_TARGET;
            }

            nd.vx = (nd.vx + nd.fx) * nd.spawnDamping;
            nd.vy = (nd.vy + nd.fy) * nd.spawnDamping;
            nd.vz = (nd.vz + nd.fz) * nd.spawnDamping;

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
        // Pass the world rotation angles to each billboard so it can
        // counter-rotate by the inverse — keeping labels facing the camera.
        double ay = rotateY.getAngle();
        double ax = rotateX.getAngle();
        for (GraphNode nd : nodes) {
            nd.applyPosition();
            nd.updateBillboard(ay, ax);
        }
        for (GraphEdge edge : edges) edge.update();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ══════════════════════════════════════════════════════════════════

    private Region divider() {
        Region r = new Region(); r.setPrefHeight(1);
        r.setStyle("-fx-background-color: #30363D;"); return r;
    }

    private Label sectionHeader(String text) {
        Label l = new Label(text);
        l.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        l.setTextFill(Color.web("#8B949E")); return l;
    }

    private Button styledButton(String text, String bg, String bgHover) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        String base  = ("-fx-background-color:%s;-fx-text-fill:#E6EDF3;"
                      + "-fx-background-radius:6;-fx-cursor:hand;"
                      + "-fx-padding:10 14 10 14;").formatted(bg);
        String hover = base.replace(bg, bgHover);
        btn.setStyle(base);
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e  -> btn.setStyle(base));
        return btn;
    }
}
