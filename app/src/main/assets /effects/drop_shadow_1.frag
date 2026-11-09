// Drop Shadow, pass 1: the layer's alpha, moved by the shadow's offset and blurred sideways.
// Direction 0 is up and it turns clockwise (135 = down and to the right), as in After Effects.
uniform vec2 uStep;    // one layer pixel sideways, in vUv units
uniform vec2 uOffset;  // the shadow's offset, in vUv units
uniform float uSigma;  // softness / 2, in layer pixels

void main() {
  float sigma = max(uSigma, 0.01);
  float reach = ceil(3.0 * sigma);
  float stride = max(1.0, reach / 24.0);
  vec2 base = vUv - uOffset;
  float sum = texture2D(uTex, base).a;
  float wsum = 1.0;
  for (int i = 1; i <= 24; i++) {
    float k = float(i) * stride;
    if (k > reach) break;
    float w = exp(-0.5 * k * k / (sigma * sigma));
    sum += (texture2D(uTex, base + uStep * k).a + texture2D(uTex, base - uStep * k).a) * w;
    wsum += 2.0 * w;
  }
  gl_FragColor = vec4(0.0, 0.0, 0.0, sum / wsum);
}
