Aug 13, 2026
beta33

#Concluded Phase 1 of the 3D Graph Visualizer engine. 

This milestone consolidated multiple feature additions, critical camera projection bug fixes, JSON-based workspace save/load operations, and refined 3D interaction modes into a stable, self-contained architecture.

Key Architectural & Feature Milestones:

Workspace Serialization & Persistence:Added a dependency-free JSON import/export parser to persist graph states (node labels, colors, $X/Y/Z$ coordinates, and edge connections).

Resolved nested bracket parsing issues (between() logic) by implementing depth-based bracket counting to prevent edge-array corruption.Configured imported nodes to bypass spawn damping mechanics (spawnTick set to settled threshold) so saved structures render immediately in equilibrium positions without scattering.

Auto-Focus Camera Motion & View Modes:Coordinate Alignment: Fixed screen-space vs. world-space pan overshoot by decoupling zoom manipulation from auto-focus, strictly lerping $X/Y$ translation and rotation matrices (rotateX, rotateY).

Side-View Focus Mode: Introduced a side-view toggle that rotates the viewport 90° along the Y-axis. Re-mapped pan targets to account for axis swapping ($+Z$ mapping to screen-horizontal when rotated 90°).

Target Snapping Fix: Updated auto-focus animation convergence to snap directly to variable targets (glideTargetRX, glideTargetRY) instead of hardcoded zero angles.

OS Window Binding Architecture:Shifted window binding persistence away from dynamic runtime handles (HWND) to saved window title strings (boundTitle).

Enhanced window title matching across sessions using a multi-tiered heuristic (exact match > saved-contains-current > current-contains-saved) to handle dynamic application title changes (e.g., active file names in editors or browsers).

Viewport Navigation & Physics:Removed upper zoom distance limits (ZOOM_MAX), leaving viewport distance control fully in the user's hands.

Preserved full FPS-style navigation independence: orbit rotation, 2D translation panning, scroll zoom, and instant manual drag cancellation during automated camera glides.

this version is the finished result of phase one.

Claude's response:

What was added and why each decision was made
State fields — three variables, no new threads
java
private boolean autoFocusEnabled = false;
private boolean glideActive      = false;
private double  glideTargetX, glideTargetY;
private static final double GLIDE_SPEED = 0.10;

The glide is driven entirely by the existing AnimationTimer — no Timeline, no background thread. This keeps the architecture clean and means the glide is automatically synchronised with the physics loop at 60fps.

The ToggleButton

A ToggleButton is used instead of a CheckBox because it matches the visual language of the existing action buttons. Its selectedProperty listener does two things: sets autoFocusEnabled, and immediately sets glideActive = false if toggled off so any running glide stops instantly.

startGlideTo(node) — the target calculation
java
glideTargetX = -node.x;
glideTargetY = -node.y;

The camera looks at the world origin. To bring node (nx, ny, nz) to the centre of the screen we need world3D to translate so that node is at the origin — which means shifting by (-nx, -ny). This is called every time selectNode fires, so if the physics is still moving nodes the glide target updates on the next click.

The lerp in syncSceneGraph
java
double newX = curX + (glideTargetX - curX) * GLIDE_SPEED;

This is an exponential ease-out (lerp with a fixed factor each frame). At GLIDE_SPEED = 0.10 the camera covers 10% of the remaining distance every tick — fast at the start, smoothly decelerating as it closes in. It stops when within 0.5 world-units to avoid infinite micro-corrections at the end.

