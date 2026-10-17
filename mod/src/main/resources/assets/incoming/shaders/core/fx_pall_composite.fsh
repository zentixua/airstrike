#version 150

#moj_import <incoming:pall.glsl>

// Пелена — на кадр (client/fx/layer/PallPass): смешивание ONE, SRC_ALPHA — свет пелены плюс мир за ней, ослабленный
// и в тени пелены. Кадр пелены мельче полного: из четырёх ближних его пикселей — с весом по сходству расстояния до мира
// (у края дома пелена неба не ложится на стену и наоборот). Тень — здесь, в каждом пикселе, по полю без шума: в кадре
// пелены альфа — только ослабление (его берут квадраты слоя, fx.fsh), а тень на земле — не их. Пламя горящего города
// на поверхностях (огненный шторм, client/nuclear/FireStorm) — тоже здесь: по расстоянию до мира за пикселем (и на LOD
// Distant Horizons), за пеленой — ослабленное ею.

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
// огненный шторм: карты огня слотов, слоты огня (pall.glsl), шум для пятен и мерцания (свой блок текстуры — PallPass)
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
uniform sampler3D PallNoise;

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

// Пламя горящего города на поверхности в точке p (от глаза), dist — до неё, pixel — ширина пикселя там (блоки): где
// горит — пятна по шуму (дома и дворы) с силой клетки карты, мерцает; мельче пикселя — средняя доля пятен (вдали не
// рябит). Ближе FLAME_NEAR (F[2].w) тает: там пятно краски на блоках видно, а горит настоящий огонь ванили. И ровный
// отсвет пожаров на всём вокруг (ночью горящий город рыжий).
vec3 flames(sampler2D map, mat4 S, mat4 F, vec3 p, float dist, float pixel) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    // облака в расстоянии до мира тоже есть: на них не горит
    if (h < -80.0 || h > 200.0 || p.y > F[3].w - 6.0) return vec3(0.0);
    vec2 uv = fireUv(S, F, p.xz);
    float r = textureLod(map, uv, 0.0).r, around = textureLod(map, uv, 2.5).r;
    if (r + around <= 1e-3) return vec3(0.0);
    vec2 rel = p.xz - S[0].xy;
    float t = F[2].z;
    float spots = smoothstep(0.52, 0.72, texture(PallNoise, vec3(rel.x / 70.0, p.y / 90.0, rel.y / 70.0)).r);
    spots = mix(spots, 0.3, smoothstep(4.0, 16.0, pixel));
    float flicker = 0.55 + 0.9 * texture(PallNoise, vec3(rel.x / 25.0, t * 0.4, rel.y / 25.0)).g;
    float near = smoothstep(0.5 * F[2].w, 1.5 * F[2].w, dist);
    float e = F[2].x * min(r, 1.5) * spots * flicker;
    vec3 hot = mix(F[3].rgb, vec3(1.0, 0.72, 0.36), clamp(e - 0.6, 0.0, 0.5));
    return near * (hot * e + F[3].rgb * (0.12 * F[1].w * around));
}

void main() {
    ivec2 fullSize = textureSize(Sampler1, 0), lowSize = textureSize(Sampler0, 0);
    ivec2 full = ivec2(gl_FragCoord.xy);
    float depth = pallScene(texelFetch(Sampler1, clamp(full, ivec2(0), fullSize - 1), 0).r);
    vec2 at = gl_FragCoord.xy / PallFrame.z - 0.5;
    ivec2 base = ivec2(floor(at));
    vec2 f = at - vec2(base);
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int j = 0; j <= 1; j++) {
        for (int i = 0; i <= 1; i++) {
            ivec2 t = clamp(base + ivec2(i, j), ivec2(0), lowSize - 1);
            ivec2 tf = clamp(ivec2((vec2(t) + 0.5) * PallFrame.z), ivec2(0), fullSize - 1);
            float d = pallScene(texelFetch(Sampler1, tf, 0).r);
            float rel = abs(d - depth) / max(min(d, depth), 0.1);
            // билинейный вес без «?:» внутри выражения: Veil теряет скобки вокруг него (ShaderSourceTest)
            float w = mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j));
            w *= 1.0 / (1.0 + 400.0 * rel * rel) + 1e-4;
            sum += texelFetch(Sampler0, t, 0) * w;
            total += w;
        }
    }
    vec4 pall = sum / total;
    if (PallFire0[0].x > 0.0 || PallFire1[0].x > 0.0) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0), nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 / float(fullSize.y)), 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        float dist = depth / max(dot(dir, PallForward), 1e-3);
        if (depth >= 1e8) {
            // мира за пикселем нет (дальше прорисовки без Distant Horizons): луч вниз кончается у земли под эпицентром,
            // как в fx_pall, — горящий город за краем прорисовки виден огнями на ней
            float ground = 1e9;
            if (PallSlot0[1].w > 0.5) ground = PallSlot0[0].z;
            if (PallSlot1[1].w > 0.5) ground = min(ground, PallSlot1[0].z);
            dist = dir.y < -1e-3 && ground < 0.0 ? ground / dir.y : -1.0;
        }
        if (dist > 0.0) {
            float pixel = dist * length(normalize(nextPoint.xyz / nextPoint.w) - dir);
            vec3 p = dir * dist;
            // пламя светит само: за пеленой — ослабленное ею, тень пелены его не трогает
            pall.rgb += (flames(Sampler3, PallSlot0, PallFire0, p, dist, pixel) + flames(Sampler4, PallSlot1, PallFire1, p, dist, pixel)) * pall.a;
        }
    }
    // только на мире: пустоту под горизонтом тень красила бы пятнами
    if (depth < 1e8 && PallSun.w > 0.0 && pall.a > 0.005) {
        vec2 ndc = (vec2(full) + 0.5) / vec2(fullSize) * 2.0 - 1.0;
        vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
        vec3 dir = normalize(farPoint.xyz / farPoint.w);
        pall.a *= shade(dir * (depth / max(dot(dir, PallForward), 1e-3)));
    }
    // накопленная пелена годна всегда (fx_pall_resolve), но мир кадра после неё не должен пострадать ни при каком входе
    fragColor = pallValid(pall) ? pall : vec4(0.0, 0.0, 0.0, 1.0);
}
