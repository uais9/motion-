// Motion Tile: the layer is repeated as tiles. Tile Center, Tile Width/Height and Output Width/Height are as in
// After Effects (sizes are a percentage of the layer). Phase shifts every other row (Horizontal Phase Shift on)
// or column (off) by a fraction of a tile: 360 degrees is one whole tile.
uniform vec2 uCenter;   // where the tile is taken from, in layer pixels (0, 0 = the top left of the layer)
uniform vec2 uTile;     // tile size in pixels
uniform vec2 uOut;      // output size in pixels, around the middle of the layer
uniform float uMirror;  // 1 = every other tile is flipped
uniform float uPhase;   // degrees
uniform float uHPhase;  // 1 = rows shift sideways, 0 = columns shift up and down

void main() {
  vec2 p = vUv * uWork - vec2(uPad);
  vec2 d = abs(p - uLayer * 0.5);
  if (d.x > uOut.x * 0.5 || d.y > uOut.y * 0.5) {
    gl_FragColor = vec4(0.0);
    return;
  }
  vec2 q = p - uCenter;
  float shift = uPhase / 360.0;
  if (uHPhase > 0.5) {
    float row = floor((q.y + uTile.y * 0.5) / uTile.y);
    q.x -= row * shift * uTile.x;
  } else {
    float col = floor((q.x + uTile.x * 0.5) / uTile.x);
    q.y -= col * shift * uTile.y;
  }
  vec2 idx = floor((q + uTile * 0.5) / uTile);
  vec2 loc = q - idx * uTile;
  if (uMirror > 0.5) {
    if (abs(mod(idx.x, 2.0)) > 0.5) loc.x = -loc.x;
    if (abs(mod(idx.y, 2.0)) > 0.5) loc.y = -loc.y;
  }
  vec2 src = uCenter + loc;
  gl_FragColor = texture2D(uTex, (src + vec2(uPad)) / uWork);
}
