Aug 11, 2026
beta23

#Introduced an "Auto-Focus Mode" toggle that smoothly glides the viewport camera to align with any selected graph node. 

Resolved dynamic coordinate-space mismatches across transformed 3D rotations by synchronizing translation panning with an angle-reset animation.

Key Changes

Auto-Focus Glide State: Added an exponential ease-out lerp mechanism (GLIDE_SPEED = 0.10) within the primary AnimationTimer loop to smoothly translate viewport boundaries toward the target node without additional background threads.

Manual Override Safety: Implemented immediate glide cancellation upon detecting any mouse drag input, returning instant manual view control to the user.

Coordinate-Space Reconciliation:Identified a coordinate misalignment bug where translating screen-space boundaries ($X/Y$) directly using world-local node position coordinates ($-x, -y$) caused overshoot or inaccurate framing when the scene was rotated away from origin angles.Solved by animating all four primary transforms simultaneously (translateX, translateY, rotateX, rotateY). As the camera glides toward the node's local position, rotation angles smoothly interpolate back to zero, re-aligning world axes directly with screen space for smooth target framing.

UI Controls: Integrated a dedicated Auto-Focus ToggleButton within the sidebar interface with active state handling.

Claude's conversation:

Good. I have everything I need. Three surgical edits: add state fields, add the UI toggle to the sidebar, hook selectNode to trigger the glide, and add the glide method plus manual-override cancellation in the drag handler.

Edited 2 files
Edited 2 files

Now hook selectNode to trigger the glide, and add the startGlideTo method:

Hook selectNode to trigger glide and add startGlideTo method
Hook selectNode to trigger glide and add startGlideTo method

Now cancel the glide on any drag, and tick the lerp in syncSceneGraph:

Cancel glide on any drag start
Cancel glide on any drag start

Finally, tick the glide lerp in syncSceneGraph:

Edited a file, read a file
Edited a file, read a file

Five targeted edits, nothing else touched. Here's the breakdown.

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
glx: a fix: when you rotate the camera the auto focus doesn't adjust for the different angles
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
glx:something's wrong here, everytime I try to auto focus it shifts to a clearly exaggerated view, lets start fresh, and this time when auto focusing on a node reset the angle
Let me read the current auto-focus and glide code carefully before rewriting.

Viewed 3 files
Viewed 3 files

Good. The cleanest possible approach given the "reset angle" requirement: when auto-focus fires, animate three things simultaneously — rotateX → 0, rotateY → 0, world3D.translateX/Y → -node.x, -node.y. With angles reset to zero, the world axes ARE screen axes, so the simple -node.x/-node.y translation is perfectly correct. No projection math needed.

Edited 2 files
Edited 2 files

Now update the glide tick in syncSceneGraph to also lerp the rotation angles:

Update glide tick to also animate rotateX and rotateY toward zero
Update glide tick to also animate rotateX and rotateY toward zero

Also update the drag cancel to reset the stored rotation correctly when the user overrides:

Edited a file, read a file
Edited a file, read a file

Here's why this is now correct and why the previous attempts weren't.

Why all previous approaches were broken

Attempt 1 (-node.x, -node.y) — correct math, but only at zero rotation. world3D.translateX/Y is in screen space; node.x/y is in world-local space. After any rotation those two spaces diverge, so the pan target was wrong.

Attempt 2 (localToScene() projection) — correct concept, but localToScene() inside a SubScene returns coordinates in SubScene space, not the parent Scene space. The delta was being computed against the wrong origin, causing the exaggerated overshoot.

Why the reset approach is correct

Resetting the angles to zero eliminates the coordinate space mismatch entirely. When rotateX = 0 and rotateY = 0, world3D's local X and Y axes are perfectly aligned with the screen's X and Y axes — they're the same thing. At that point:

glideTargetX = -node.x   ← moves node to screen centre X  ✓
glideTargetY = -node.y   ← moves node to screen centre Y  ✓

No projection. No matrix decomposition. Just correct.

The four values — translateX, translateY, rotateX, rotateY — all lerp simultaneously with the same GLIDE_SPEED factor, so the rotation unwinds and the pan converges in one smooth continuous motion. Because the lerp factor is the same for all four, they all arrive at their targets at the same time, which keeps the node tracking smoothly toward centre throughout the animation rather than arriving in two separate phases.
glx: this is perfect great work
Thank you! That one took a few tries to get right — the coordinate space mismatch between world-local and screen space is a genuinely tricky JavaFX gotcha.

Here's where the project stands now as a complete feature summary, useful if you're writing it up for the hackathon:

3D Graph Engine

Force-directed physics (repulsion + spring attraction) across full X/Y/Z
Spawn damping so new nodes ease in rather than launch away
Billboard labels that always face the camera

Navigation

Left drag → orbit rotation
Right drag → pan
Scroll → zoom
Auto-Focus toggle → smooth glide to selected node with angle reset

Graph Editing

Named node spawn with counter fallback
Link Mode for manual edge creation/removal
Node selection + deletion (button and Delete key)
Edge toggle (click same pair twice to unlink)

OS Integration (Windows)

Window picker on every spawn — full list, live search filter
HWND binding with ⚡ badge on bound nodes
Double-click to focus the bound OS window, preserving its size

Whenever you're ready to keep going — whether that's more features, the visual polish pass, or prep for the pitch — just say the word.
#CODE
Code was too bugged, reconsidering logic.
