Aug 16, 2026
beta43

# Introduced two major workflow features: an "Add Active Window" capture shortcut for effortless workspace integration, and a HUD Assistant widget—a secondary, floating 3D viewport utility designed for seamless multi-angle control and navigation.

Key Architectural & Feature Milestones

1. Instant Active Window CaptureZ-Order Index Targeting: Extended OS window binding (EnumWindows) to pull the window at Z-order index

 1. Because focusing the app UI pushes the app to index 0, the target window drops cleanly to index 1—capturing the user's previously active application without requiring a manual window-picker flow.
 
 2. Selection Snapshot Isolation: Captured the selected node context immediately before launching the thread process. This prevents state collisions or improper node linking if the user changes selections while the window query completes.
S
treamlined UI Integration: Integrated the action directly into the primary sidebar control panel, automatically applying the OS binding badge (⚡) and updating inspection controls upon node creation.

2. Synchronized Secondary HUD ViewportParallel Scene Graph Architecture: Implemented a twin scene graph pattern (world3D and assistWorld) to bypass the strict single-parent constraint of JavaFX scene graphs while providing a full secondary viewport:

Shared Properties & Transforms: Bound spatial transforms (translateX, translateY, translateZ) and rotation matrices (rotateX, rotateY) between viewports so orbit, pan, and zoom operate synchronously across both windows.

Proxy Node Mirroring: Graph creation pipelines (GraphNode, GraphEdge) instantiate lightweight proxy objects mapped directly to the secondary viewport.

Shared Materials: Shared PhongMaterial instances across main and proxy shapes ensure selection highlights, window-bound glow states, and deletion indicators render synchronously without duplicate state logic.

3. Aspect Ratio & Perspective Preservation

Derived Window Dimensions: Solved camera projection stretching by programmatically deriving the secondary viewport height ($AH$) directly from the primary viewport aspect ratio ($\frac{\text{SCENE\_H}}{\text{Main Viewport Width}}$):$$AH = AW \times \frac{\text{SCENE\_H}}{\text{Main Viewport Width}}$$

Native Geometry Rendering:

 Eliminates projection/FOV hacks, keeping 3D node spheres perfectly spherical and visually consistent across both primary and utility viewports regardless of window scaling.

Claude's response:

Clean feature — reuses almost everything that already exists. Let me read the spawn and bind methods quickly to make sure I wire it correctly.

Read spawnNewNode and bindNode
Read spawnNewNode and bindNode

Good. Two edits: add the button to the sidebar, add the captureActiveWindow method.

Edited 3 files
Edited 3 files

Now add the captureActiveWindow method right before spawnNewNode:

Edited a file, read a file
Edited a file, read a file

Done. Here's the breakdown.

Why index 1: EnumWindows enumerates windows in Z-order — frontmost first. When the user clicks the "Add Active Window" button, this app moves to the front (index 0). The window they were just using drops to index 1. That's exactly the previously-active window.

previousSelection snapshot: The currently selected node is captured as a local variable before the background thread starts. If the user clicks elsewhere while the thread is running, selectedNode would have changed — the snapshot ensures we link to the node the user intended.

No picker needed: Unlike the manual bind flow, here we know exactly which window to bind — the user's intent is unambiguous. The node is bound immediately with the ⚡ badge, skipping the picker entirely.

selectNode(newNode) reuse: This calls the exact existing method, which handles the visual state transition (red highlight), enables the rename/delete/bind buttons, populates the rename field, and triggers auto-focus if it's on — all for free.
There's a duplicate — the method got inserted twice. Let me find and remove the extra one.

Ran a command, viewed a file
Ran a command, viewed a file

The second one at line 923 is actually cleaner — it uses bindNode() properly. Let me remove the first one and keep the second.

Viewed a file, edited a file, read a file
Viewed a file, edited a file, read a file

Fixed — the first duplicate is gone. The kept version (previously at line 923) is the cleaner one that uses bindNode() properly instead of manually setting the hwnd fields.
The error is duplicate children added in buildSidebar — a node is being added to the VBox twice. Let me check the children list.

Read the sidebar children addAll call
Read the sidebar children addAll call

There it is — captureBtn appears twice on line 547.

Edited a file, read a file
Edited a file, read a file

Fixed. JavaFX's scene graph doesn't allow the same node instance to appear more than once in a parent's children list — captureBtn was listed twice, which threw the duplicate children error immediately on startup.
--HUD feature
Excellent feature — the shared world3D group is the key insight here. JavaFX allows the same Group to be rendered by multiple SubScenes as long as each has its own Camera. Let me read the current camera and rotation state to wire the bindings correctly.
Good. Now let me read the sidebar and start() to find the right insertion points.

Viewed 2 files
Viewed 2 files

Good. Three edits: add assistantStage field, add the Enable Assistant button to the sidebar, and add buildAssistantStage() method.

Edited 2 files
Edited 2 files

Now add buildAssistantStage() before startPhysicsLoop:

Edited a file, read a file
Edited a file, read a file

Here's the full technical breakdown.

How it works

Shared world3D — the same Group instance is passed into both SubScene constructors. JavaFX renders it into whichever SubScene owns it at that moment, but since it's shared via direct reference, all nodes, edges, physics positions, and billboard labels appear in both views automatically. No duplication of data or physics.

Separate camera — a Camera can only belong to one SubScene at a time, so the assistant gets its own PerspectiveCamera. The sync happens via property bindings:

java
assistCam.translateXProperty().bind(camera.translateXProperty());
assistCam.translateYProperty().bind(camera.translateYProperty());
assistCam.translateZProperty().bind(camera.translateZProperty());

These are live JavaFX bindings — the moment the user scrolls (changing camera.translateZ), the assistant camera updates on the same render pulse. Rotation and pan are already shared because rotateX, rotateY, and world3D.translateX/Y live on the shared world3D group, not on the camera.

