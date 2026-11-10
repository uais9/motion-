// Tint: the picture's brightness picks a colour between "Map Black To" and "Map White To",
// and Amount to Tint mixes that with the original colours.
uniform vec3 uBlack;
uniform vec3 uWhite;
uniform float uAmount; // 0 to 1

void main() {
  vec4 c = texture2D(uTex, vUv);
  vec3 rgb = c.a > 0.0 ? c.rgb / c.a : vec3(0.0);
  float l = dot(rgb, vec3(0.299, 0.587, 0.114));
  vec3 tinted = mix(uBlack, uWhite, l);
  vec3 outc = mix(rgb, tinted, uAmount);
  gl_FragColor = vec4(outc * c.a, c.a);
}