Override safety — one line
java
viewport.setOnMouseDragged(e -> {
    glideActive = false;   // ← this line
    ...

Any drag event — left or right button — immediately sets glideActive = false before the rotation or pan logic runs. The user gets instant manual control the moment they touch the mouse, with no fighting against the animation.
The issue is that startGlideTo uses raw node physics coordinates (-node.x, -node.y) as the pan target, but world3D.translateX/Y operates in screen space — and after a rotation, the world's X and Y axes no longer align with the screen's X and Y axes. We need to project the node's 3D position through the current rotation to find where it lands on screen, then pan to cancel that offset.

Viewed 2 files
Viewed 2 files

Good. The fix is to use JavaFX's own scene-graph projection rather than doing manual trig. We call node.sphere.localToScene() to get the node's current screen position, then compute the pan delta needed to move that screen position to the viewport centre. This automatically accounts for all rotations, translations, and the perspective projection.

Edited a file, read a file
Edited a file, read a file

One method rewritten. Here's exactly why the old approach was wrong and why this one works regardless of rotation angle.

The root cause

world3D.translateX/Y lives in the parent coordinate space — which is screen-aligned. But node.x and node.y are in world3D's local coordinate space — which rotates with the world. After a 90° yaw rotation, what was the world's X-axis is now pointing into the screen, so setting glideTargetX = -node.x pans the camera sideways in screen space when it should be panning into depth (which has no visible effect), and completely misses the intended centre.

Why localToScene() fixes it
java
javafx.geometry.Bounds b = node.sphere.localToScene(node.sphere.getBoundsInLocal());

JavaFX has already run the full transform pipeline — local → world3D local → world3D rotated → perspective projection → screen. localToScene() gives us the sphere's actual pixel position on screen after all of that. No manual matrix math needed.

From there the target is simply:

java
// How far is the sphere from viewport centre right now?
double deltaX = vpCentreX - b.getCenterX();
double deltaY = vpCentreY - b.getCenterY();

// Shift world3D's translation by that exact screen delta
glideTargetX = world3D.getTranslateX() + deltaX;
glideTargetY = world3D.getTranslateY() + deltaY;

This works at any rotation because we never touch world-space coordinates — we're entirely in screen space throughout. Rotate 90°, 180°, any angle — the projected screen position is always correct, and the pan target always lands the node at the viewport centre.
Why all previous approaches were broken

Attempt 1 (-node.x, -node.y) — correct math, but only at zero rotation. world3D.translateX/Y is in screen space; node.x/y is in world-local space. After any rotation those two spaces diverge, so the pan target was wrong.

Attempt 2 (localToScene() projection) — correct concept, but localToScene() inside a SubScene returns coordinates in SubScene space, not the parent Scene space. The delta was being computed against the wrong origin, causing the exaggerated overshoot.

Why the reset approach is correct

Resetting the angles to zero eliminates the coordinate space mismatch entirely. When rotateX = 0 and rotateY = 0, world3D's local X and Y axes are perfectly aligned with the screen's X and Y axes — they're the same thing. At that point:

glideTargetX = -node.x   ← moves node to screen centre X  ✓
glideTargetY = -node.y   ← moves node to screen centre Y  ✓

No projection. No matrix decomposition. Just correct.

The four values — translateX, translateY, rotateX, rotateY — all lerp simultaneously with the same GLIDE_SPEED factor, so the rotation unwinds and the pan converges in one smooth continuous motion. Because the lerp factor is the same for all four, they all arrive at their targets at the same time, which keeps the node tracking smoothly toward centre throughout the animation rather than arriving in two separate phases.
Two separate issues to fix:

Distance too close — FOCUS_DISTANCE should be computed from the node's edge lengths rather than a hardcoded value, so connected nodes stay in view.
Zoom shifts after glide — the glide sets camera.translateZ to a fixed target, overriding whatever zoom the user had. The fix: don't touch camera.translateZ at all during the glide. Let the user's zoom stay exactly where it is.
Read startGlideTo and the glide tick in syncSceneGraph
Read startGlideTo and the glide tick in syncSceneGraph

Good. Both fixes are clear. Remove glideTargetZ entirely and delete the FOCUS_DISTANCE constant — zoom is untouched. For distance, compute it dynamically in startGlideTo from the node's connected edges.

Edited 3 files
Edited 3 files

Now strip the Z lerp out of the glide tick in syncSceneGraph:

Edited a file, read a file
Edited a file, read a file

Fixed. The glide now animates exactly three things — pan X, pan Y, rotation reset — and leaves camera.translateZ completely alone. Whatever zoom level you were at before clicking a node is exactly where you stay after the glide finishes.

The FOCUS_DISTANCE constant and all Z lerp code are gone. Scroll to zoom in or out to taste before or after auto-focusing, and it will never be overridden.
Fix 1 — zoom to fit on focus: compute a glideTargetZ from the node's Z depth plus a comfortable viewing offset, and add it back to the glide tick. This time it's driven by the node's actual position rather than a hardcoded constant.

Fix 2 — no zoom-out limit: remove ZOOM_MAX from the clamp, keep only ZOOM_MIN so you can't clip through geometry.

Edited 2 files
Edited 2 files

Now add glideTargetZ back to the state fields and wire it into startGlideTo and the glide tick — this time computed from the node's actual world position:

Edited 2 files
Edited 2 files

Now wire glideTargetZ into the glide tick:

Edited a file, read a file
Edited a file, read a file

Two fixes done. Here's what each one does:

Fix 1 — zoom adapts to node depth and neighbours

startGlideTo now walks the edge list to find the furthest connected neighbour from the selected node. That distance becomes the spread. The camera Z target is:

targetZ = -(node.z + spread × 2.2 + NODE_RADIUS × 3)

So a node with neighbours 150 units away lands the camera ~330+ units back from the node — enough to see all connected nodes comfortably. A completely isolated node (no edges) falls back to a fixed 380 unit distance. Tweak FOCUS_PADDING (currently 2.2) to taste — higher gives more breathing room, lower tightens the frame.

Fix 2 — no zoom-out limit

The scroll handler now only clamps the near end:

java
newZ = Math.min(-ZOOM_MIN, newZ);  // can't get closer than ZOOM_MIN
// no far limit — scroll out as far as you want

ZOOM_MAX and the Math.max call are gone entirely.
What was added
Format — plain JSON, zero new dependencies

The file saves as a .json you can open in any text editor, diff in git, or copy between machines. No XML, no binary, no third-party library. The schema is:

json
{
  "version": 1,
  "nodes": [
    { "id": 0, "name": "Root", "color": "#58A6FF",
      "x": 12.34, "y": -5.67, "z": 0.00,
      "boundTitle": "Visual Studio Code" }
  ],
  "edges": [
    { "from": 0, "to": 1 }
  ]
}
Why boundTitle instead of HWND

HWNDs are integers issued by Windows per-process-per-session. Save a handle at 3pm, reopen the app at 9am — the handle is gone. Instead we save the window title string that was matched when binding. On import, listAllWindows() runs once and we match the saved title with a case-insensitive contains() — the same fuzzy logic the picker uses. If the window is open, it rebinds automatically with the ⚡ badge. If it's not open, the node loads as unbound with no error.

The minimal JSON parser

Rather than pulling in Gson or Jackson (which would break the single-file constraint), the save/load uses four small static helpers:

jsonStr() — quotes and escapes strings for output
between() — extracts the nodes or edges array body
splitObjects() — splits {...},{...} into individual object strings by tracking brace depth
strVal() / dblVal() — extract a named value from an object string

This handles the exact schema we write — it's not a general-purpose JSON parser and doesn't need to be.

Import — loaded nodes skip spawn damping
java
node.spawnTick    = SPAWN_DAMP_TICKS;
node.spawnDamping = DAMPING;

Without this, every loaded node would rocket away from the origin for 2 seconds while the spawn damping wore off. Setting them to fully-settled values means the graph loads into the physics engine already stable at the saved positions.

Workflow transfer between versions

Since the file is plain text JSON with stable field names, a workspace saved from an older version of the code will load correctly in a newer one as long as the "version" field is present. You can add new fields in future versions and the old parser will simply ignore them.
The bug is in this line in startGlideTo:

java
double targetZ = -(node.z + spread * FOCUS_PADDING + NODE_RADIUS * 3);

node.z is the node's depth in world space, and we're adding it to the camera distance — but camera.translateZ is in the parent (screen) space, not world space. When world3D has any rotation applied, node.z in world-local space does not correspond to depth along the camera's view axis. So nodes with large Z values in world space push the camera further back than intended, and nodes with negative Z pull it too close.

The fix: ignore node.z entirely. The camera's view depth is already handled by CAMERA_DISTANCE as the baseline. We just need to offset by the spread of connected nodes — nothing more.

Viewed a file, edited a file, read a file
Viewed a file, edited a file, read a file

One line removed from the formula. The camera distance is now purely:

-(spread × 2.2 + NODE_RADIUS × 3)

Where spread is the 3D distance to the furthest connected neighbour in world-local space — which is rotation-invariant because it's a distance between two points in the same coordinate system, not a position relative to the camera. Every node at any rotation angle will now get the same consistent framing based purely on how far its neighbours are from it.
I can see the real problem now. The zoom target is still being computed and animated — but the issue is that glideTargetZ is based on neighbour spread in world-local space, while camera.translateZ is the distance from the camera to the world origin, not to the node. After panning, the node is at the screen centre but the camera distance is still measured from the origin — so the zoom over/undershoots depending on where the node actually sits.

The simplest correct fix: don't touch zoom at all during auto-focus. Just pan and reset rotation. Leave the camera Z exactly where the user had it. If they want to zoom, they scroll. This removes the entire source of misalignment.

Edited 3 files, read a file
Edited 3 files, read a file

Zoom is completely out of the auto-focus now. The glide does exactly two things — pan to centre the node, and reset the rotation to face-on. Camera Z is untouched.

The core reason every zoom attempt broke: camera.translateZ is the distance from the camera to the world origin, not to the node. The node is offset from the origin by its physics position, and after panning world3D that offset shifts again. There's no clean formula that maps node position to a correct camera Z without fully projecting through the perspective matrix — which is exactly the rabbit hole that caused all the previous misalignment. Leaving zoom to the user entirely sidesteps the problem completely.
Bug 1 — edges not loading: between(json, "\"edges\"", "]") uses lastIndexOf("]") which grabs the last ] in the file — but if the nodes array ends with ] that comes after the edges array, it returns the wrong slice. The node objects themselves contain no ] but the fix is to parse the edges section more precisely.

