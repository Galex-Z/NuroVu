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
 * ║        3D Force-Directed Workspace Graph — JavaFX Prototype          ║
 * ║  Inspired by Roblox's Friendscape. Single-file, zero dependencies.  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * LABEL FIX — Billboard technique:
 *   Labels are Text nodes placed INSIDE the 3D world (world3D Group),
 *   parented to a "billboard" Group that sits at the node's world position.
 *   Each frame we counter-rotate the billboard by the inverse of the
 *   camera's rotateX and rotateY angles so the text always faces the
 *   camera, regardless of how much the user has dragged.
 *
 *   WHY the old 2D-overlay approach broke:
 *     localToScene() on a node inside a SubScene returns coordinates in
 *     the SubScene's own coordinate space, NOT the parent Scene's space.
 *     The label Pane lived in the parent Scene space, so the numbers were
 *     wrong the moment any rotation was applied.
 *
 * COMPILE & RUN:
 *   Requires: Java 17+ with JavaFX 17+ on the module path
 *
 *   Option A — Maven (recommended):
 *     Add to pom.xml:
 *       <dependency>
 *         <groupId>org.openjfx</groupId>
 *         <artifactId>javafx-controls</artifactId>
 *         <version>21.0.2</version>
 *       </dependency>
 *     Plugin:
 *       <plugin>
 *         <groupId>org.openjfx</groupId>
 *         <artifactId>javafx-maven-plugin</artifactId>
 *         <version>0.0.8</version>
 *         <configuration><mainClass>WorkspaceGraph3D</mainClass></configuration>
 *       </plugin>
 *     Then: mvn javafx:run
 *
 *   Option B — Direct (Linux/Mac):
 *     export FX=/path/to/javafx-sdk/lib
 *     javac --module-path $FX --add-modules javafx.controls WorkspaceGraph3D.java
 *     java  --module-path $FX --add-modules javafx.controls WorkspaceGraph3D
 *
 *   Option B — Direct (Windows):
 *     set FX=C:\javafx-sdk\lib
 *     javac --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D.java
 *     java  --module-path %FX% --add-modules javafx.controls WorkspaceGraph3D
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
    private static final double ROTATION_SPEED  = 0.4;
    private static final double NODE_RADIUS     = 18.0;
    private static final int    SCENE_W         = 1000;
    private static final int    SCENE_H         = 680;

    // ══════════════════════════════════════════════════════════════════
    //  MOCK DATA
    // ══════════════════════════════════════════════════════════════════

    private static final String[][] MOCK_WINDOWS = {
        { "VS Code",       "#4EC9B0" },
        { "Chrome",        "#F6AE2D" },
        { "Spotify",       "#1DB954" },
        { "Slack",         "#E01E5A" },
        { "Terminal",      "#A9B7C6" },
    };

    private static final String[] EXTRA_COLORS = {
        "#FF6B6B", "#C77DFF", "#48CAE4", "#F4A261", "#E9C46A", "#A8DADC"
    };

    private static final String[] EXTRA_NAMES = {
        "Figma", "Discord", "Notion", "PyCharm", "Obsidian",
        "Postman", "Steam", "Finder", "Zoom", "Excel"
    };

    // ══════════════════════════════════════════════════════════════════
    //  INNER CLASS: GraphNode
    // ══════════════════════════════════════════════════════════════════

    private static class GraphNode {
        // Physics state
        double x, y, z;
        double vx, vy, vz;
        double fx, fy, fz;

        // Scene-graph objects
        Sphere sphere;
        Group  billboard;   // Group that holds Text; counter-rotated each frame
        Text   label;

        // The two rotation transforms we update every frame to face the camera
        final Rotate billboardRotY = new Rotate(0, Rotate.Y_AXIS);
        final Rotate billboardRotX = new Rotate(0, Rotate.X_AXIS);

        String name;
        Color  color;

        GraphNode(String name, Color color, double x, double y, double z) {
            this.name = name; this.color = color;
            this.x = x; this.y = y; this.z = z;

            // ── Sphere ──────────────────────────────────────────────
            sphere = new Sphere(NODE_RADIUS);
            PhongMaterial mat = new PhongMaterial();
            mat.setDiffuseColor(color);
            mat.setSpecularColor(Color.WHITE);
            mat.setSpecularPower(32);
            sphere.setMaterial(mat);

            // ── Label (billboard) ────────────────────────────────────
            // The Text node is a child of a Group that lives in 3D world
            // space at the node's position. Each frame we set the Group's
            // transforms to the INVERSE of the world rotation so it always
            // faces the camera — this is the "billboard" trick.
            label = new Text(name);
            label.setFont(Font.font("Monospace", FontWeight.BOLD, 13));
            label.setFill(Color.WHITE);
            label.setStroke(Color.color(0, 0, 0, 0.55));
            label.setStrokeWidth(0.6);
            // Offset: slightly to the right of and above the sphere centre.
            // These are in 3D world units (not pixels), but at our camera
            // distance ~1 world-unit ≈ ~1 screen-pixel, so 22 looks right.
            label.setTranslateX(NODE_RADIUS + 4);
            label.setTranslateY(-5);

            // billboardRotY must be applied BEFORE billboardRotX in JavaFX's
            // left-to-right transform list (transforms are applied right-to-left
            // to the geometry, so the list order here is: first Y, then X).
            billboard = new Group(label);
            billboard.getTransforms().addAll(billboardRotY, billboardRotX);
        }

        /** Move sphere and billboard Group to current physics position. */
        void applyPosition() {
            sphere.setTranslateX(x);
            sphere.setTranslateY(y);
            sphere.setTranslateZ(z);

            billboard.setTranslateX(x);
            billboard.setTranslateY(y);
            billboard.setTranslateZ(z);
        }

        /**
         * Counter-rotate this node's billboard by the INVERSE of the world
         * rotation so the label always faces the viewer.
         *
         * The world3D group has rotateY applied first, then rotateX.
         * To undo that, we apply -rotateX first, then -rotateY
         * (reverse order, negated angles).
         *
         * @param worldAngleY current rotateY.getAngle() of world3D
         * @param worldAngleX current rotateX.getAngle() of world3D
         */
        void updateBillboard(double worldAngleY, double worldAngleX) {
            // Inverse rotation: negate angles, reverse application order.
            // billboardRotY is index 0, billboardRotX is index 1 in the
            // transforms list, so JavaFX applies Y first then X — which
            // correctly undoes X-then-Y applied by world3D.
            billboardRotY.setAngle(-worldAngleY);
            billboardRotX.setAngle(-worldAngleX);
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
            mat.setDiffuseColor(Color.color(0.5, 0.7, 1.0, 0.35));
            cylinder.setMaterial(mat);
        }

        /**
         * Reposition and reorient the cylinder from node A to node B.
         * A JavaFX Cylinder points along Y by default; we rotate it to
         * match the A→B vector using cross+dot product.
         */
        void update() {
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if (dist < 0.001) return;

            cylinder.setHeight(dist);
            cylinder.setTranslateX((a.x + b.x) / 2.0);
            cylinder.setTranslateY((a.y + b.y) / 2.0);
            cylinder.setTranslateZ((a.z + b.z) / 2.0);

            Point3D yAxis     = new Point3D(0, 1, 0);
            Point3D direction = new Point3D(dx/dist, dy/dist, dz/dist);
            Point3D rotAxis   = yAxis.crossProduct(direction);
            double  angle     = Math.toDegrees(Math.acos(
                                    Math.max(-1, Math.min(1, yAxis.dotProduct(direction)))));
            cylinder.getTransforms().setAll(new Rotate(angle, rotAxis));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  APPLICATION STATE
    // ══════════════════════════════════════════════════════════════════

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Random rng = new Random(42);

    private final Group  world3D  = new Group();
    private final Rotate rotateX  = new Rotate(20,  Rotate.X_AXIS);
    private final Rotate rotateY  = new Rotate(-30, Rotate.Y_AXIS);
    private double mouseX, mouseY;
    private int extraWindowIndex  = 0;

    // ══════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.setTitle("3D Force-Directed Workspace Graph");
        BorderPane root = buildUI();

        for (String[] w : MOCK_WINDOWS) addNode(w[0], Color.web(w[1]));
        buildInitialEdges();
        startPhysicsLoop();

        Scene scene = new Scene(root, SCENE_W, SCENE_H, Color.web("#0D1117"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        // ── SubScene (the 3D viewport) ────────────────────────────────
        SubScene subScene = new SubScene(world3D, SCENE_W - 220, SCENE_H, true,
                                         SceneAntialiasing.BALANCED);
        subScene.setFill(Color.TRANSPARENT);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(4000);
        camera.setTranslateZ(-CAMERA_DISTANCE);
        subScene.setCamera(camera);

        // Rotation transforms on world3D: Y first, then X
        // (mouse drag updates these; billboard inverses them)
        world3D.getTransforms().addAll(rotateX, rotateY);

        // ── Lights ───────────────────────────────────────────────────
        AmbientLight ambient = new AmbientLight(Color.color(0.25, 0.25, 0.35));
        PointLight key = new PointLight(Color.color(0.9, 0.95, 1.0));
        key.setTranslateX(-200); key.setTranslateY(-300); key.setTranslateZ(-200);
        PointLight fill = new PointLight(Color.color(0.2, 0.3, 0.5));
        fill.setTranslateX(200);  fill.setTranslateY(200);  fill.setTranslateZ(100);
        world3D.getChildren().addAll(ambient, key, fill);

        // ── Viewport (SubScene only — no 2D overlay needed anymore) ──
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
        VBox sidebar = new VBox(16);
        sidebar.setPrefWidth(210);
        sidebar.setPadding(new Insets(24, 18, 24, 18));
        sidebar.setStyle("""
            -fx-background-color: #161B22;
            -fx-border-color: #30363D;
            -fx-border-width: 0 0 0 1;
            """);

        Label title = new Label("Workspace\nGraph 3D");
        title.setFont(Font.font("Monospace", FontWeight.BOLD, 18));
        title.setTextFill(Color.web("#58A6FF"));

        Label subtitle = new Label("Force-Directed Layout");
        subtitle.setFont(Font.font("Monospace", 11));
        subtitle.setTextFill(Color.web("#8B949E"));

        Region div1 = divider();

        Label legendTitle = new Label("NODES");
        legendTitle.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        legendTitle.setTextFill(Color.web("#8B949E"));

        VBox legend = new VBox(6);
        for (String[] w : MOCK_WINDOWS) {
            HBox row = new HBox(8);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Circle dot = new Circle(5, Color.web(w[1]));
            Label lbl  = new Label(w[0]);
            lbl.setFont(Font.font("Monospace", 12));
            lbl.setTextFill(Color.web("#E6EDF3"));
            row.getChildren().addAll(dot, lbl);
            legend.getChildren().add(row);
        }

        Region div2 = divider();

        Label ctrlTitle = new Label("CONTROLS");
        ctrlTitle.setFont(Font.font("Monospace", FontWeight.BOLD, 10));
        ctrlTitle.setTextFill(Color.web("#8B949E"));

        VBox controls = new VBox(4);
        for (String h : new String[]{ "Drag  → Rotate camera", "Physics auto-layouts", "Button → Add node" }) {
            Label l = new Label("• " + h);
            l.setFont(Font.font("Monospace", 11));
            l.setTextFill(Color.web("#8B949E"));
            l.setWrapText(true);
            controls.getChildren().add(l);
        }

        Region div3 = divider();

        Button addBtn = new Button("+ Spawn Window Node");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setFont(Font.font("Monospace", FontWeight.BOLD, 12));
        String btnStyle = """
            -fx-background-color: #238636;
            -fx-text-fill: #E6EDF3;
            -fx-background-radius: 6;
            -fx-cursor: hand;
            -fx-padding: 10 14 10 14;
            """;
        String btnHover = btnStyle.replace("#238636", "#2EA043");
        addBtn.setStyle(btnStyle);
        addBtn.setOnMouseEntered(e -> addBtn.setStyle(btnHover));
        addBtn.setOnMouseExited(e  -> addBtn.setStyle(btnStyle));
        addBtn.setOnAction(e -> spawnNewNode());

        sidebar.getChildren().addAll(
            title, subtitle, div1,
            legendTitle, legend, div2,
            ctrlTitle, controls, div3,
            addBtn
        );
        return sidebar;
    }

    private Region divider() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: #30363D;");
        return r;
    }

    // ══════════════════════════════════════════════════════════════════
    //  MOUSE ROTATION
    // ══════════════════════════════════════════════════════════════════

    private void attachMouseHandlers(StackPane viewport) {
        viewport.setOnMousePressed(e  -> { mouseX = e.getSceneX(); mouseY = e.getSceneY(); });
        viewport.setOnMouseDragged(e  -> {
            double dx = e.getSceneX() - mouseX;
            double dy = e.getSceneY() - mouseY;
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
            rotateY.setAngle(rotateY.getAngle() + dx * ROTATION_SPEED);
            rotateX.setAngle(rotateX.getAngle() + dy * ROTATION_SPEED);
        });
    }

    // ══════════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════

    private GraphNode addNode(String name, Color color) {
        double x = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;
        double y = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;
        double z = (rng.nextDouble() * 2 - 1) * WORLD_RADIUS * 0.6;

        GraphNode node = new GraphNode(name, color, x, y, z);
        nodes.add(node);

        // Both the Sphere AND the billboard Group go into world3D —
        // they are separate children so the sphere gets lighting
        // (Text nodes inside a Group don't respond to PointLight).
        world3D.getChildren().addAll(node.sphere, node.billboard);
        return node;
    }

    private void buildInitialEdges() {
        int n = nodes.size();
        for (int i = 0; i < n; i++) addEdge(nodes.get(i), nodes.get((i+1) % n));
        if (n >= 4) { addEdge(nodes.get(0), nodes.get(2)); addEdge(nodes.get(1), nodes.get(3)); }
    }

    private void addEdge(GraphNode a, GraphNode b) {
        GraphEdge edge = new GraphEdge(a, b);
        edges.add(edge);
        world3D.getChildren().add(edge.cylinder);
    }

    private void spawnNewNode() {
        String name  = EXTRA_NAMES [extraWindowIndex % EXTRA_NAMES.length];
        Color  color = Color.web(EXTRA_COLORS[extraWindowIndex % EXTRA_COLORS.length]);
        extraWindowIndex++;

        GraphNode newNode = addNode(name, color);
        if (nodes.size() > 1) addEdge(newNode, nodes.get(rng.nextInt(nodes.size() - 1)));
        if (nodes.size() > 2) addEdge(newNode, nodes.get(nodes.size() - 2));
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

        for (GraphNode node : nodes) { node.fx = 0; node.fy = 0; node.fz = 0; }

        // Repulsion: Coulomb-like F = k / r²
        for (int i = 0; i < n; i++) {
            for (int j = i+1; j < n; j++) {
                GraphNode a = nodes.get(i), b = nodes.get(j);
                double dx = a.x-b.x, dy = a.y-b.y, dz = a.z-b.z;
                double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
                double f = REPULSION / (dist * dist);
                double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
                a.fx+=fx; a.fy+=fy; a.fz+=fz;
                b.fx-=fx; b.fy-=fy; b.fz-=fz;
            }
        }

        // Attraction: Hooke's spring F = k * (dist - rest)
        for (GraphEdge e : edges) {
            double dx = e.b.x-e.a.x, dy = e.b.y-e.a.y, dz = e.b.z-e.a.z;
            double dist = Math.max(1.0, Math.sqrt(dx*dx + dy*dy + dz*dz));
            double f = ATTRACTION * (dist - SPRING_LENGTH);
            double fx = f*dx/dist, fy = f*dy/dist, fz = f*dz/dist;
            e.a.fx+=fx; e.a.fy+=fy; e.a.fz+=fz;
            e.b.fx-=fx; e.b.fy-=fy; e.b.fz-=fz;
        }

        // Integrate
        for (GraphNode node : nodes) {
            node.vx = (node.vx + node.fx) * DAMPING;
            node.vy = (node.vy + node.fy) * DAMPING;
            node.vz = (node.vz + node.fz) * DAMPING;
            double speed = Math.sqrt(node.vx*node.vx + node.vy*node.vy + node.vz*node.vz);
            if (speed > MAX_VELOCITY) {
                double s = MAX_VELOCITY / speed;
                node.vx*=s; node.vy*=s; node.vz*=s;
            }
            node.x += node.vx;
            node.y += node.vy;
            node.z += node.vz;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  SCENE GRAPH SYNC
    // ══════════════════════════════════════════════════════════════════

    private void syncSceneGraph() {
        double ay = rotateY.getAngle();
        double ax = rotateX.getAngle();

        for (GraphNode node : nodes) {
            node.applyPosition();
            // Counter-rotate every billboard by the inverse of the world rotation.
            // This keeps labels facing the camera at all times.
            node.updateBillboard(ay, ax);
        }

        for (GraphEdge edge : edges) edge.update();
    }
}
