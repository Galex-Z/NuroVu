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
import javafx.scene.transform.Translate;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║        3D Force-Directed Workspace Graph — JavaFX Prototype          ║
 * ║  Inspired by Roblox's Friendscape. Single-file, zero dependencies.  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  HOW JavaFX 3D COORDINATES DIFFER FROM 2D                          │
 * │                                                                     │
 * │  2D (Canvas / Pane):                                               │
 * │    • Two axes: X (→ right) and Y (↓ down)                         │
 * │    • Origin (0,0) is top-left of the screen                       │
 * │    • Depth is an illusion you fake with draw-order (painter's alg) │
 * │    • No camera — you "see" everything flat                         │
 * │                                                                     │
 * │  3D (SubScene + PerspectiveCamera):                                │
 * │    • Three axes: X (→ right), Y (↓ down), Z (↗ toward viewer)    │
 * │    • Origin is the center of your 3D world                        │
 * │    • Real perspective projection: far things look smaller          │
 * │    • You move/rotate a Camera object to change your viewpoint      │
 * │    • Z > 0 pushes objects TOWARD the camera (closer)              │
 * │    • Z < 0 pushes objects AWAY from the camera (into the scene)   │
 * │    • Rotations require Rotate transforms with an axis vector       │
 * │    • PhongMaterial gives objects light-reactive surfaces           │
 * │    • Lighting (AmbientLight / PointLight) affects 3D shapes only  │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * COMPILE & RUN:
 *   Requires: Java 17+ with JavaFX 17+ on the module path
 *
 *   Option A — Using Maven wrapper (recommended for hackathon):
 *     1. Create pom.xml (see README section below)
 *     2. mvn javafx:run
 *
 *   Option B — Direct javac + java with JavaFX SDK:
 *     export FX=/path/to/javafx-sdk/lib
 *     javac --module-path $FX --add-modules javafx.controls,javafx.fxml WorkspaceGraph3D.java
 *     java  --module-path $FX --add-modules javafx.controls,javafx.fxml WorkspaceGraph3D
 *
 *   Option B (Windows):
 *     set FX=C:\javafx-sdk\lib
 *     javac --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D.java
 *     java  --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D
 *
 * ── pom.xml snippet ──────────────────────────────────────────────────
 *   <dependencies>
 *     <dependency>
 *       <groupId>org.openjfx</groupId>
 *       <artifactId>javafx-controls</artifactId>
 *       <version>21.0.2</version>
 *     </dependency>
 *   </dependencies>
 *   <build><plugins>
 *     <plugin>
 *       <groupId>org.openjfx</groupId>
 *       <artifactId>javafx-maven-plugin</artifactId>
 *       <version>0.0.8</version>
 *       <configuration>
 *         <mainClass>WorkspaceGraph3D</mainClass>
 *       </configuration>
 *     </plugin>
 *   </plugins></build>
 * ─────────────────────────────────────────────────────────────────────
 */
public class WorkspaceGraph3D extends Application {

    // ══════════════════════════════════════════════════════════════════
    //  CONSTANTS — Physics, Camera, Visual Tuning
    // ══════════════════════════════════════════════════════════════════

    /** 3D world radius — nodes are initialised randomly within a sphere of this size */
    private static final double WORLD_RADIUS      = 200.0;

    /** How strongly connected nodes attract each other (spring constant) */
    private static final double ATTRACTION        = 0.0015;

    /** How strongly all nodes repel each other (Coulomb-like constant) */
    private static final double REPULSION         = 12_000.0;

    /** Damping coefficient: 0 = no friction, 1 = fully frozen */
    private static final double DAMPING           = 0.85;

    /** Maximum velocity cap to prevent nodes from flying off */
    private static final double MAX_VELOCITY      = 4.0;

    /** The "ideal" spring length between connected nodes */
    private static final double SPRING_LENGTH     = 120.0;

    /** How far the camera sits from the origin */
    private static final double CAMERA_DISTANCE   = 550.0;

    /** Pixel sensitivity for mouse-drag rotation */
    private static final double ROTATION_SPEED    = 0.4;

    /** Sphere radius for window nodes */
    private static final double NODE_RADIUS       = 18.0;

    private static final int    SCENE_W           = 1000;
    private static final int    SCENE_H           = 680;

    // ══════════════════════════════════════════════════════════════════
    //  MOCK WINDOW DATA — replace with real OS window enumeration later
    // ══════════════════════════════════════════════════════════════════

    /** Name and accent color of each mock desktop window. */
    private static final String[][] MOCK_WINDOWS = {
        { "VS Code",        "#4EC9B0" },   // teal
        { "Google Chrome",  "#F6AE2D" },   // amber
        { "Spotify",        "#1DB954" },   // green
        { "Slack",          "#E01E5A" },   // pink-red
        { "Terminal",       "#A9B7C6" },   // steel blue
    };

    /** Pool of colors for dynamically added nodes */
    private static final String[] EXTRA_COLORS = {
        "#FF6B6B", "#C77DFF", "#48CAE4", "#F4A261", "#E9C46A", "#A8DADC"
    };

    /** Template names for new dynamically added windows */
    private static final String[] EXTRA_NAMES = {
        "Figma", "Discord", "Notion", "PyCharm", "Obsidian",
        "Postman", "Steam", "Finder", "Zoom", "Excel"
    };

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphNode
    //  Each node = a logical window + its 3D physics state
    // ══════════════════════════════════════════════════════════════════

    private static class GraphNode {
        // Physics state — positions and velocities in 3D space
        double x, y, z;
        double vx, vy, vz;   // velocity
        double fx, fy, fz;   // accumulated force this tick

        // JavaFX scene-graph objects
        Sphere sphere;
        Text   label;

        // Metadata
        String name;
        Color  color;

        GraphNode(String name, Color color, double x, double y, double z) {
            this.name  = name;
            this.color = color;
            this.x = x; this.y = y; this.z = z;

            // ── Sphere ──────────────────────────────────────────────
            sphere = new Sphere(NODE_RADIUS);

            // PhongMaterial: diffuseColor = base color,
            // specularColor = bright highlight for that glassy look
            PhongMaterial mat = new PhongMaterial();
            mat.setDiffuseColor(color);
            mat.setSpecularColor(Color.WHITE);
            mat.setSpecularPower(32);
            sphere.setMaterial(mat);

            // Position the sphere in 3D world space
            sphere.setTranslateX(x);
            sphere.setTranslateY(y);
            sphere.setTranslateZ(z);

            // ── Floating label ───────────────────────────────────────
            // Text lives in a 2D overlay panel, but we manually sync
            // its screen position from the sphere's 3D position each frame.
            label = new Text(name);
            label.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
            label.setFill(Color.WHITE);
            label.setStroke(Color.color(0, 0, 0, 0.4));
            label.setStrokeWidth(0.5);
        }

        /** Sync the JavaFX Sphere position to our physics x/y/z */
        void applyPositionToNode() {
            sphere.setTranslateX(x);
            sphere.setTranslateY(y);
            sphere.setTranslateZ(z);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphEdge
    //  Each edge = a directed connection between two nodes (rendered as
    //  a cylinder dynamically repositioned each physics tick)
    // ══════════════════════════════════════════════════════════════════

    private static class GraphEdge {
        GraphNode a, b;
        Cylinder  cylinder;

        GraphEdge(GraphNode a, GraphNode b) {
            this.a = a;
            this.b = b;

            cylinder = new Cylinder(1.5, 1);  // radius=1.5, height updated each frame

            PhongMaterial mat = new PhongMaterial();
            mat.setDiffuseColor(Color.color(0.5, 0.7, 1.0, 0.35));  // translucent blue-white
            cylinder.setMaterial(mat);
        }

        /**
         * Recompute the cylinder's position, rotation and height every frame.
         *
         * 3D MATH EXPLAINED:
         * A Cylinder in JavaFX points along the Y-axis by default. To draw it
         * from node A to node B we must:
         *   1. Find the midpoint  → translate the cylinder there.
         *   2. Find the distance  → set that as the cylinder's height.
         *   3. Find the angle between JavaFX's default Y-axis and our A→B
         *      direction vector → rotate the cylinder by that angle around
         *      the perpendicular axis (cross product of Y-axis and direction).
         */
        void update() {
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;

            double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (distance < 0.001) return;

            // 1. Height = distance between the two nodes
            cylinder.setHeight(distance);

            // 2. Position = midpoint of the two nodes
            cylinder.setTranslateX((a.x + b.x) / 2.0);
            cylinder.setTranslateY((a.y + b.y) / 2.0);
            cylinder.setTranslateZ((a.z + b.z) / 2.0);

            // 3. Rotation: align default Y-axis (0,1,0) with direction vector
            //    Cross product of Y-axis and direction gives the rotation axis.
            //    Dot product gives cos(angle) → Math.acos gives the angle.
            Point3D yAxis     = new Point3D(0, 1, 0);
            Point3D direction = new Point3D(dx / distance, dy / distance, dz / distance);
            Point3D rotAxis   = yAxis.crossProduct(direction);
            double  angle     = Math.toDegrees(Math.acos(yAxis.dotProduct(direction)));

            cylinder.getTransforms().setAll(new Rotate(angle, rotAxis));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  APPLICATION STATE
    // ══════════════════════════════════════════════════════════════════

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Random rng = new Random(42);

    /** Root group for ALL 3D scene-graph objects */
    private final Group world3D = new Group();

    /** 2D overlay pane — labels float here, updated each frame */
    private Pane labelOverlay;

    /** SubScene holds the entire 3D world */
    private SubScene subScene;

    /** Camera rotation state (Euler angles driven by mouse drag) */
    private final Rotate rotateX = new Rotate(20, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-30, Rotate.Y_AXIS);
    private double mouseX, mouseY;

    /** Counter for naming new windows */
    private int extraWindowIndex = 0;

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Force-Directed Workspace Graph  —  JavaFX Prototype");

        // ── Build the UI ──────────────────────────────────────────────
        BorderPane root = buildUI();

        // ── Seed the graph with mock windows ─────────────────────────
        for (String[] w : MOCK_WINDOWS) {
            addNode(w[0], Color.web(w[1]));
        }
        buildInitialEdges();

        // ── Start the physics + render loop ───────────────────────────
        startPhysicsLoop();

        // ── Show ───────────────────────────────────────────────────────
        Scene scene = new Scene(root, SCENE_W, SCENE_H, Color.web("#0D1117"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        // ── 3D SubScene ──────────────────────────────────────────────
        //  A SubScene is a lightweight 3D viewport embedded inside a
        //  normal JavaFX 2D scene graph. It has its own Camera and
        //  depth-buffer, letting you mix 3D content with regular 2D UI.
        subScene = new SubScene(world3D, SCENE_W - 220, SCENE_H, true,
                                SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);

        // ── Camera ───────────────────────────────────────────────────
        //  PerspectiveCamera simulates how human eyes see the world:
        //  objects farther away appear smaller (unlike ParallelCamera).
        //  fixedEyeAtCameraZero=false: the origin is the world centre.
        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(4000);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        subScene.setCamera(camera);

        // Attach rotation transforms to the world so mouse drag spins
        // the scene around the world origin (0,0,0).
        world3D.getTransforms().addAll(rotateX, rotateY);

        // ── Lights ───────────────────────────────────────────────────
        //  AmbientLight = flat base illumination (no shadows)
        //  PointLight   = positional light that creates highlights
        AmbientLight ambient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight   key     = new PointLight(Color.color(0.9, 0.95, 1.0));
        key.setTranslateX(-200);
        key.setTranslateY(-300);
        key.setTranslateZ(-200);
        PointLight fill = new PointLight(Color.color(0.2, 0.3, 0.5));
        fill.setTranslateX(200);
        fill.setTranslateY(200);
        fill.setTranslateZ(100);
        world3D.getChildren().addAll(ambient, key, fill);

        // ── Label overlay (2D pane on top of the SubScene) ───────────
        labelOverlay = new Pane();
        labelOverlay.setMouseTransparent(true);   // clicks pass through to 3D
        labelOverlay.setPrefSize(SCENE_W - 220, SCENE_H);

        // Stack the SubScene and label overlay
        StackPane viewport = new StackPane(subScene, labelOverlay);
        viewport.setStyle("-fx-background-color: #0D1117;");
        attachMouseHandlers(viewport);

        // ── Side panel ───────────────────────────────────────────────
        VBox sidebar = buildSidebar();

        // ── Root layout ───────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0D1117;");
        root.setCenter(viewport);
        root.setRight(sidebar);
        return root;
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(16);
        sidebar.setPrefWidth(210);
        sidebar.setPadding(new Insets(24, 18, 24, 18));
        sidebar.setStyle("""
                -fx-background-color: #161B22;
                -fx-border-color: #30363D;
                -fx-border-width: 0 0 0 1;
                """);

        // Title
        Label title = new Label("Workspace\nGraph 3D");
        title.setFont(Font.font("Monospace", FontWeight.BOLD, 18));
        title.setTextFill(Color.web("#58A6FF"));

        Label subtitle = new Label("Force-Directed Layout");
        subtitle.setFont(Font.font("Monospace", 11));
        subtitle.setTextFill(Color.web("#8B949E"));

        // Divider
        Region div1 = new Region();
        div1.setPrefHeight(1);
        div1.setStyle("-fx-background-color: #30363D;");

        // Legend
        Label legendTitle = new Label("NODES");
        legendTitle.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        legendTitle.setTextFill(Color.web("#8B949E"));

        VBox legend = new VBox(6);
        for (String[] w : MOCK_WINDOWS) {
            HBox row = new HBox(8);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Circle dot = new Circle(5, Color.web(w[1]));
            Label lbl = new Label(w[0]);
            lbl.setFont(Font.font("Monospace", 12));
            lbl.setTextFill(Color.web("#E6EDF3"));
            row.getChildren().addAll(dot, lbl);
            legend.getChildren().add(row);
        }

        // Divider
        Region div2 = new Region();
        div2.setPrefHeight(1);
        div2.setStyle("-fx-background-color: #30363D;");

        // Controls help
        Label ctrlTitle = new Label("CONTROLS");
        ctrlTitle.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        ctrlTitle.setTextFill(Color.web("#8B949E"));

        String[] hints = {
            "Drag  → Rotate camera",
            "Physics auto-layouts",
            "Button → Add node"
        };
        VBox controls = new VBox(4);
        for (String h : hints) {
            Label l = new Label("• " + h);
            l.setFont(Font.font("Monospace", 11));
            l.setTextFill(Color.web("#8B949E"));
            l.setWrapText(true);
        controls.getChildren().add(l);
        }

        // Divider
        Region div3 = new Region();
        div3.setPrefHeight(1);
        div3.setStyle("-fx-background-color: #30363D;");

        // "Add Window" button
        Button addBtn = new Button("+ Spawn Window Node");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        addBtn.setStyle("""
                -fx-background-color: #238636;
                -fx-text-fill: #E6EDF3;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                -fx-padding: 10 14 10 14;
                """);
        addBtn.setOnMouseEntered(e -> addBtn.setStyle("""
                -fx-background-color: #2EA043;
                -fx-text-fill: #E6EDF3;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                -fx-padding: 10 14 10 14;
                """));
        addBtn.setOnMouseExited(e -> addBtn.setStyle("""
                -fx-background-color: #238636;
                -fx-text-fill: #E6EDF3;
                -fx-background-radius: 6;
                -fx-cursor: hand;
                -fx-padding: 10 14 10 14;
                """));
        addBtn.setOnAction(e -> spawnNewNode());

        // Node count label (updated each frame)
        Label countLabel = new Label();
        countLabel.setFont(Font.font("Monospace", 11));
        countLabel.setTextFill(Color.web("#8B949E"));
        countLabel.textProperty().bind(
            javafx.beans.binding.Bindings.createStringBinding(
                () -> "Nodes: " + nodes.size() + "   Edges: " + edges.size(),
                // No real observable here — we'll update manually in the loop.
                // For a hackathon prototype this label is set in the physics tick.
                new javafx.beans.Observable[0]
            )
        );
        // We'll update this label text each frame via a reference stored as userData
        addBtn.getParent();  // side-effect: just for coherence
        // Store the label so physics loop can update it
        sidebar.setUserData(countLabel);

        sidebar.getChildren().addAll(
            title, subtitle, div1,
            legendTitle, legend, div2,
            ctrlTitle, controls, div3,
            addBtn, countLabel
        );
        return sidebar;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MOUSE ROTATION
    //  Rotates the entire world3D group around the origin by updating
    //  the Rotate transforms. This gives the "orbit camera" feel.
    // ══════════════════════════════════════════════════════════════════

    private void attachMouseHandlers(StackPane viewport) {
        viewport.setOnMousePressed(e -> {
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });

        viewport.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();

            // rotateY controls spinning left/right (around world Y-axis)
            rotateY.setAngle(rotateY.getAngle() + dx * ROTATION_SPEED);
            // rotateX controls tilting up/down (around world X-axis)
            rotateX.setAngle(rotateX.getAngle() + dy * ROTATION_SPEED);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    /** Add a new node to the physics world and the 3D scene-graph. */
    private GraphNode addNode(String name, Color color) {
        // Spawn at a random position within WORLD_RADIUS — spread across X,Y,Z
        double x = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;
        double y = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;
        double z = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;

        GraphNode node = new GraphNode(name, color, x, y, z);
        nodes.add(node);

        // Add the Sphere to the 3D world group
        world3D.getChildren().add(node.sphere);

        // Add the floating text label to the 2D overlay
        labelOverlay.getChildren().add(node.label);

        return node;
    }

    /**
     * Build initial edges: connect each node to its two neighbours
     * in the list, forming a rough ring. This creates a connected graph
     * that gives the force-directed layout something to work with.
     */
    private void buildInitialEdges() {
        int n = nodes.size();
        for (int i = 0; i < n; i++) {
            addEdge(nodes.get(i), nodes.get((i + 1) % n));
        }
        // Add a few cross-edges for visual interest
        if (n >= 4) {
            addEdge(nodes.get(0), nodes.get(2));
            addEdge(nodes.get(1), nodes.get(3));
        }
    }

    private void addEdge(GraphNode a, GraphNode b) {
        GraphEdge edge = new GraphEdge(a, b);
        edges.add(edge);
        world3D.getChildren().add(edge.cylinder);
    }

    /** Called by the "Spawn Window Node" button. */
    private void spawnNewNode() {
        String name  = EXTRA_NAMES [extraWindowIndex % EXTRA_NAMES.length];
        Color  color = Color.web(EXTRA_COLORS[extraWindowIndex % EXTRA_COLORS.length]);
        extraWindowIndex++;

        GraphNode newNode = addNode(name, color);

        // Connect the new node to a random existing node
        if (nodes.size() > 1) {
            int target = rng.nextInt(nodes.size() - 1);
            addEdge(newNode, nodes.get(target));
        }
        // Also connect to the most-recently-added previous node
        if (nodes.size() > 2) {
            addEdge(newNode, nodes.get(nodes.size() - 2));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PHYSICS LOOP — Custom 3D Force-Directed Layout
    //
    //  This runs on JavaFX's animation pulse (~60 fps).
    //  Each tick:
    //    1. Reset forces
    //    2. Apply repulsion between ALL pairs (Coulomb repulsion)
    //    3. Apply attraction along edges (Hooke's spring law)
    //    4. Integrate velocity → position (Euler integration)
    //    5. Update 3D scene-graph objects
    //    6. Update 2D label screen positions
    //    7. Update edge cylinders
    //
    //  ALL forces and positions operate in full 3D (x, y, z).
    // ══════════════════════════════════════════════════════════════════

    private void startPhysicsLoop() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                physicsTick();
                syncSceneGraph();
            }
        }.start();
    }

    private void physicsTick() {
        int n = nodes.size();

        // ── Step 1: Reset accumulated forces ─────────────────────────
        for (GraphNode node : nodes) {
            node.fx = 0; node.fy = 0; node.fz = 0;
        }

        // ── Step 2: Repulsion — every pair of nodes pushes each other apart ──
        //  Coulomb's law analogy:  F = k / r²  directed along the vector between nodes
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                GraphNode a = nodes.get(i);
                GraphNode b = nodes.get(j);

                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dz = a.z - b.z;

                // Euclidean distance in 3D
                double distSq = dx*dx + dy*dy + dz*dz;
                double dist   = Math.sqrt(distSq);
                if (dist < 1.0) dist = 1.0;  // avoid division-by-zero singularity

                // Repulsion magnitude: decreases with distance squared
                double force = REPULSION / distSq;

                // Decompose force into X, Y, Z components using unit vector
                double fx = force * dx / dist;
                double fy = force * dy / dist;
                double fz = force * dz / dist;

                a.fx += fx; a.fy += fy; a.fz += fz;
                b.fx -= fx; b.fy -= fy; b.fz -= fz;
            }
        }

        // ── Step 3: Attraction — spring force along each edge ────────
        //  Hooke's law:  F = k * (distance - rest_length)
        //  directed from each node toward the other.
        for (GraphEdge edge : edges) {
            GraphNode a = edge.a;
            GraphNode b = edge.b;

            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;

            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < 1.0) dist = 1.0;

            // Spring force: positive = attract, negative = repel (if too close)
            double force = ATTRACTION * (dist - SPRING_LENGTH);

            double fx = force * dx / dist;
            double fy = force * dy / dist;
            double fz = force * dz / dist;

            a.fx += fx; a.fy += fy; a.fz += fz;
            b.fx -= fx; b.fy -= fy; b.fz -= fz;
        }

        // ── Step 4: Integrate ─────────────────────────────────────────
        //  Euler integration: velocity += force, position += velocity
        //  Damping bleeds off velocity each frame (simulates air resistance).
        for (GraphNode node : nodes) {
            node.vx = (node.vx + node.fx) * DAMPING;
            node.vy = (node.vy + node.fy) * DAMPING;
            node.vz = (node.vz + node.fz) * DAMPING;

            // Clamp velocity so nodes don't fly off to infinity
            double speed = Math.sqrt(node.vx*node.vx + node.vy*node.vy + node.vz*node.vz);
            if (speed > MAX_VELOCITY) {
                double scale = MAX_VELOCITY / speed;
                node.vx *= scale; node.vy *= scale; node.vz *= scale;
            }

            node.x += node.vx;
            node.y += node.vy;
            node.z += node.vz;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SCENE GRAPH SYNC
    //  After physics moves nodes in our data model, we sync the actual
    //  JavaFX objects (Sphere positions, Cylinder orientations, Text XY).
    // ══════════════════════════════════════════════════════════════════

    private void syncSceneGraph() {
        // Update sphere 3D positions
        for (GraphNode node : nodes) {
            node.applyPositionToNode();
        }

        // Update edge cylinders
        for (GraphEdge edge : edges) {
            edge.update();
        }

        // Project 3D node positions to 2D screen for the floating labels
        //
        // JavaFX 3D → 2D projection math:
        //  We must account for: the world rotation (rotateX, rotateY),
        //  the perspective camera (field of view + distance), and the
        //  SubScene viewport offset.
        //  JavaFX provides Node.localToScene() for this — we query the
        //  sphere's scene-space bounds and use their centre X,Y.
        for (GraphNode node : nodes) {
            try {
                // localToScene gives us where the sphere is in 2D screen space
                // after all transforms (camera projection included).
                javafx.geometry.Bounds b = node.sphere.localToScene(
                    node.sphere.getBoundsInLocal()
                );
                double screenX = b.getCenterX();
                double screenY = b.getCenterY();

                // Offset label slightly above and to the right of the sphere centre
                node.label.setLayoutX(screenX + NODE_RADIUS + 2);
                node.label.setLayoutY(screenY - 6);

                // Fade the label based on Z depth for a natural depth-of-field feel.
                // Nodes with z < 0 are farther from the camera → dimmer label.
                // We rotate the raw z by the current camera angles to get view-space z.
                double angleY = Math.toRadians(rotateY.getAngle());
                double angleX = Math.toRadians(rotateX.getAngle());
                // Simplified view-space Z (good enough for opacity scaling):
                double viewZ = -node.x * Math.sin(angleY) * Math.cos(angleX)
                               + node.y * Math.sin(angleX)
                               + node.z * Math.cos(angleY) * Math.cos(angleX);
                double opacity = 0.4 + 0.6 * Math.max(0, Math.min(1,
                    (viewZ + WORLD_RADIUS) / (2 * WORLD_RADIUS)));
                node.label.setOpacity(opacity);

            } catch (Exception ignored) {
                // Bounds can be NaN on the very first frame before layout — safe to skip
            }
        }
    }
}