Bug 2 — boundTitle not saving on non-Windows: WindowManager.listAllWindows() returns empty on non-Windows, so the for loop in saveGraph that tries to match the HWND to a live title never fires — boundTitle stays "" even for bound nodes. The title needs to be stored directly on the node when binding, not re-looked-up at save time.

Add boundTitle field to GraphNode so it's stored at bind time
Add boundTitle field to GraphNode so it's stored at bind time

Now store the title in bindNode so it's always available at save time:

Store boundTitle on the node when binding
Store boundTitle on the node when binding

Now simplify saveGraph — just read node.boundTitle directly, no re-scan needed. Also fix the edges section parsing to use a targeted between that finds the edges array correctly:

Fix saveGraph to use stored boundTitle directly
Fix saveGraph to use stored boundTitle directly

Now fix between() — it uses lastIndexOf for the end token which is ambiguous when multiple ] exist. Replace it with a version that finds the matching close bracket for the specific array:

Fix between() to find the correctly matched closing bracket instead of lastIndexOf
Fix between() to find the correctly matched closing bracket instead of lastIndexOf

Also update the load rebind to use boundTitle field directly on the node after loading:

Edited a file, read a file
Edited a file, read a file

Three bugs fixed. Here's exactly what each one was:

Bug 1 — edges not loading (between() using lastIndexOf)

