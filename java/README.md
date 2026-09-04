# Java scratch programs

Standalone demos, unrelated to the Node scanner in the repo root. No build tool
or dependencies -- a JDK is all you need.

    javac LoopDemo.java && java LoopDemo    # for / while / enhanced-for loops
    javac Block3D.java && java Block3D      # clickable 3D block + inspector

`Block3D` renders a cube with plain Java2D: corners are rotated in 3D, projected
with a perspective divide, back-facing sides are culled, and the rest are painted
back-to-front. Drag to rotate, scroll to zoom, click a face to inspect it.
