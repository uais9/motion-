{
  "id": "gaussian_blur",
  "name": "Gaussian Blur",
  "params": [
    { "key": "blurriness", "label": "Blurriness", "dims": 1, "default": [0], "min": 0, "max": 4000, "step": 0.5 },
    { "key": "dims", "label": "Dimensions", "dims": 1, "default": [0], "min": 0, "max": 2, "step": 0.05 },
    { "key": "repeat", "label": "Repeat Edge", "dims": 1, "default": [0], "min": 0, "max": 1, "step": 0.05 }
  ],
  "pad": "repeat > 0.5 ? 0 : ceil(3 * blurriness / 2)",
  "passes": [
    {
      "shader": "gaussian_blur.frag",
      "when": "blurriness > 0.01 && dims < 1.5",
      "uniforms": { "uStep": ["1 / workW", "0"], "uSigma": ["blurriness / 2"], "uRepeat": ["repeat"] }
    },
    {
      "shader": "gaussian_blur.frag",
      "when": "blurriness > 0.01 && (dims < 0.5 || dims > 1.5)",
      "uniforms": { "uStep": ["0", "1 / workH"], "uSigma": ["blurriness / 2"], "uRepeat": ["repeat"] }
    }
  ]
}