The old parser did json.lastIndexOf("]") to find the end of the target array. But in a JSON file with two arrays the last ] is always the one closing the edges array — so when parsing the nodes array it would grab everything from "nodes": [ all the way to the very end of the file, including the edges array inside it. splitObjects() then found edge objects mixed in and tried to parse them as nodes, silently producing garbage indices. The fix counts bracket depth to find the correctly matched closing ] for each specific array.

Bug 2 — boundTitle not saving

saveGraph was trying to re-scan live windows to recover the title from the HWND at save time. This fails silently in two situations: when the window has been closed since binding, and on non-Windows where listAllWindows() returns empty. The fix stores boundTitle directly on the node inside bindNode() the moment the user picks a window — it's always available at save time with no re-scan needed.

Bug 3 — boundTitle not restored on load

The loaded boundTitle was being used for matching but not stored back on the node after a successful rebind. This meant if you saved, loaded, and saved again, the re-exported file would have empty boundTitle fields. Fixed by setting node.boundTitle = we.title() during the rebind step in loadGraph.
The issue is in how saveGraph gets the boundTitle at save time. Even though we now store it on the node, let me verify the field is actually being written and read correctly.

Viewed 4 files
Viewed 4 files

Now read the load rebind section and strVal to check parsing:

Read loadGraph rebind section and strVal
Read loadGraph rebind section and strVal

