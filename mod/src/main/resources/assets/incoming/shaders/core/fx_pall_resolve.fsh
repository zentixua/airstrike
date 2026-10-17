#version 150

// Пелена: накопление по кадрам (client/fx/layer/PallPass). Луч этого кадра сдвинут синим шумом — зерно; с прошлыми
// кадрами в том же месте мира (перенос по глубине мира за пикселем, не дальше рамки пелены) оно сглаживается. Прошлое
// зажато в пределы соседей этого кадра: где пелена на экране сдвинулась (её край за домом), след не тянется.

// этот кадр (fx_pall)
uniform sampler2D Sampler0;
// накопленное (прошлый кадр этого прохода)
uniform sampler2D Sampler1;
// расстояние до мира за пикселем по оси взгляда (полное разрешение)
uniform sampler2D Sampler2;

uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
uniform vec3 PallForward;
// xyz: глаз этого кадра от глаза прошлого; w: 1 — прошлое есть
uniform vec4 PallReproject;
// z: во сколько раз кадр мельче полного; w: дальше стольких блоков перенос не считает
uniform vec4 PallFrame;

out vec4 fragColor;

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(Sampler0, 0);
    vec4 cur = texelFetch(Sampler0, px, 0);
    if (PallReproject.w < 0.5) {
        fragColor = cur;
        return;
    }
    vec4 lo = cur, hi = cur;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec4 n = texelFetch(Sampler0, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0);
            lo = min(lo, n);
            hi = max(hi, n);
        }
    }
    ivec2 fullSize = textureSize(Sampler2, 0);
    ivec2 full = clamp(ivec2((vec2(px) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
    float depth = texelFetch(Sampler2, full, 0).r;
    vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    float t = min(depth / max(dot(dir, PallForward), 1e-3), PallFrame.w);
    vec4 prev = PrevViewProj * vec4(dir * t + PallReproject.xyz, 1.0);
    if (prev.w <= 0.0) {
        fragColor = cur;
        return;
    }
    vec2 uv = prev.xy / prev.w * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = cur;
        return;
    }
    vec4 history = clamp(texture(Sampler1, uv), lo, hi);
    fragColor = mix(cur, history, 0.9);
}
