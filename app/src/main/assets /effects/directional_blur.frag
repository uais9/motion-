// Directional Blur: the picture is averaged along a line, half the length each way from the pixel.
// Direction 0 is sideways, 90 is up and down (it turns clockwise). This is the "motion blur" look.
uniform vec2 uStepLen; // the whole blur length along the direction, in vUv units

void main() {
  vec4 sum = vec4(0.0);
  for (int i = 0; i < 64; i++) {
    float t = (float(i) + 0.5) / 64.0 - 0.5;
    sum += texture2D(uTex, vUv + uStepLen * t);
  }
  gl_FragColor = sum / 64.0;
}