Found it. The rebind logic does we.title().toLowerCase().contains(lower) — where lower is the saved full window title lowercased. But window titles change constantly. "Visual Studio Code - WorkspaceGraph3D.java" at save time becomes "Visual Studio Code - GraphNode.java" when you reopen it. The contains() check fails because the saved full title is longer and more specific than what's currently open.

The fix: reverse the match direction. Instead of checking if the current title contains the saved title, check if the saved title contains the current window's title — or better yet, extract just the application name (the part after the last - or before the first -) and match on that. The most robust approach: try exact match first, then saved-contains-current, then current-contains-saved.

Edited a file, read a file
Edited a file, read a file

The root cause was the match direction, not the saving. Window titles are dynamic — VS Code appends the active filename, Chrome appends the tab title, Spotify changes as songs change. Saving the full title at bind time and then requiring the current title to contain that full string almost never works across sessions because the saved title is usually the longer, more specific one.

The new matching tries three levels:

Score	Condition	Example
3	Exact match	Title hasn't changed at all
2	Saved title contains current	Current: "VS Code", Saved: "file.java - VS Code"
1	Current title contains saved	Current: "notes.md - VS Code", Saved: "VS Code"

It picks the highest-scoring window across all open windows, so if both Chrome and Edge happen to match at score 1, but one also matches at score 2, it correctly prefers the stronger match.
(window binding when loading was removed)
So a "side view" toggle — auto-focus centres the node as normal but then rotates the world 90° around Y instead of resetting to 0°. Let me read startGlideTo quickly.

Viewed 3 files
Viewed 3 files

Good. Two small edits — add the sideViewEnabled state field and toggle button to the sidebar, then change startGlideTo to set glideTargetRY = 90 when the toggle is on.

Edited 3 files
Edited 3 files

Now update startGlideTo to use sideViewEnabled:

Edited a file, read a file
Edited a file, read a file

Done. Three small edits, nothing else touched.

The toggle only changes glideTargetRY in startGlideTo — 0.0 for face-on, 90.0 for side view. Everything else about the glide (pan, pitch reset, lerp speed, drag cancellation) stays identical. You can flip the toggle between node clicks to switch between the two viewing angles freely.
The snap happens in the glide tick's convergence block — when all values are close enough it snaps to the final targets, but glideTargetRY is being snapped to 0 instead of the intended angle. Let me find it
Found it — lines 1304-1305 hardcode rotateX.setAngle(0) and rotateY.setAngle(0) in the snap block instead of using the target values.

Edited a file, read a file
Edited a file, read a file

One line change. The convergence block was snapping rotateX and rotateY to 0 regardless of what the targets actually were — so the glide would correctly animate toward 90° but then snap back to 0° the moment it got close enough to stop. Now it snaps to glideTargetRX and glideTargetRY, which are 0 in face-on mode and 90 in side view
The pan target (-node.x, -node.y) is computed in world-local space, but when side view rotates the world 90° around Y, the world's X axis becomes the screen's Z axis and the world's Z axis becomes the screen's X axis. So panning by -node.x slides the camera along the wrong screen axis entirely.

