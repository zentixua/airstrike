#version 150

#moj_import <incoming:pall.glsl>

// Пелена — на кадр (client/fx/layer/PallPass): смешивание ONE, SRC_ALPHA — свет пелены плюс мир за ней, ослабленный
// и в тени пелены. Кадр пелены мельче полного: из четырёх ближних его пикселей — с весом по сходству расстояния до мира
// (у края дома пелена неба не ложится на стену и наоборот). Тень — здесь, в каждом пикселе, по полю без шума: в кадре
// пелены альфа — только ослабление (его берут квадраты слоя, fx.fsh), а тень на земле — не их.

// накопленная пелена (fx_pall_resolve)
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (полное разрешение)
uniform sampler2D Sampler1;
// таблица поля (PallField.row)
uniform sampler2D Sampler2;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
// направление на солнце (xyz) и сила солнца днём 0..1 (w)
uniform vec4 PallSun;
// z: во сколько раз кадр пелены мельче полного
uniform vec4 PallFrame;

out vec4 fragColor;

// тень пелены на освещённом солнцем мире в точке p (от глаза): столбы пыли и мглы над ней к солнцу
float shade(vec3 p) {
    float tau = 0.0, dense;
    vec2 column, height;
    if (PallSlot0[1].w > 0.5) {
        pallField(Sampler2, PallSlot0, p, column, height, dense);
        tau += column.x + column.y;
    }
    if (PallSlot1[1].w > 0.5) {
        pallField(Sampler2, PallSlot1, p, column, height, dense);
        tau += column.x + column.y;
    }
    return mix(1.0, 0.4 + 0.6 * exp(-tau / max(PallSun.y, 0.12)), PallSun.w);
}

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
    vec4 pall = sum / total;
    // только на мире: пустоту под горизонтом тень красила бы пятнами
    if (depth < 1e8 && PallSun.w > 0.0 && pall.a > 0.005) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        pall.a *= shade(dir * (depth / max(dot(dir, PallForward), 1e-3)));
    }
    fragColor = pall;
}
