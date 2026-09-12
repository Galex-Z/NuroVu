# NuroVu
A Software/Workflow navigation tool which uses 3D Force directed node graphing to represent a window/tab on a desktop, you can open these nodes to be redirected to that specific tab, these nodes can be connected, organised and collapsible. Allowing a person to visualise and navigate their workflow. where the line between thinking and doing blurs
##Summary
An advanced, 3D spatial desktop utility that transforms operating system window management into an interactive, self-organizing three-dimensional knowledge graph, this tool replaces traditional linear taskbars with an immersive 3D workspace constellation.

Built with **JavaFX 3D** and low-level **Windows API integration (JNA)**, the application maps active desktop windows into a fluid network of floating 3D spherical nodes, connected by reactive vector-spring lines.

## 🚀 The Core Innovation

Traditional desktop multitasking forces users to think linearly via flat tabs or static grid switches. When managing large, multi-app workflows, users suffer a steep cognitive penalty. 

This project maps workspaces into a **3D Semantic Nebula**. Related windows (e.g., an IDE, a web browser documentation tab, and a terminal window) naturally cluster together in a 3D field based on user-defined workflows, floating fluidly as an organized orbital structure.

## ✨ Key Features

*   **3D Workspace Nebula:** Spawns active desktop apps as interactable 3D spheres positioned in a XYZ vector space on a dark-mode interstellar canvas.
*   **3D Force-Directed Layout:** A custom physics engine calculating 3D Hooke's spring forces (attraction) and 3D Coulomb forces (repulsion) so nodes organically balance themselves in three dimensions without collision.
*   **Immersive Camera Controls:** Allows the user to rotate, pan, and zoom through their window network using native mouse/keyboard navigation (WASD + mouse drag).
*   **Instant Native Focus:** Double-clicking any floating 3D node instantly sends low-level signals to the Windows kernel to pull that specific app to the foreground.

## 🛠️ Built With

*   **Java 21+** - Core application logic.
*   **JavaFX 3D** - Utilizes built-in hardware-accelerated 3D features (`PerspectiveCamera`, `Sphere`, `PointLight`, and 3D `Group` transforms) to avoid massive third-party rendering engines.
*   **JNA (Java Native Access)** - Low-level Win32 bridge calling `User32.dll` to manipulate the operating system's window focus.
