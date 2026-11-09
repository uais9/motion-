// Drop Shadow, pass 2: blurs the shadow up and down, colours it and puts the layer on top of it.
// uTex is the layer, uTex2 is the sideways-blurred shadow from pass 1.
uniform vec2 uStep;     // one layer pixel up and down, in vUv units
uniform float uSigma;
uniform vec3 uColor;    // 0 to 1
uniform float uOpacity; // 0 to 1
uniform float uOnly;    // 1 = show only the shadow

void main() {
  float sigma = max(uSigma, 0.01);
  float reach = ceil(3.0 * sigma);
  float stride = max(1.0, reach / 24.0);
  float sum = texture2D(uTex2, vUv).a;
  float wsum = 1.0;
  for (int i = 1; i <= 24; i++) {
    float k = float(i) * stride;
    if (k > reach) break;
    float w = exp(-0.5 * k * k / (sigma * sigma));
    sum += (texture2D(uTex2, vUv + uStep * k).a + texture2D(uTex2, vUv - uStep * k).a) * w;
    wsum += 2.0 * w;
  }
  float sa = sum / wsum * uOpacity;
  vec4 shadow = vec4(uColor * sa, sa);
  vec4 orig = texture2D(uTex, vUv);
  gl_FragColor = (uOnly > 0.5) ? shadow : orig + shadow * (1.0 - orig.a);
}
