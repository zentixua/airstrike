#version 150

// Пелена — на кадр (client/fx/layer/PallPass): смешивание ONE, SRC_ALPHA — свет пелены плюс мир за ней, ослабленный.
// Кадр пелены мельче полного: из четырёх ближних его пикселей — с весом по сходству расстояния до мира (у края дома
// пелена неба не ложится на стену и наоборот).

// накопленная пелена (fx_pall_resolve)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;

// z: во сколько раз кадр пелены мельче полного
uniform vec4 PallFrame;

out vec4 fragColor;

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r;
    vec2 at = gl_FragCoord.xy / PallFrame.z - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            ivec2 tf = clamp(ivec2((vec2(t) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
            float d = texelFetch(Sampler1, tf, 0).r;
            float rel = abs(d - depth) / max(min(d, depth), 0.1);
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            w *= 1.0 / (1.0 + 400.0 * rel * rel) + 1e-4;
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    fragColor = sum / total;
}