Toggle behaviour — clicking "Enable Assistant" opens the widget and relabels the button "Assistant: ON". Clicking again closes it. The setOnCloseRequest handler nulls the reference if the user closes the window via the utility chrome, keeping the button state consistent.
Clear error — world3D is already set as root of another scene or subScene. JavaFX doesn't allow the same Group to be the root of two SubScenes simultaneously. The fix: wrap world3D in a new Group for the assistant SubScene so it's a different root node that still contains the same shared content.

Wrap world3D in a new Group for the assistant SubScene to avoid dual-root error
Wrap world3D in a new Group for the assistant SubScene to avoid dual-root error

But there's a second problem this will cause — world3D can only have one parent in the scene graph too. We need a different approach: instead of sharing world3D directly, we keep the main SubScene owning world3D, and for the assistant we create a read-only snapshot approach using a WritableImage that we redraw each frame. Actually, the cleanest correct solution is to use a SnapshotParameters live snapshot of the main SubScene rendered onto an ImageView in the assistant window, updated via the AnimationTimer.

Rewrite buildAssistantStage to use live snapshot of main SubScene instead of shared world3D
Rewrite buildAssistantStage to use live snapshot of main SubScene instead of shared world3D

Now add the assistSnapTimer field:

Add assistSnapTimer field
Add assistSnapTimer field

Also stop the timer when the assistant is closed via the button toggle:

Edited a file, read a file
Edited a file, read a file

The root cause was a fundamental JavaFX constraint: a Node can only have one parent, and a Group can only be the root of one SubScene. Neither wrapping nor sharing world3D directly can work around this.

The correct solution is a live snapshot mirror. Each frame the AnimationTimer calls rootLayout.snapshot() cropped to just the 3D viewport area, and the result is displayed in an ImageView in the assistant window. It's a pixel-perfect copy that updates at the same rate as the physics loop — rotation, pan, zoom, new nodes, new edges all appear instantly. The SnapshotParameters viewport crops out the sidebar so only the 3D view is mirrored.
The snapshot approach won't work for either of these fixes — you can't interact with a snapshot image, and aspect ratio correction on a pixel copy is just stretching. We need to go back to the real SubScene approach, but solve the dual-root problem correctly.

The actual fix: keep world3D owned by the main SubScene, and for the assistant create a parallel scene graph — a second Group that we keep manually synchronized with world3D's transforms (translateX/Y, rotateX/Y). The assistant's Group contains the same sphere/billboard/cylinder objects by... actually that still hits the one-parent rule.

The real correct solution: move world3D up so it has no parent SubScene directly — instead wrap it in a Group for each SubScene. Let me restructure this properly.

Read buildUI SubScene and world3D construction
Read buildUI SubScene and world3D construction

The solution is clear now. The main SubScene owns world3D as its root. For the assistant, we create a second independent scene graph — a Group assistWorld that is a sibling structure. Instead of sharing nodes (impossible due to single-parent rule), we keep the assistant's transforms bound to world3D's transforms so they always look the same. The assistant world is populated with lightweight proxy spheres/cylinders that we sync positionally each physics tick via the existing AnimationTimer.

Actually — the cleanest real solution used in production JavaFX apps is simpler: store the main SubScene as a field, then in the assistant use subScene.snapshot() ... but we already know that breaks interaction.

The only correct approach that allows real interaction is: give each SubScene its own independent scene graph, keep them in sync via the physics loop. Let me implement this properly:

Store mainSubScene as field and add assistWorld group
Store mainSubScene as field and add assistWorld group

Now update buildUI to store the SubScene and set up assistWorld with shared transforms and lights:

Update buildUI to store mainSubScene and set up assistWorld with shared transforms
Update buildUI to store mainSubScene and set up assistWorld with shared transforms

Now update addNodeAt to also add proxy sphere/billboard to assistWorld, and sync positions in syncSceneGraph. First add proxy fields to GraphNode:

Edited 2 files, viewed a file
Edited 2 files, viewed a file

Now update the GraphNode constructor to build the proxy objects, and update applyPosition and updateBillboard:

Viewed a file, edited a file
Viewed a file, edited a file

Now also add proxy edge cylinders to GraphEdge:

Add proxy cylinder to GraphEdge
Add proxy cylinder to GraphEdge

Now update GraphEdge.update() to sync the proxy cylinder:

Sync proxyCylinder in GraphEdge.update()
Sync proxyCylinder in GraphEdge.update()

Now update addNodeAt to add proxies to assistWorld and wire proxy sphere click handlers:

Add proxy nodes to assistWorld in addNodeAt
Add proxy nodes to assistWorld in addNodeAt

Update addEdge and removeEdge to handle proxy cylinders:

Edited 3 files, viewed 3 files
Now update deleteSelectedNode to remove proxy nodes from assistWorld, then rewrite buildAssistantStage with the real SubScene, correct aspect ratio, and mouse handlers:

Viewed a file, edited a file
Viewed a file, edited a file

Also sync proxy label text in applyRename:

Edited 2 files, viewed a file
Edited 2 files, viewed a file

Now rewrite buildAssistantStage with the real SubScene, correct aspect ratio, and full mouse handlers:

Rewrite buildAssistantStage with real SubScene, aspect ratio fix, and mouse handlers
Rewrite buildAssistantStage with real SubScene, aspect ratio fix, and mouse handlers

Clean up the now-unused assistSnapTimer field and snapshot imports:

Remove assistSnapTimer field — no longer needed
Remove assistSnapTimer field — no longer needed

Also remove the stale assistSnapTimer stop calls in the button and close handler:

Edited 2 files, read a file
Edited 2 files, read a file

Here's the full technical picture of what changed and why.

Architecture — parallel scene graphs

JavaFX's single-parent constraint means a node can only be in one scene graph. The solution is two independent scene graphs — world3D and assistWorld — that are kept in perfect sync:

Rotation — both Groups share the exact same Rotate objects (rotateX, rotateY). When the user drags in either window, the same Rotate transform updates and both views reflect it instantly.

Pan — assistWorld.translateXProperty().bind(world3D.translateXProperty()) and same for Y. Moving world3D automatically moves assistWorld.

Zoom — assistCam.translateZProperty().bind(camera.translateZProperty()). Scrolling in either window updates camera.translateZ, and assistCam follows via binding.

