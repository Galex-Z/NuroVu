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

    /** These 3 nodes appear at startup with NO edges between them. */
    private static final String[][] SEED_WINDOWS = {
        { "VS Code",  "#4EC9B0" },
        { "Chrome",   "#F6AE2D" },
        { "Terminal", "#A9B7C6" },
    };

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

        /** Light up the node to show it is the first selected node. */
        void setHighlight(boolean on) {
            material.setSpecularPower(on ? 4 : 32);
            material.setSpecularColor(on ? Color.WHITE : Color.WHITE);
            // Emissive is the cleanest way to show "selected" — it adds
            // self-illumination on top of the diffuse color so the node
            // visually glows even in areas not hit by the point light.
            material.setSelfIlluminationMap(null);  // clear any map
            // We simulate glow by brightening the diffuse dramatically:
            material.setDiffuseColor(on
                ? baseColor.interpolate(Color.WHITE, 0.55)
                : baseColor);
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

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Workspace Graph — Manual Linking");
        BorderPane root = buildUI();

        // Seed 3 disconnected nodes — NO edges created here
        for (String[] w : SEED_WINDOWS) addNode(w[0], Color.web(w[1]));

        startPhysicsLoop();

        Scene scene = new Scene(root, SCENE_W, SCENE_H, Color.web("#0D1117"));
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

    private VBox buildSidebar() {
        VBox sidebar = new VBox(14);
        sidebar.setPrefWidth(220);
        sidebar.setPadding(new Insets(24, 18, 24, 18));
        sidebar.setStyle("""
            -fx-background-color: #161B22;
            -fx-border-color: #30363D;
            -fx-border-width: 0 0 0 1;
            """);

        // ── Title ────────────────────────────────────────────────────
        Label title = new Label("Workspace\nGraph 3D");
        title.setFont(Font.font("Monospace", FontWeight.BOLD, 18));
        title.setTextFill(Color.web("#58A6FF"));

        Label subtitle = new Label("Manual Linking Edition");
        subtitle.setFont(Font.font("Monospace", 11));
        subtitle.setTextFill(Color.web("#8B949E"));

        // ── Controls help ────────────────────────────────────────────
        Region div1 = divider();
        Label ctrlTitle = sectionHeader("CONTROLS");
        VBox controls = new VBox(5);
        String[][] hints = {
            { "Navigate mode", "Drag → rotate camera" },
            { "Navigate mode", "Scroll → zoom in / out" },
            { "Link mode",     "Click node 1, then node 2" },
            { "Link mode",     "Click same edge → removes it" },
            { "Link mode",     "Click empty space → deselects" },
        };
        for (String[] h : hints) {
            Label tag = new Label(h[0]);
            tag.setFont(Font.font("Monospace", FontWeight.BOLD, 9));
            tag.setTextFill(Color.web("#58A6FF"));
            Label txt = new Label("  " + h[1]);
            txt.setFont(Font.font("Monospace", 11));
            txt.setTextFill(Color.web("#8B949E"));
            txt.setWrapText(true);
            controls.getChildren().addAll(tag, txt);
        }

        // ── Spawn button ─────────────────────────────────────────────
        Region div2 = divider();
        Button spawnBtn = styledButton("+ Spawn Window Node", "#238636", "#2EA043");
        spawnBtn.setOnAction(e -> spawnNewNode());

        // ── Link Mode toggle ─────────────────────────────────────────
        Region div3 = divider();
        Label linkTitle = sectionHeader("LINK MODE");

        linkBtn = styledButton("🔗  Link Mode: OFF", "#21262D", "#30363D");
        linkBtn.setOnAction(e -> toggleLinkMode());

        // Status label: guides the user through the two-click workflow
        statusLabel = new Label("Enable Link Mode to\nconnect nodes.");
        statusLabel.setFont(Font.font("Monospace", 11));
        statusLabel.setTextFill(Color.web("#8B949E"));
        statusLabel.setWrapText(true);

        // ── Instruction card ─────────────────────────────────────────
        Region div4 = divider();
        Label tip = new Label(
            "💡 Nodes start fully\ndisconnected.\nUse Link Mode to\nbuild your graph.");
        tip.setFont(Font.font("Monospace", 11));
        tip.setTextFill(Color.web("#8B949E"));
        tip.setWrapText(true);
        tip.setPadding(new Insets(6, 0, 0, 0));

        sidebar.getChildren().addAll(
            title, subtitle,
            div1, ctrlTitle, controls,
            div2, spawnBtn,
            div3, linkTitle, linkBtn, statusLabel,
            div4, tip
        );
        return sidebar;
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
     * Called whenever a sphere is clicked in Link mode.
     *
     * State machine:
     *   linkSource == null  →  this node becomes the source; highlight it.
     *   linkSource == node  →  same node clicked twice; deselect.
     *   linkSource != node  →  attempt to link/unlink source ↔ node.
     */
    private void handleSphereClick(GraphNode clicked) {
        if (!linkModeActive) return;

        if (linkSource == null) {
            // ── First click: select this node as the source ──────────
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

            // Check whether an edge already exists between them
            GraphEdge existing = findEdge(src, clicked);

            if (existing != null) {
                // Edge exists → remove it (toggle behaviour)
                removeEdge(existing);
                setStatus("Edge removed:\n\"" + src.name + "\"\n↔ \"" + clicked.name + "\"");
            } else {
                // No edge → create one
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
        viewport.setOnMousePressed(e -> {
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });

        viewport.setOnMouseDragged(e -> {
            // Block camera rotation while in link mode so the user can
            // click spheres without accidentally spinning the scene.
            if (linkModeActive) return;

            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
            rotateY.setAngle(rotateY.getAngle() + dx * ROTATION_SPEED);
            rotateX.setAngle(rotateX.getAngle() + dy * ROTATION_SPEED);
        });

        // Click on empty space in link mode → deselect pending source
        viewport.setOnMouseClicked(e -> {
            if (linkModeActive && linkSource != null) {
                clearLinkSelection();
                setStatus("Deselected.\nClick a node to\nselect it first.");
            }
        });

        // ── Scroll to zoom ───────────────────────────────────────────
        // The camera sits at a negative Z (behind the scene looking forward).
        // Scrolling UP (positive deltaY) moves it toward the scene  → zoom in  → less negative Z.
        // Scrolling DOWN (negative deltaY) moves it away             → zoom out → more negative Z.
        // We clamp between -ZOOM_MAX and -ZOOM_MIN so the camera
        // never clips through the geometry or flies off to infinity.
        viewport.setOnScroll(e -> {
            double currentZ = camera.getTranslateZ();
            double newZ = currentZ + e.getDeltaY() * ZOOM_SPEED / 40.0;
            // clamp: camera Z is negative, so min distance = -ZOOM_MAX, max closeness = -ZOOM_MIN
            newZ = Math.max(-ZOOM_MAX, Math.min(-ZOOM_MIN, newZ));
            camera.setTranslateZ(newZ);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    /**
     * Adds a node to the physics world and scene graph.
     * No edges are created here — all edges are created manually by the user.
     */
    private GraphNode addNode(String name, Color color) {
        double x = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double y = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;
        double z = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.5;

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
     * No edges are auto-wired — the user must link it manually.
     */
    private void spawnNewNode() {
        String name  = SPAWN_NAMES [spawnIndex % SPAWN_NAMES.length];
        Color  color = Color.web(SPAWN_COLORS[spawnIndex % SPAWN_COLORS.length]);
        spawnIndex++;
        addNode(name, color);

        // Update status to hint the user
        if (linkModeActive) {
            setStatus("New node spawned.\nClick it to select\nand link it.");
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