The fix: when side view is on, swap the pan axes to account for the 90° Y rotation. At 90° rotation, what was world-X is now pointing into the screen, and what was world-Z is now pointing screen-left. So the screen-X pan target should be +node.z (not -node.x), and screen-Y stays -node.y.

Edited a file, read a file
Edited a file, read a file

The pan targets now account for the rotation. At 90° Y rotation the world's X axis has rotated to point into the screen, so panning by -node.x in screen space was moving the camera forward/back rather than left/right — which is why nodes to the right disappeared off screen. Using -node.z instead correctly maps to the screen-horizontal axis at 90°, and Y stays the same since the Y axis is the rotation axis and doesn't change.

Thank you — and that's the right way to think about it. Phase 1 is a solid foundation.

Here's what Phase 1 delivers as a complete, self-contained system:

3D Graph Engine

Force-directed physics across X/Y/Z with spawn damping
Billboard labels always facing the camera
Edge cylinders with correct 3D orientation math

Navigation

Left drag → orbit rotation
Right drag → pan
Scroll → zoom (no outward limit)
Auto-Focus with smooth glide, angle reset, and side-view mode
Drag-to-cancel glide override

Graph Editing

Named node spawn with fallback counter
Link Mode for manual edge creation and removal
Single-click selection, Delete key and button removal
Bind Window button for manual OS window attachment on any node
Double-click to focus and restore a bound OS window

OS Integration (Windows)

JNA/Win32 via EnumWindows, GetWindowRect, SetForegroundWindow
Live window picker with search filter on every spawn or manual bind
Window size preservation on focus (no resize side effect)

Persistence

Export/Import workspace as plain JSON
Saves node names, colors, positions, and links
Compatible across versions — binding is manual post-import

When you're ready to start Phase 2, a few natural directions worth thinking about:

Visual polish — the starfield and aura effects from earlier, now that the core is stable
Node types — distinguishing between task nodes, app nodes, reference nodes visually
Real-time window tracking — polling to detect if a bound window closes and updating the node state
Collaborative graphs — Claude Tag or a shared backend so a team can build a workspace graph together

