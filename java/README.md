# Java scratch programs

Standalone demos, unrelated to the Node scanner in the repo root. No build tool
or dependencies -- a JDK is all you need.

    javac LoopDemo.java && java LoopDemo    # for / while / enhanced-for loops
    ./run.sh                                # Block3D, in your browser

## Block3D

A 3D block you can spin, resize, relight, recolour and click into -- with its
own control panel. It runs two ways from the same renderer:

- **In a browser tab**, served from localhost. The page is only a control
  surface: every frame it shows was drawn by Java and streamed back as an image.
- **As a native desktop window** (Swing), opened by a button on that page or
  started on its own.

```bash
cd java
./run.sh              # compiles, serves http://localhost:8080, opens a browser
./run.sh 9000         # ...on a different port
```

If the port is busy the server takes the next free one and prints the URL.
`Ctrl+C` stops it. Straight to the desktop window, no browser involved:

    javac -d out Block3D.java && java -cp out Block3D

### Controls

Click a face to inspect it -- normal, colour, size in mm, area and corner
coordinates. Drag to tumble, scroll to zoom, shift-drag (or right-drag) to pan.
The rail sets orientation, dimensions, camera, surface and stage options;
**Download this frame** saves a full-resolution PNG. The desktop window adds
hover highlighting, arrow keys to rotate, `+`/`-` to zoom, `R` to reset and
`Space` to toggle the spin.

### How it works

`Block3D.Scene` is the whole renderer, and it draws into any `Graphics2D`:

- eight corners scaled per axis, rotated by a 3x3 matrix built from the three
  Euler angles, then divided through by depth for perspective;
- six quads sorted back-to-front (painter's algorithm) with optional back-face
  culling -- a wireframe ignores culling so the full cage shows;
- flat Lambert shading plus a narrow specular highlight, per face;
- a contact shadow: the corners are dropped onto the floor plane along the light
  direction and the convex hull of those points is filled;
- clicks hit-test the projected quads, nearest front-facing one wins.

That single entry point is why one renderer serves both surfaces.
`Block3D.Viewport` hands it a Swing panel's `Graphics2D`; `Block3DServer` hands
it a `BufferedImage`, encodes the result and writes it to the HTTP response --
so the browser and the desktop window are never out of step.

While you drag or the block spins, the browser asks for 1x JPEG frames so they
keep up; once you stop, one full-resolution PNG replaces the last. The server
binds to `127.0.0.1` only -- it can open windows on the host, so it stays off
the network.

### Files

| File | Purpose |
| --- | --- |
| `Block3D.java` | Renderer (`Scene`), Swing viewport, control panel, inspector |
| `Block3DServer.java` | `HttpServer` -- static files, `/frame`, `/pick`, `/launch` |
| `web/index.html` | The browser control surface (no external assets) |
| `run.sh` | Compile and serve |
