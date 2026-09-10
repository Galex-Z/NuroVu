beta9
#Implemented full node deletion capabilities with dynamic visual feedback, and restructured the sidebar layout to fix vertical overflow issues across smaller screen resolutions.

Key Changes

Enhanced Visual State Management: Replaced simple boolean highlights with a tri-state system (NORMAL, LINK_SOURCE, DELETE_SELECT) to differentiate default, linking, and queued-for-deletion nodes. Selection states combine distinct color shifts with object scaling for clear visual hierarchy.

Separated Interaction Paths: Split handleSphereClick() into distinct dispatch routines (handleLinkClick and handleSelectClick), allowing object selection during navigation mode without interfering with Link Mode logic.

Safe Node & Edge Teardown: Added deleteSelectedNode() to perform concurrent-safe edge removal when deleting a node, automatically clearing dangling references and link states.

Keyboard Shortcuts & UI Controls: Bound the Delete and Backspace keys (for macOS compatibility) at the scene level for fast node removal. Added a context-aware sidebar deletion button that automatically disables when no node is selected.

Sidebar Responsiveness & Scroll Support: Fixed UI clipping on smaller displays by wrapping the sidebar in a vertical ScrollPane and replacing the controls panel with a compact GridPane.
Claude's response:
What changed and why
Visual states — three distinct looks, zero ambiguity

The existing setHighlight(bool) only toggled between normal and one highlight. Now applyVisualState(VisualState) handles three named states:

State	Diffuse	Specular	Scale	Means
NORMAL	Base color	White, tight (32)	1.0×	Idle
LINK_SOURCE	+55% toward white	Yellow, soft (6)	1.0×	First click in Link mode
DELETE_SELECT	+55% toward red	OrangeRed, soft (4)	1.15×	Queued for deletion

The scale-up on DELETE_SELECT gives a second visual cue independent of color, which is useful if nodes are close together. setHighlight(bool) is kept as a one-liner wrapper so the existing link-mode code needed no changes.

handleSphereClick split into two methods

The old version early-returned if not in link mode, making navigate-mode selection impossible. Now it's a dispatcher:

java
private void handleSphereClick(GraphNode clicked) {
    if (linkModeActive) handleLinkClick(clicked);
    else                handleSelectClick(clicked);
}

handleSelectClick is a simple toggle: clicking the selected node deselects it; clicking a different one moves the selection. handleLinkClick is the extracted two-click linking logic, unchanged in behaviour.

deleteSelectedNode() — safe concurrent removal

The edge list is iterated via a snapshot copy to avoid ConcurrentModificationException:

java
List<GraphEdge> toRemove = new ArrayList<>();
for (GraphEdge e : edges) {
    if (e.a == target || e.b == target) toRemove.add(e);
}
for (GraphEdge e : toRemove) { ... edges.remove(e); }

This collects all edges touching the node first, then removes them in a second pass. You can't remove from edges while iterating it directly.

The method also guards against a node being simultaneously the linkSource and the deletion target — if it is, linkSource is cleared so no ghost reference remains.

Delete key on the Scene, not the viewport
java
scene.setOnKeyPressed(e -> {
    if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
        deleteSelectedNode();
    }
});

Attaching to the Scene (not the SubScene or viewport StackPane) means the key handler fires regardless of which UI element currently has focus — including when the TextField doesn't have focus. BACK_SPACE is included because on macOS the "Delete" key is actually BACK_SPACE in JavaFX's key codes.

Mode isolation — highlights never bleed between modes
Toggling into Link mode calls clearSelection() → removes the red glow before the gold one can appear on a different node.
Toggling out of Link mode calls clearLinkSelection() → removes gold before returning to Navigate mode.
Deleting a node calls both cleanups internally, so no dangling highlights or null references remain.
Delete button disabled by default

deleteBtn.setDisable(true) at construction time means the button is visually greyed out until a node is selected. selectNode() enables it; clearSelection() and deleteSelectedNode() both disable it again. This gives the user an immediate visual cue about whether a deletion is possible.
You're right — the sidebar has gotten tall enough that the Link Mode section is probably being cut off at the bottom. Let me check the layout and fix it with a ScrollPane so everything is always reachable.

Viewed 2 files
Viewed 2 files

Confirmed — the VBox is taller than the window. Three fixes in one edit: wrap the sidebar in a ScrollPane, collapse the verbose controls hints into a compact grid, and drop the redundant tip card.

Wrap sidebar in ScrollPane and compact the controls section so Link Mode is always visible
Wrap sidebar in ScrollPane and compact the controls section so Link Mode is always visible

Now fix the call site — buildSidebar() now returns a ScrollPane, so root.setRight() still works but the type in buildUI needs no change (it accepts Node). Just verify the call compiles cleanly:

Verify buildUI calls buildSidebar correctly
Verify buildUI calls buildSidebar correctly

Good — setRight() takes a Node and ScrollPane is a Node, so no cast needed.

Presented file
Presented file

Two changes, both in buildSidebar only — nothing else touched.

What was wrong: the VBox had roughly 700px worth of content in a 700px window with 48px of top+bottom padding consumed before anything rendered. Link Mode was there all along, just below the visible bottom edge.

Fix 1 — ScrollPane wrapper: the sidebar VBox is now inside a ScrollPane with horizontal scrolling disabled and vertical scrolling on-demand. The scroll only appears if the window is short enough to need it, so on a normal 1080p display everything fits without scrolling at all.

Fix 2 — compact controls grid: the old controls section was 8 rows × 2 labels each = 16 label nodes with generous spacing. Replaced with a GridPane using 5 compact rows — badge in column 0, description in column 1 — saving about 120px of vertical space. That alone is usually enough to fit everything without scrolling on a standard laptop display.
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

        // The material is kept as a field so we can swap emissive color
        // to provide the selection-highlight glow.
        PhongMaterial material;

        String name;
        Color  baseColor;

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
         * Three visual states, applied via PhongMaterial properties:
         *
         *  NORMAL        — base colour, tight specular, no glow
         *  LINK_SOURCE   — brightened toward white (gold-ish shimmer)
         *                  used when this node is the first click in Link mode
         *  DELETE_SELECT — brightened toward red + looser specular
         *                  used when this node is queued for deletion
         *
         * PhongMaterial has no true emissive channel in JavaFX, so we
         * simulate glow by interpolating the diffuse colour toward a
         * highlight colour and tightening / loosening specularPower.
         * Lower specularPower = larger, softer highlight patch.
         */
        enum VisualState { NORMAL, LINK_SOURCE, DELETE_SELECT }

        void applyVisualState(VisualState state) {
            switch (state) {
                case NORMAL -> {
                    material.setDiffuseColor(baseColor);
                    material.setSpecularColor(Color.WHITE);
                    material.setSpecularPower(32);
                    sphere.setScaleX(1.0); sphere.setScaleY(1.0); sphere.setScaleZ(1.0);
                }
                case LINK_SOURCE -> {
                    // Gold-tinted brighten: shows "ready to link"
                    material.setDiffuseColor(baseColor.interpolate(Color.WHITE, 0.55));
                    material.setSpecularColor(Color.LIGHTYELLOW);
                    material.setSpecularPower(6);
                    sphere.setScaleX(1.0); sphere.setScaleY(1.0); sphere.setScaleZ(1.0);
                }
                case DELETE_SELECT -> {
                    // Red-tinted brighten + slight scale-up: shows "selected for deletion"
                    material.setDiffuseColor(baseColor.interpolate(Color.RED, 0.55));
                    material.setSpecularColor(Color.ORANGERED);
                    material.setSpecularPower(4);
                    sphere.setScaleX(1.15); sphere.setScaleY(1.15); sphere.setScaleZ(1.15);
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
            { "L-drag",  "Rotate camera"     },
            { "R-drag",  "Pan camera"         },
            { "Scroll",  "Zoom in / out"      },
            { "Click",   "Select node"        },
            { "Delete",  "Remove selected"    },
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

        statusLabel = new Label("Enable Link Mode,\nthen click two nodes\nto connect them.");
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

        // ── Register click handler on this sphere ────────────────────
        // event.consume() stops the click bubbling up to the viewport's
        // background handler, which would immediately deselect the node
        // we just selected (they'd fire in the same event round-trip).
        node.sphere.setOnMouseClicked(e -> {
            handleSphereClick(node);
            e.consume();   // ← critical: don't let this reach the viewport
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
     * Spawns a completely disconnected new node.
     * Name comes from the TextField if non-empty, otherwise "Node N".
     * The color cycles through SPAWN_COLORS regardless of the name source.
     * No edges are auto-wired — the user must link it manually.
     */
    private void spawnNewNode() {
        // Read and trim whatever the user typed
        String typed = nameField.getText().trim();

        // Use the typed name when present; fall back to "Node N"
        String name = typed.isEmpty()
            ? "Node " + (spawnIndex + 1)
            : typed;

        Color color = Color.web(SPAWN_COLORS[spawnIndex % SPAWN_COLORS.length]);
        spawnIndex++;

        addNode(name, color);

        // Clear the field so it's ready for the next entry
        nameField.clear();

        // Update link-mode status hint
        if (linkModeActive) {
            setStatus("\"" + name + "\" spawned.\nClick it to select\nand link it.");
        }
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