Node positions — applyPosition() now sets both sphere/billboard and proxySphere/proxyBillboard coordinates each physics tick.

Visual state — both the main sphere and proxy sphere share the same PhongMaterial instance, so selection highlights, bound glow, and delete-red appear in both views simultaneously with zero extra code.

Aspect ratio — the FOV is computed to match the main window's horizontal span projected into the assistant's narrower canvas, keeping spheres perfectly round instead of oval.
The window dimensions need to match the main view's aspect ratio. The main viewport is SCENE_W - 238 wide and SCENE_H tall — so 862 × 700. Let me compute the right assistant dimensions and update the stage size to match.

Viewed 3 files
Viewed 3 files

Simple fix — scale the assistant to a compact width and derive the height from the main aspect ratio, then remove all the FOV correction math since the aspect ratio will be correct by construction.

Fix assistant dimensions to match main aspect ratio, remove FOV hack
Fix assistant dimensions to match main aspect ratio, remove FOV hack

Now update the Scene and stage size to use the derived dimensions:

Edited a file, read a file
Edited a file, read a file

The fix is straightforward — instead of fighting the aspect ratio with FOV math, just make the window the right shape from the start. AH is derived directly from AW using the main viewport's ratio:

java
int AH = (int)(AW * (double) SCENE_H / mainVpW);

So with AW = 500 and a main viewport of 862 × 700, AH = 500 × 700/862 ≈ 406px. The assistant window is now 500 × 442 (including the 36px button), which is the exact same proportions as the main 3D view. Spheres will be round with no projection correction needed, and the whole widget is still compact enough to sit alongside other apps.