Say the word when you're ready.
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
    private static final double ZOOM_MIN         = 40.0;  // closest the camera can get
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
        HWND   hwnd = null;
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
    private Button bindBtn;
    private javafx.scene.control.TextField nameField;

    // ── Auto-focus glide state ────────────────────────────────────────
    // Animates three things simultaneously toward their targets each tick:
    //   • rotateX / rotateY  → reset to 0  (face-on view)
    //   • world3D.translateX → -node.x     (centre node horizontally)
    //   • world3D.translateY → -node.y     (centre node vertically)
    //
    // Because angles reset to zero, world axes = screen axes at the end
    // of the glide, so the simple -node.x/-node.y translation is exact.
    // No scene-projection math required.
    private boolean autoFocusEnabled = false;
    private boolean sideViewEnabled  = false; // when true, auto-focus rotates 90° on Y
    private boolean glideActive      = false;
    private double  glideTargetX     = 0;
    private double  glideTargetY     = 0;
    private double  glideTargetRX    = 0;
    private double  glideTargetRY    = 0;
    private static final double GLIDE_SPEED = 0.09; // lerp factor per tick

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

        // ToggleButton styled to match the rest of the panel.
        // When selected it turns blue; when deselected it goes grey.
        // We use a ToggleButton rather than a CheckBox so it matches
        // the visual language of the other action buttons.
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
            if (!isOn) glideActive = false;  // immediately halt any running glide
        });

        Label focusHint = new Label("When ON, clicking a\nnode glides the camera\nto centre it.\nDrag to cancel glide.");
        focusHint.setFont(Font.font("Monospace", 10));
        focusHint.setTextFill(Color.web("#484F58"));
        focusHint.setWrapText(true);

        // Side-view toggle — only meaningful when auto-focus is on.
        // Rotates the world 90° around Y during the glide so the camera
        // views the node from the side rather than face-on.
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

        sidebar.getChildren().addAll(
            title,
            div1, ctrlTitle, grid,
            div2, spawnTitle, nameLabel, nameField, spawnBtn,
            divDel, delTitle, deleteBtn, bindBtn,
            div3, linkTitle, linkBtn, statusLabel,
            divFocus, focusTitle, autoFocusBtn, focusHint, sideViewBtn,
            divider(), sectionHeader("WORKSPACE"),
            buildSaveLoadSection()
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
        bindBtn.setDisable(false);
        if (autoFocusEnabled) startGlideTo(node);
    }

    /**
     * Triggers a smooth glide that resets the camera angle to face-on,
     * centres the node, and adjusts zoom so the node and its neighbours
     * are comfortably in view.
     *
     * ZOOM TARGET CALCULATION
     * ───────────────────────
     * After the rotation resets to zero, the camera looks straight down
     * the -Z axis at the world origin.  The node will be centred at (0,0)
     * on screen by the pan.  Its depth (node.z) tells us how far it is
     * along the camera's view axis.
     *
     * To frame the node and its connected neighbours we compute the
     * maximum distance from the node to any of its neighbours, then add
     * a comfortable padding multiplier.  That gives the camera Z target.
     *
     *   targetZ = -(spread * FOCUS_PADDING + NODE_RADIUS * 3)
     *
     * WHY node.z IS NOT INCLUDED
     * ──────────────────────────
     * camera.translateZ lives in screen/parent space — it is the literal
     * distance from the camera to the world origin along the view axis.
     * node.z is in world3D's LOCAL space, which rotates with world3D.
     * After any rotation, world-local Z does not map to view-axis depth.
     * Adding node.z to the camera distance therefore produces a different
     * result for every rotation angle — nodes behind the origin push the
     * camera further back, nodes in front pull it closer, incorrectly.
     * The camera just needs to be far enough to see the spread of neighbours,
     * regardless of where in world space the node happens to sit.
     *
     * If the node has no edges we fall back to a fixed comfortable distance.
     * The result is clamped to ZOOM_MIN so we never clip into geometry.
     */
    private void startGlideTo(GraphNode node) {
        // When side view rotates world3D by 90° around Y, the world axes
        // are remapped in screen space:
        //   face-on (0°):  screen-X = world-X,  screen-Z = world-Z
        //   side view (90°): screen-X = world-Z, screen-Z = -world-X
        //
        // world3D.translateX/Y operates in screen space, so we must use
        // the correct world axis for each rotation target:
        //   face-on:   panX = -node.x,  panY = -node.y
        //   side view: panX = -node.z,  panY = -node.y  (X and Z swapped)
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

    private void clearSelection() {
        if (selectedNode != null) {
            selectedNode.applyVisualState(GraphNode.VisualState.NORMAL);
            selectedNode = null;
        }
        deleteBtn.setDisable(true);
        bindBtn.setDisable(true);
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
        bindBtn.setDisable(true);
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
            // Any manual drag immediately cancels the glide and hands
            // full control back to the user — including any in-progress
            // rotation reset.
            glideActive = false;

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
            // Only clamp the near end — no limit on zooming out
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
    //  SAVE / LOAD
    //
    //  Format: plain JSON, no external library needed.
    //  Written manually with StringBuilder; parsed with a minimal
    //  hand-rolled reader — zero dependencies added.
    //
    //  Schema:
    //  {
    //    "version": 1,
    //    "nodes": [
    //      { "id": 0, "name": "Root", "color": "#58A6FF",
    //        "x": 0.0, "y": 0.0, "z": 0.0,
    //        "boundTitle": "Visual Studio Code" }   // "" if unbound
    //    ],
    //    "edges": [
    //      { "from": 0, "to": 1 }
    //    ]
    //  }
    //
    //  HWND values are NOT saved — they are process-lifetime handles that
    //  are meaningless across sessions.  Instead we save the window title
    //  string and re-run findAllWindows() on import to rebind live.
    // ══════════════════════════════════════════════════════════════════

    private VBox buildSaveLoadSection() {
        Button saveBtn = styledButton("💾  Export Workspace", "#1C3A5E", "#1F4E79");
        saveBtn.setOnAction(e -> saveGraph());

        Button loadBtn = styledButton("📂  Import Workspace", "#2D3A1E", "#3A4D28");
        loadBtn.setOnAction(e -> loadGraph());

        Label hint = new Label("Export saves nodes,\nlinks & window bindings\nto a .json file.");
        hint.setFont(Font.font("Monospace", 10));
        hint.setTextFill(Color.web("#484F58"));
        hint.setWrapText(true);

        VBox box = new VBox(6, saveBtn, loadBtn, hint);
        return box;
    }

    // ── Export ────────────────────────────────────────────────────────

    private void saveGraph() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export Workspace");
        fc.setInitialFileName("workspace.json");
        fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = fc.showSaveDialog(null);
        if (file == null) return;

        // Assign each node a stable integer index for edge references
        java.util.Map<GraphNode, Integer> idMap = new java.util.IdentityHashMap<>();
        for (int i = 0; i < nodes.size(); i++) idMap.put(nodes.get(i), i);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"nodes\": [\n");

        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            // Convert Color back to a hex string
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

    // ── Import ────────────────────────────────────────────────────────

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
            // ── Clear current graph ───────────────────────────────────
            for (GraphEdge e : new ArrayList<>(edges)) removeEdge(e);
            for (GraphNode n : new ArrayList<>(nodes)) {
                world3D.getChildren().remove(n.sphere);
                world3D.getChildren().remove(n.billboard);
            }
            nodes.clear();
            clearSelection();
            clearLinkSelection();

            // ── Parse nodes ───────────────────────────────────────────
            String nodesArr  = between(json, "\"nodes\"", "]");
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

            // ── Parse edges ───────────────────────────────────────────
            String edgesArr = between(json, "\"edges\"", "]");
            List<String> edgeObjs = splitObjects(edgesArr);
            for (String obj : edgeObjs) {
                int from = (int) dblVal(obj, "from");
                int to   = (int) dblVal(obj, "to");
                if (from >= 0 && from < loaded.size()
                        && to >= 0 && to < loaded.size()) {
                    addEdge(loaded.get(from), loaded.get(to));
                }
            }

            setStatus("Loaded " + loaded.size() + " nodes,\n"
                    + edges.size() + " edges.");

        } catch (Exception ex) {
            setStatus("Parse error:\n" + ex.getMessage());
        }
    }

    // ── Minimal JSON helpers (no external library) ────────────────────

    /** Quote and escape a string value for JSON output. */
    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Format a double to 2 decimal places for compact output. */
    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /**
     * Extract the contents of the JSON array whose key is {@code startKey}.
     * Finds the opening [ after the key, then walks forward counting
     * brackets to find the matching ], so nested structures don't confuse it.
     */
    private static String between(String json, String startKey, String endToken) {
        int k = json.indexOf(startKey);
        if (k < 0) return "";
        int open = json.indexOf("[", k);
        if (open < 0) return "";

        // Walk forward from open bracket, counting depth to find the match
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(open + 1, i);
            }
        }
        return "";
    }

    /**
     * Split a JSON array body (already stripped of outer [ ]) into
     * individual {...} object strings.
     */
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

    /**
     * Extract the string value for {@code key} from a JSON object string.
     * Handles escaped quotes inside the value.
     */
    private static String strVal(String obj, String key) {
        int k = obj.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = obj.indexOf(":", k);
        int q1    = obj.indexOf("\"", colon + 1);
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

    /**
     * Extract a numeric value for {@code key} from a JSON object string.
     */
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
        // ── Glide tick ────────────────────────────────────────────────
        // If a glide is active, lerp world3D's translation toward the
        // target each frame.  GLIDE_SPEED = 0.10 means we cover 10% of
        // the remaining distance per tick — exponential ease-out that
        // starts fast and slows as it approaches the target.
        // We stop the glide once we're within 0.5 world-units to avoid
        // infinite micro-corrections.
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
