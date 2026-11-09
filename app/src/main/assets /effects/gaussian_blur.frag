// Gaussian Blur: one separable pass (the .json runs it sideways, then up and down).
// Blurriness is twice the sigma (the same mapping EffectCraft uses for After Effects' Blurriness).
uniform vec2 uStep;    // one layer pixel along the blur direction, in vUv units
uniform float uSigma;  // in layer pixels
uniform float uRepeat; // 1 = repeat the edge pixels

vec4 tap(vec2 uv) {
  if (uRepeat > 0.5) uv = clamp(uv, uBox.xy, uBox.zw);
  return texture2D(uTex, uv);
}

void main() {
  float sigma = max(uSigma, 0.01);
  float reach = ceil(3.0 * sigma);
  float stride = max(1.0, reach / 24.0);
  vec4 sum = tap(vUv);
  float wsum = 1.0;
  for (int i = 1; i <= 24; i++) {
    float k = float(i) * stride;
    if (k > reach) break;
    float w = exp(-0.5 * k * k / (sigma * sigma));
    sum += (tap(vUv + uStep * k) + tap(vUv - uStep * k)) * w;
    wsum += 2.0 * w;
  }
  gl_FragColor = sum / wsum;
}