If you want it smaller or larger, just change AW — the height follows automatically.
The assistant window will now be 400 × ~361px (plus the 36px button), keeping the same aspect ratio as the main viewport.
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
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * 3D Force-Directed Workspace Graph — Phase 1 Baseline
 *
 * COMPILE & RUN:
 *   Maven: mvn javafx:run
 *   Direct (Linux/Mac):
 *     export FX=/path/to/javafx-sdk/lib
 *     javac --module-path $FX --add-modules javafx.controls \
 *           -cp ".:jna-5.14.0.jar:jna-platform-5.14.0.jar" WorkspaceGraph3D.java
 *     java  --module-path $FX --add-modules javafx.controls \
 *           -cp ".:jna-5.14.0.jar:jna-platform-5.14.0.jar" WorkspaceGraph3D
 *   Direct (Windows):
 *     set FX=C:\javafx-sdk\lib
 *     javac --module-path %FX% --add-modules javafx.controls ^
 *           -cp ".;jna-5.14.0.jar;jna-platform-5.14.0.jar" WorkspaceGraph3D.java
 *     java  --module-path %FX% --add-modules javafx.controls ^
 *           -cp ".;jna-5.14.0.jar;jna-platform-5.14.0.jar" WorkspaceGraph3D
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
    private static final double CAMERA_DISTANCE = 550.0;
    private static final double ZOOM_SPEED      = 20.0;
    private static final double ZOOM_MIN        = 40.0;
    private static final double ROTATION_SPEED  = 0.4;
    private static final double PAN_SPEED       = 0.9;
    private static final double NODE_RADIUS     = 18.0;
    private static final int    SCENE_W         = 1100;
    private static final int    SCENE_H         = 700;

    private static final double SPAWN_DAMPING_INITIAL = 0.98;
    private static final double SPAWN_DAMPING_TARGET  = DAMPING;
    private static final int    SPAWN_DAMP_TICKS      = 120;
    private static final double GLIDE_SPEED           = 0.09;

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
                ? Native.load("user32", User32Extended.class, W32APIOptions.DEFAULT_OPTIONS)
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
        double x, y, z, vx, vy, vz, fx, fy, fz;
        double spawnDamping = SPAWN_DAMPING_INITIAL;
        int    spawnTick    = 0;

        Sphere sphere;
        Group  billboard;
        Text   label;

        final Rotate billboardRotY = new Rotate(0, Rotate.Y_AXIS);
        final Rotate billboardRotX = new Rotate(0, Rotate.X_AXIS);

        // Assistant mirror — separate node instances, same visual appearance
        Sphere proxySphere;
        Group  proxyBillboard;
        Text   proxyLabel;
        final Rotate proxyBBRotY = new Rotate(0, Rotate.Y_AXIS);
        final Rotate proxyBBRotX = new Rotate(0, Rotate.X_AXIS);

        PhongMaterial material;
        String name;
        Color  baseColor;

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

            // ── Proxy (assistant mirror) ──────────────────────────────
            // Separate node instances sharing the same material so visual
            // state changes (selection highlight, bound glow) show in both.
            proxySphere = new Sphere(NODE_RADIUS);
            proxySphere.setMaterial(material);  // shared material = shared visual state

            proxyLabel = new Text(name);
            proxyLabel.setFont(Font.font("Monospace", FontWeight.BOLD, 13));
            proxyLabel.setFill(Color.WHITE);
            proxyLabel.setStroke(Color.color(0, 0, 0, 0.55));
            proxyLabel.setStrokeWidth(0.6);
            proxyLabel.setTranslateX(NODE_RADIUS + 4);
            proxyLabel.setTranslateY(-5);

            proxyBillboard = new Group(proxyLabel);
            proxyBillboard.getTransforms().addAll(proxyBBRotY, proxyBBRotX);
        }

        void applyPosition() {
            sphere.setTranslateX(x);       sphere.setTranslateY(y);       sphere.setTranslateZ(z);
            billboard.setTranslateX(x);    billboard.setTranslateY(y);    billboard.setTranslateZ(z);
            // Sync proxy positions
            proxySphere.setTranslateX(x);  proxySphere.setTranslateY(y);  proxySphere.setTranslateZ(z);
            proxyBillboard.setTranslateX(x); proxyBillboard.setTranslateY(y); proxyBillboard.setTranslateZ(z);
        }

        void updateBillboard(double ay, double ax) {
            billboardRotY.setAngle(-ay);  billboardRotX.setAngle(-ax);
            proxyBBRotY.setAngle(-ay);    proxyBBRotX.setAngle(-ax);
        }

        /** Sync the proxy label text to match the main label. */
        void syncProxyLabel() {
            proxyLabel.setText(label.getText());
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
        Cylinder  proxyCylinder;

        GraphEdge(GraphNode a, GraphNode b) {
            this.a = a; this.b = b;
            PhongMaterial mat = new PhongMaterial();
            mat.setDiffuseColor(Color.color(0.5, 0.7, 1.0, 0.4));

            cylinder = new Cylinder(1.5, 1);
            cylinder.setMaterial(mat);

            proxyCylinder = new Cylinder(1.5, 1);
            proxyCylinder.setMaterial(mat);  // shared material
        }

        void update() {
            double dx = b.x-a.x, dy = b.y-a.y, dz = b.z-a.z;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < 0.001) return;

            Point3D yAxis = new Point3D(0, 1, 0);
            Point3D dir   = new Point3D(dx/dist, dy/dist, dz/dist);
            Point3D axis  = yAxis.crossProduct(dir);
            double  angle = Math.toDegrees(Math.acos(
                                Math.max(-1, Math.min(1, yAxis.dotProduct(dir)))));
            Rotate rot = new Rotate(angle, axis);

            for (Cylinder cyl : new Cylinder[]{ cylinder, proxyCylinder }) {
                cyl.setHeight(dist);
                cyl.setTranslateX((a.x+b.x)/2.0);
                cyl.setTranslateY((a.y+b.y)/2.0);
                cyl.setTranslateZ((a.z+b.z)/2.0);
                cyl.getTransforms().setAll(rot);
            }
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

    private final Group             world3D     = new Group();
    private final Group             assistWorld = new Group(); // mirrors world3D transforms
    private final Rotate            rotateX     = new Rotate(20,  Rotate.X_AXIS);
    private final Rotate            rotateY     = new Rotate(-30, Rotate.Y_AXIS);
    private final PerspectiveCamera camera      = new PerspectiveCamera(true);
    private final PerspectiveCamera assistCam   = new PerspectiveCamera(true);
    private SubScene                mainSubScene;
    private double mouseX, mouseY;
    private int    spawnIndex = 0;

    private boolean   linkModeActive = false;
    private GraphNode linkSource     = null;
    private GraphNode selectedNode   = null;

    private boolean autoFocusEnabled = false;
    private boolean sideViewEnabled  = false;
    private boolean glideActive      = false;
    private double  glideTargetX     = 0;
    private double  glideTargetY     = 0;
    private double  glideTargetRX    = 0;
    private double  glideTargetRY    = 0;

    private Label                           statusLabel;
    private Button                          linkBtn;
    private Button                          deleteBtn;
    private Button                          bindBtn;
    private Button                          renameBtn;
    private Button                          captureBtn;
    private javafx.scene.control.TextField  nameField;
    private javafx.scene.control.TextField  renameField;
    private BorderPane                      rootLayout;
    private javafx.scene.control.ScrollPane sidebarPanel;
    private Stage                           assistantStage;

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Workspace Graph — Phase 1");
        BorderPane root = buildUI();

        addNodeAt("Root", Color.web("#58A6FF"), 0, 0, 0);
        startPhysicsLoop();

        javafx.scene.Scene scene = new javafx.scene.Scene(root, SCENE_W, SCENE_H,
                                                           Color.web("#0D1117"));
        scene.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DELETE, BACK_SPACE -> deleteSelectedNode();
                default -> {}
            }
        });

        stage.setResizable(false);
        stage.setScene(scene);
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        mainSubScene = new SubScene(world3D, SCENE_W - 238, SCENE_H, true,
                                    SceneAntialiasing.BALANCED);
        mainSubScene.setFill(Color.TRANSPARENT);

        camera.setNearClip(0.1);
        camera.setFarClip(4000);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        mainSubScene.setCamera(camera);

        // Share the same Rotate transforms on world3D — rotation,
        // pan and zoom changes propagate to assistWorld via bindings
        // set up below.
        world3D.getTransforms().addAll(rotateX, rotateY);

        AmbientLight ambient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight key  = new PointLight(Color.color(0.9, 0.95, 1.0));
        key.setTranslateX(-200); key.setTranslateY(-300); key.setTranslateZ(-200);
        PointLight fill = new PointLight(Color.color(0.2, 0.3, 0.5));
        fill.setTranslateX(200); fill.setTranslateY(200); fill.setTranslateZ(100);
        world3D.getChildren().addAll(ambient, key, fill);

        // assistWorld uses the SAME Rotate transform objects as world3D
        // so rotation is automatically shared. Pan and zoom are bound below.
        assistWorld.getTransforms().addAll(rotateX, rotateY);

        // Duplicate lights for assistWorld
        AmbientLight aAmbient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight   aKey     = new PointLight(Color.color(0.9, 0.95, 1.0));
        aKey.setTranslateX(-200); aKey.setTranslateY(-300); aKey.setTranslateZ(-200);
        PointLight   aFill    = new PointLight(Color.color(0.2, 0.3, 0.5));
        aFill.setTranslateX(200); aFill.setTranslateY(200); aFill.setTranslateZ(100);
        assistWorld.getChildren().addAll(aAmbient, aKey, aFill);

        // Bind assistWorld pan to world3D pan so right-drag mirrors
        assistWorld.translateXProperty().bind(world3D.translateXProperty());
        assistWorld.translateYProperty().bind(world3D.translateYProperty());

        // assistCam zoom bound to main camera
        assistCam.setNearClip(0.1);
        assistCam.setFarClip(4000);
        assistCam.translateZProperty().bind(camera.translateZProperty());

        StackPane viewport = new StackPane(mainSubScene);
        viewport.setStyle("-fx-background-color: #0D1117;");
        attachMouseHandlers(viewport);

        rootLayout = new BorderPane();
        rootLayout.setStyle("-fx-background-color: #0D1117;");
        rootLayout.setCenter(viewport);
        sidebarPanel = buildSidebar();
        rootLayout.setRight(sidebarPanel);
        return rootLayout;
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

        captureBtn = styledButton("⚡  Add Active Window", "#1A3A2A", "#1F4D38");
        captureBtn.setOnAction(e -> captureActiveWindow());

        Button captureBtn = styledButton("⚡  Add Active Window", "#1C3A5E", "#1F4E79");
        captureBtn.setOnAction(e -> captureActiveWindow());

        Region divDel = divider();
        Label delTitle = sectionHeader("SELECTED NODE");

        deleteBtn = styledButton("🗑  Delete Selected", "#6E1010", "#9B1C1C");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(e -> deleteSelectedNode());

        bindBtn = styledButton("🔗  Bind Window…", "#1C3A5E", "#1F4E79");
        bindBtn.setDisable(true);
        bindBtn.setOnAction(e -> {
            if (selectedNode == null) return;
            if (!WindowManager.isAvailable()) {
                setStatus("Window binding\nrequires Windows.");
                return;
            }
            GraphNode target = selectedNode;
            Thread scanner = new Thread(() -> {
                List<WindowManager.WindowEntry> all = WindowManager.listAllWindows();
                javafx.application.Platform.runLater(() -> showWindowPicker(target, all));
            }, "winscan-bind");
            scanner.setDaemon(true);
            scanner.start();
        });

        // ── Rename section ───────────────────────────────────────────
        Label renameLabel = new Label("Rename Node");
        renameLabel.setFont(Font.font("Monospace", 11));
        renameLabel.setTextFill(Color.web("#8B949E"));

        renameField = new javafx.scene.control.TextField();
        renameField.setPromptText("Select a node first");
        renameField.setMaxWidth(Double.MAX_VALUE);
        renameField.setDisable(true);
        renameField.setStyle("""
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
        renameField.setOnAction(e -> applyRename());

        renameBtn = styledButton("✏  Apply Name", "#2D2A1E", "#3D3A2E");
        renameBtn.setDisable(true);
        renameBtn.setOnAction(e -> applyRename());

        Region div3 = divider();
        Label linkTitle = sectionHeader("LINK MODE");
        linkBtn = styledButton("🔗  Link Mode: OFF", "#21262D", "#30363D");
        linkBtn.setOnAction(e -> toggleLinkMode());

        statusLabel = new Label("Enable Link Mode,\nthen click two nodes\nto connect them.\n\n⚡ = bound to live\nOS window.\n2×click to focus it.");
        statusLabel.setFont(Font.font("Monospace", 10));
        statusLabel.setTextFill(Color.web("#8B949E"));
        statusLabel.setWrapText(true);

        Region divFocus = divider();
        Label focusTitle = sectionHeader("CAMERA");

        javafx.scene.control.ToggleButton autoFocusBtn =
            new javafx.scene.control.ToggleButton("🎯  Auto-Focus: OFF");
        autoFocusBtn.setMaxWidth(Double.MAX_VALUE);
        autoFocusBtn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        String afOff = "-fx-background-color:#21262D;-fx-text-fill:#8B949E;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:10 14 10 14;";
        String afOn  = "-fx-background-color:#0D419D;-fx-text-fill:#58A6FF;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:10 14 10 14;";
        autoFocusBtn.setStyle(afOff);
        autoFocusBtn.selectedProperty().addListener((obs, wasOn, isOn) -> {
            autoFocusEnabled = isOn;
            autoFocusBtn.setText(isOn ? "🎯  Auto-Focus: ON" : "🎯  Auto-Focus: OFF");
            autoFocusBtn.setStyle(isOn ? afOn : afOff);
            if (!isOn) glideActive = false;
        });

        Label focusHint = new Label("When ON, clicking a\nnode glides the camera\nto centre it.\nDrag to cancel glide.");
        focusHint.setFont(Font.font("Monospace", 10));
        focusHint.setTextFill(Color.web("#484F58"));
        focusHint.setWrapText(true);

        javafx.scene.control.ToggleButton sideViewBtn =
            new javafx.scene.control.ToggleButton("↔  Side View: OFF");
        sideViewBtn.setMaxWidth(Double.MAX_VALUE);
        sideViewBtn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        String svOff = "-fx-background-color:#21262D;-fx-text-fill:#8B949E;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:10 14 10 14;";
        String svOn  = "-fx-background-color:#0D419D;-fx-text-fill:#58A6FF;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:10 14 10 14;";
        sideViewBtn.setStyle(svOff);
        sideViewBtn.selectedProperty().addListener((obs, wasOn, isOn) -> {
            sideViewEnabled = isOn;
            sideViewBtn.setText(isOn ? "↔  Side View: ON" : "↔  Side View: OFF");
            sideViewBtn.setStyle(isOn ? svOn : svOff);
        });

        Button assistantBtn = styledButton("🤖  Enable Assistant", "#1A1A3A", "#252550");
        assistantBtn.setOnAction(e -> {
            if (assistantStage == null || !assistantStage.isShowing()) {
                assistantStage = buildAssistantStage();
                assistantStage.show();
                assistantBtn.setText("🤖  Assistant: ON");
            } else {
                assistantStage.close();
                assistantStage = null;
                assistantBtn.setText("🤖  Enable Assistant");
            }
        });

        sidebar.getChildren().addAll(
            title,
            div1, ctrlTitle, grid,
            div2, spawnTitle, nameLabel, nameField, spawnBtn, captureBtn,
            divDel, delTitle, deleteBtn, bindBtn,
            renameLabel, renameField, renameBtn,
            div3, linkTitle, linkBtn, statusLabel,
            divFocus, focusTitle, autoFocusBtn, focusHint, sideViewBtn,
            divider(), sectionHeader("WORKSPACE"),
            buildSaveLoadSection(),
            divider(), sectionHeader("ASSISTANT"),
            assistantBtn
        );

        javafx.scene.control.ScrollPane scroll =
            new javafx.scene.control.ScrollPane(sidebar);
        scroll.setPrefWidth(238);
        scroll.setMinWidth(238);
        scroll.setMaxWidth(238);
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
        bindBtn.setDisable(false);
        renameField.setDisable(false);
        renameField.setText(node.name);
        renameBtn.setDisable(false);
        if (autoFocusEnabled) startGlideTo(node);
    }

    private void clearSelection() {
        if (selectedNode != null) {
            selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
            selectedNode = null;
        }
        deleteBtn.setDisable(true);
        bindBtn.setDisable(true);
        renameField.setDisable(true);
        renameField.clear();
        renameBtn.setDisable(true);
    }

    /**
     * Applies the text in renameField to the selected node.
     * Only the display name and billboard label change — the node
     * object reference, physics state, and edge connections are untouched.
     */
    private void applyRename() {
        if (selectedNode == null) return;
        String newName = renameField.getText().trim();
        if (newName.isEmpty()) return;

        selectedNode.name = newName;
        selectedNode.label.setText(selectedNode.isBound() ? newName + " ⚡" : newName);
        selectedNode.syncProxyLabel();
    }

    private void deleteSelectedNode() {
        if (selectedNode == null) return;
        GraphNode target = selectedNode;

        List<GraphEdge> toRemove = new ArrayList<>();
        for (GraphEdge e : edges)
            if (e.a == target || e.b == target) toRemove.add(e);
        for (GraphEdge e : toRemove) {
            world3D.getChildren().remove(e.cylinder);
            assistWorld.getChildren().remove(e.proxyCylinder);
            edges.remove(e);
        }

        if (linkSource == target) {
            linkSource = null;
            if (linkModeActive) setStatus("Click a node to\nselect it first.");
        }

        world3D.getChildren().remove(target.sphere);
        world3D.getChildren().remove(target.billboard);
        assistWorld.getChildren().remove(target.proxySphere);
        assistWorld.getChildren().remove(target.proxyBillboard);
        nodes.remove(target);
        selectedNode = null;
        deleteBtn.setDisable(true);
        bindBtn.setDisable(true);
        renameField.setDisable(true);
        renameField.clear();
        renameBtn.setDisable(true);
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
        assistWorld.getChildren().remove(edge.proxyCylinder);
    }

    private void clearLinkSelection() {
        if (linkSource != null) { linkSource.setHighlight(false); linkSource = null; }
    }

    private void setStatus(String text) { statusLabel.setText(text); }

    // ══════════════════════════════════════════════════════════════════
    //  AUTO-FOCUS GLIDE
    // ══════════════════════════════════════════════════════════════════

    private void startGlideTo(GraphNode node) {
        if (sideViewEnabled) {
            glideTargetX  = -node.z;
            glideTargetY  = -node.y;
            glideTargetRY = 90.0;
        } else {
            glideTargetX  = -node.x;
            glideTargetY  = -node.y;
            glideTargetRY = 0.0;
        }
        glideTargetRX = 0;
        glideActive   = true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MOUSE HANDLERS
    // ══════════════════════════════════════════════════════════════════

    private void attachMouseHandlers(StackPane viewport) {
        viewport.setOnMousePressed(e -> { mouseX = e.getSceneX(); mouseY = e.getSceneY(); });

        viewport.setOnMouseDragged(e -> {
            glideActive = false;
            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX(); mouseY = e.getSceneY();

            if (e.isPrimaryButtonDown()) {
                if (linkModeActive) return;
                rotateY.setAngle(rotateY.getAngle() - dx * ROTATION_SPEED);
                rotateX.setAngle(rotateX.getAngle() - dy * ROTATION_SPEED);
            } else if (e.isSecondaryButtonDown()) {
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
            newZ = Math.min(-ZOOM_MIN, newZ);
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
        assistWorld.getChildren().addAll(node.proxySphere, node.proxyBillboard);

        // Main sphere click handler
        node.sphere.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && node.isBound()) {
                Thread t = new Thread(() -> WindowManager.focusWindow(node.hwnd),
                                      "winfocus-" + node.name);
                t.setDaemon(true); t.start();
            } else if (e.getClickCount() == 1) {
                handleSphereClick(node);
            }
            e.consume();
        });

        // Proxy sphere click handler — same behaviour as main
        node.proxySphere.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && node.isBound()) {
                Thread t = new Thread(() -> WindowManager.focusWindow(node.hwnd),
                                      "winfocus-proxy-" + node.name);
                t.setDaemon(true); t.start();
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
        assistWorld.getChildren().add(edge.proxyCylinder);
    }

    // ══════════════════════════════════════════════════════════════════
    //  SPAWN WITH WINDOW PICKER
    // ══════════════════════════════════════════════════════════════════

    /**
     * Captures the window at index 1 of the EnumWindows list — the
     * application that was active immediately before this one took focus.
     *
     * Index 0 is this app itself (or the system shell).
     * Index 1 is the previously focused window — the one the user just
     * switched away from to click this button.
     *
     * Steps:
     *   1. Enumerate all visible windows on a background thread.
     *   2. Take entry at index 1 (guard against empty list).
     *   3. Spawn a new node using the window's title as its name.
     *   4. Bind the node to that window's HWND immediately.
     *   5. If a node was selected before clicking, link them.
     *   6. Select the new node using the existing selectNode() method.
     */
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

    /**
     * Captures the window at index 1 of the EnumWindows list — the
     * application that was active immediately before our app took focus —
     * spawns a node for it, binds it, links it to the current selection
     * if one exists, and selects the new node.
     *
     * Index 0 is our own application window (it appears first because
     * EnumWindows enumerates in Z-order, foreground first).
     * Index 1 is therefore the previously focused window.
     *
     * Run on a background thread so EnumWindows never stalls the FX pulse.
     */
    private void captureActiveWindow() {
        if (!WindowManager.isAvailable()) {
            setStatus("Window capture\nrequires Windows.");
            return;
        }

        GraphNode previousSelection = selectedNode;

        Thread t = new Thread(() -> {
            List<WindowManager.WindowEntry> all = WindowManager.listAllWindows();

            javafx.application.Platform.runLater(() -> {
                if (all.size() < 2) {
                    setStatus("No previous window\ndetected.");
                    return;
                }

                WindowManager.WindowEntry entry = all.get(1); // index 1 = prev foreground
                Color color = Color.web(SPAWN_COLORS[spawnIndex % SPAWN_COLORS.length]);
                spawnIndex++;

                // Spawn using the captured window title as the node name
                GraphNode newNode = addNode(entry.title(), color);

                // Bind directly — no picker needed, we already know the window
                bindNode(newNode, entry);

                // Link to the previously selected node if one existed
                if (previousSelection != null) {
                    addEdge(previousSelection, newNode);
                }

                // Select the new node using the existing selection method
                selectNode(newNode);
            });
        }, "capture-active-window");
        t.setDaemon(true);
        t.start();
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
    //  SAVE / LOAD
    // ══════════════════════════════════════════════════════════════════

    private VBox buildSaveLoadSection() {
        Button saveBtn = styledButton("💾  Export Workspace", "#1C3A5E", "#1F4E79");
        saveBtn.setOnAction(e -> saveGraph());
        Button loadBtn = styledButton("📂  Import Workspace", "#2D3A1E", "#3A4D28");
        loadBtn.setOnAction(e -> loadGraph());
        Label hint = new Label("Export saves nodes,\nlinks to a .json file.");
        hint.setFont(Font.font("Monospace", 10));
        hint.setTextFill(Color.web("#484F58"));
        hint.setWrapText(true);
        return new VBox(6, saveBtn, loadBtn, hint);
    }

    private void saveGraph() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export Workspace");
        fc.setInitialFileName("workspace.json");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = fc.showSaveDialog(null);
        if (file == null) return;

        java.util.Map<GraphNode, Integer> idMap = new java.util.IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) idMap.put(nodes.get(i), i);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            String hex = String.format("#%02X%02X%02X",
                (int)(n.baseColor.getRed()   * 255),
                (int)(n.baseColor.getGreen() * 255),
                (int)(n.baseColor.getBlue()  * 255));
            sb.append("    { \"id\": ").append(i)
              .append(", \"name\": ").append(jsonStr(n.name))
              .append(", \"color\": ").append(jsonStr(hex))
              .append(", \"x\": ").append(fmt(n.x))
              .append(", \"y\": ").append(fmt(n.y))
              .append(", \"z\": ").append(fmt(n.z))
              .append(" }");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  \"edges\": [\n");
        for (int i = 0; i < edges.size(); i++) {
            GraphEdge e = edges.get(i);
            sb.append("    { \"from\": ").append(idMap.get(e.a))
              .append(", \"to\": ").append(idMap.get(e.b)).append(" }");
            if (i < edges.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");

        try (java.io.FileWriter w = new java.io.FileWriter(file)) {
            w.write(sb.toString());
            setStatus("Saved to\n" + file.getName());
        } catch (java.io.IOException ex) {
            setStatus("Save failed:\n" + ex.getMessage());
        }
    }

    private void loadGraph() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Import Workspace");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = fc.showOpenDialog(null);
        if (file == null) return;

        String json;
        try {
            json = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (java.io.IOException ex) {
            setStatus("Load failed:\n" + ex.getMessage());
            return;
        }

        try {
            for (GraphEdge e : new ArrayList<>(edges)) removeEdge(e);
            for (GraphNode n : new ArrayList<>(nodes)) {
                world3D.getChildren().remove(n.sphere);
                world3D.getChildren().remove(n.billboard);
            }
            nodes.clear();
            clearSelection();
            clearLinkSelection();

            String nodesArr = between(json, "\"nodes\"", "]");
            List<String> nodeObjs = splitObjects(nodesArr);
            List<GraphNode> loaded = new ArrayList<>();

            for (String obj : nodeObjs) {
                String name  = strVal(obj, "name");
                String color = strVal(obj, "color");
                double x     = dblVal(obj, "x");
                double y     = dblVal(obj, "y");
                double z     = dblVal(obj, "z");
                GraphNode node = addNodeAt(name, Color.web(color), x, y, z);
                node.spawnTick    = SPAWN_DAMP_TICKS;
                node.spawnDamping = DAMPING;
                loaded.add(node);
            }

            String edgesArr = between(json, "\"edges\"", "]");
            for (String obj : splitObjects(edgesArr)) {
                int from = (int) dblVal(obj, "from");
                int to   = (int) dblVal(obj, "to");
                if (from >= 0 && from < loaded.size() && to >= 0 && to < loaded.size())
                    addEdge(loaded.get(from), loaded.get(to));
            }

            setStatus("Loaded " + loaded.size() + " nodes,\n" + edges.size() + " edges.");
        } catch (Exception ex) {
            setStatus("Parse error:\n" + ex.getMessage());
        }
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static String fmt(double v) { return String.format("%.2f", v); }

    private static String between(String json, String startKey, String endToken) {
        int k = json.indexOf(startKey);
        if (k < 0) return "";
        int open = json.indexOf("[", k);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(open + 1, i); }
        }
        return "";
    }

    private static List<String> splitObjects(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) out.add(body.substring(start, i + 1)); }
        }
        return out;
    }

    private static String strVal(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = obj.indexOf(":", k);
        int q1 = obj.indexOf("\"", colon + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) { sb.append(obj.charAt(++i)); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static double dblVal(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return 0;
        int colon = obj.indexOf(":", k);
        int start = colon + 1;
        while (start < obj.length() && obj.charAt(start) == ' ') start++;
        int end = start;
        while (end < obj.length() && "0123456789.-".indexOf(obj.charAt(end)) >= 0) end++;
        if (start == end) return 0;
        return Double.parseDouble(obj.substring(start, end));
    }

    // ══════════════════════════════════════════════════════════════════
    //  ASSISTANT STAGE
    //
    //  A compact always-on-top widget that shares the exact same world3D
    //  Group as the main window.  Because JavaFX renders a Group into
    //  whichever SubScene it belongs to, and world3D is passed directly
    //  into both SubScene constructors, all nodes and edges appear in
    //  both views with zero duplication of physics or data.
    //
    //  CAMERA SYNC
    //  The assistant has its own PerspectiveCamera (required — a camera
    //  can only belong to one SubScene at a time).  We bind its translate
    //  and rotation properties directly to the main camera's properties
    //  and to the rotateX/rotateY Rotate transforms on world3D so the
    //  two views always show the exact same angle.
    //
    //  WHY BINDING WORKS HERE
    //  JavaFX property bindings are live — whenever the main camera's
    //  translateZProperty() changes (scroll), the assistant camera's
    //  translateZProperty() updates on the same pulse automatically.
    //  Same for world3D's translateX/Y (pan) and rotateX/Y (rotation).
    // ══════════════════════════════════════════════════════════════════

    private Stage buildAssistantStage() {
        Stage stage = new Stage(javafx.stage.StageStyle.UTILITY);
        stage.setTitle("Assistant");
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);

        // ── Dimensions — match main viewport aspect ratio ─────────────
        // Main viewport: (SCENE_W - 238) × SCENE_H  ≈  862 × 700  (ratio ~1.23)
        // We pick a compact width and derive height from the same ratio so
        // spheres are perfectly round without any FOV correction needed.
        int    AW        = 400;
        int    mainVpW   = SCENE_W - 238;
        int    AH        = (int)(AW * (double) SCENE_H / mainVpW);  // preserves ratio
        int    btnH      = 36;

        SubScene assistSubScene = new SubScene(assistWorld, AW, AH, true,
                                               SceneAntialiasing.BALANCED);
        assistSubScene.setFill(Color.web("#0D1117"));

        // No FOV correction needed — aspect ratio is already correct.
        assistCam.setFieldOfView(45.0);
        assistCam.setVerticalFieldOfView(true);
        assistSubScene.setCamera(assistCam);

        // ── Add Active Window macro button ────────────────────────────
        Button captureShortcut = styledButton("⚡  Add Active Window", "#1A3A2A", "#1F4D38");
        captureShortcut.setMaxWidth(Double.MAX_VALUE);
        captureShortcut.setOnAction(e -> captureActiveWindow());

        // ── Layout ───────────────────────────────────────────────────
        VBox root = new VBox(0, captureShortcut, assistSubScene);
        root.setStyle("-fx-background-color: #0D1117;");

        javafx.scene.Scene scene = new javafx.scene.Scene(root, AW, AH + btnH,
                                                           Color.web("#0D1117"));
        stage.setScene(scene);

        // ── Mouse handlers on the assistant SubScene ──────────────────
        // Left drag  → rotation (updates shared rotateX/rotateY → both views update)
        // Right drag → pan     (updates shared world3D.translateX/Y via binding)
        // Scroll     → zoom    (updates shared camera.translateZ via binding)
        final double[] aMouseX = {0}, aMouseY = {0};

        assistSubScene.setOnMousePressed(e -> {
            aMouseX[0] = e.getSceneX(); aMouseY[0] = e.getSceneY();
        });

        assistSubScene.setOnMouseDragged(e -> {
            glideActive = false;
            double dx = e.getSceneX() - aMouseX[0];
            double dy = e.getSceneY() - aMouseY[0];
            aMouseX[0] = e.getSceneX(); aMouseY[0] = e.getSceneY();

            if (e.isPrimaryButtonDown() && !linkModeActive) {
                // Rotation — updates shared rotateX/Y so main view mirrors too
                rotateY.setAngle(rotateY.getAngle() - dx * ROTATION_SPEED);
                rotateX.setAngle(rotateX.getAngle() - dy * ROTATION_SPEED);
            } else if (e.isSecondaryButtonDown()) {
                // Pan — world3D.translateX/Y; assistWorld is bound to it
                world3D.setTranslateX(world3D.getTranslateX() + dx * PAN_SPEED);
                world3D.setTranslateY(world3D.getTranslateY() + dy * PAN_SPEED);
            }
        });

        assistSubScene.setOnScroll(e -> {
            double newZ = camera.getTranslateZ() + e.getDeltaY() * ZOOM_SPEED / 40.0;
            newZ = Math.min(-ZOOM_MIN, newZ);
            camera.setTranslateZ(newZ); // assistCam.translateZ is bound to this
        });

        stage.setOnCloseRequest(e -> assistantStage = null);

        return stage;
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

        for (GraphEdge e : edges) {
            double dx = e.b.x-e.a.x, dy = e.b.y-e.a.y, dz = e.b.z-e.a.z;
            double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
            double f  = ATTRACTION * (dist - SPRING_LENGTH);
            double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
            e.a.fx+=fx; e.a.fy+=fy; e.a.fz+=fz;
            e.b.fx-=fx; e.b.fy-=fy; e.b.fz-=fz;
        }

        for (GraphNode nd : nodes) {
            if (nd.spawnTick < SPAWN_DAMP_TICKS) {
                nd.spawnTick++;
                double progress = (double) nd.spawnTick / SPAWN_DAMP_TICKS;
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

    private void syncSceneGraph() {
        if (glideActive) {
            double curX  = world3D.getTranslateX();
            double curY  = world3D.getTranslateY();
            double curRX = rotateX.getAngle();
            double curRY = rotateY.getAngle();

            double newX  = curX  + (glideTargetX  - curX)  * GLIDE_SPEED;
            double newY  = curY  + (glideTargetY  - curY)  * GLIDE_SPEED;
            double newRX = curRX + (glideTargetRX - curRX) * GLIDE_SPEED;
            double newRY = curRY + (glideTargetRY - curRY) * GLIDE_SPEED;

            world3D.setTranslateX(newX);
            world3D.setTranslateY(newY);
            rotateX.setAngle(newRX);
            rotateY.setAngle(newRY);

            if (Math.abs(glideTargetX  - newX)  < 0.4  &&
                Math.abs(glideTargetY  - newY)  < 0.4  &&
                Math.abs(glideTargetRX - newRX) < 0.05 &&
                Math.abs(glideTargetRY - newRY) < 0.05) {
                world3D.setTranslateX(glideTargetX);
                world3D.setTranslateY(glideTargetY);
                rotateX.setAngle(glideTargetRX);
                rotateY.setAngle(glideTargetRY);
                glideActive = false;
            }
        }

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
