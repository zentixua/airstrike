#version 150

#moj_import <incoming:pall.glsl>

// Пелена после ударной волны (client/fx/layer/PallPass): луч из глаза через пиксель кадра пониженного разрешения —
// до мира за пикселем (SceneDepth, с LOD Distant Horizons) или до выхода из рамки пелены. Шаги — гуще у начала
// (квадратично), начало сдвинуто синим шумом по кадрам (накопление сглаживает зерно, fx_pall_resolve). Плотность —
// поле подрыва по таблице × форма клубов по трёхмерному шуму, снесённому ветром; свет — солнце через столб над точкой
// (по таблице, и шаг шума к солнцу — тень соседнего клуба) с фазой Хеньи–Гринстейна и рассеянным многократно, небо
// сверху, пожары снизу и свет стены пыли (шар, раскалённая шапка, пожары под ней — как у её клубов: у фронта пелена
// продолжает цвет стены).
// Выход: rgb — свет пелены перед миром, a — сколько света мира проходит (только ослабление: его же берут квадраты слоя,
// fx.fsh; тень пелены на мире — в fx_pall_composite).

// расстояние до мира за пикселем по оси взгляда (полное разрешение)
uniform sampler2D Sampler0;
// таблица поля (PallField.row): 4 строки — пыль и мгла двух слотов
uniform sampler2D Sampler1;
// синий шум 64×64 (BlueNoise)
uniform sampler2D Sampler2;
// клубы (R) и мелкие края (G), плитка без швов (VolumeNoise); свой блок текстуры — PallPass
uniform sampler3D PallNoise;

uniform mat4 InvViewProj;
uniform vec3 PallForward;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
uniform vec3 PallMin;
uniform vec3 PallMax;
// направление на солнце (xyz) и сила солнца днём 0..1 (w)
uniform vec4 PallSun;
// свет солнца, неба и пожаров на экране (rgb)
uniform vec3 PallSunLight;
uniform vec3 PallSkyLight;
uniform vec3 PallFireLight;
// свет стены пыли на пелене слота (CloudPuffs.pallLight): x — свет шара и шапки сверху, y — накал гриба, z — шесть
// радиусов шара, w — край зоны пожаров (блоки); Hot — цвет накала (rgb) и высота шара или шапки над землёй (w, блоки)
uniform vec4 PallGlow0;
uniform vec4 PallGlow1;
uniform vec4 PallHot0;
uniform vec4 PallHot1;
// сила этого отсвета на дыме при свете неба кадра (CloudPuffs.glowOnSmoke)
uniform float PallGlowOnSmoke;
// x: номер кадра (сдвиг шагов), y: шагов, z: во сколько раз кадр мельче полного, w: дальше стольких блоков луч не идёт
uniform vec4 PallFrame;
// огненный шторм (client/nuclear/FireStorm, pall.glsl): карты огня слотов (R — пламя, G — дым столбов, B — G × подъём;
// уменьшенные копии — скопления источников), слоты огня, рамка шторма (там шаги луча гуще); смерчи кадра (до четырёх,
// по столбцу: основание и радиус ядра; высота, сила, секунды, закрутка; наклон, пламя, зерно — PallPass.Frame)
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
uniform vec3 PallFireMin;
uniform vec3 PallFireMax;
uniform mat4 PallWhirlPos;
uniform mat4 PallWhirlShape;
uniform mat4 PallWhirlLean;

out vec4 fragColor;

const vec3 DUST = vec3(0.60, 0.53, 0.45);
const vec3 SMOKE = vec3(0.30, 0.27, 0.24);
const vec3 HAZE = vec3(0.44, 0.41, 0.38);
// пламя пожаров под стеной (CloudPuffs.FIRE)
const vec3 FIRE = vec3(1.0, 0.541, 0.180);
// дым столбов огненного шторма: сажа
const vec3 SOOT = vec3(0.21, 0.19, 0.17);
// пламя смерча — горячее пламени города: желтее
const vec3 WHIRL_FIRE = vec3(1.0, 0.58, 0.2);
// скорость ветра у ядра смерча, блоков/с (у настоящих — 10–50 м/с; быстрее — на кадре одно размытое вращение)
const float WHIRL_SPEED = 15.0;

float hg(float g, float c) {
    float k = 1.0 + g * g - 2.0 * g * c;
    return (1.0 - g * g) / (k * sqrt(k));
}

// Сдвиг плитки шума по крупному шуму (в долях плитки): плитка повторяется через tile блоков, и с высоты повтор виден
// полосами; сдвиг до шестой части плитки, плавный на двух плитках, его ломает (сильнее — клубы тянутся в полосы),
// а распределение значений не меняет (тот же шум в другом месте — среднее формы клубов остаётся 1)
vec3 warpAt(mat4 S, vec3 p) {
    vec3 q = vec3(p.x - S[0].x - S[1].x, 0.0, p.z - S[0].y - S[1].y) / S[3].y;
    vec2 w = texture(PallNoise, q * 0.07 + vec3(0.31, 0.5, 0.67)).rg - 0.5;
    return vec3(w.x, 0.0, w.y) * 0.3;
}

// клубы слота в точке p (от глаза): шум снесён вместе с пылью и медленно клубится вверх
vec2 noiseAt(mat4 S, vec3 p, vec3 warp) {
    float tile = S[3].y, t = S[3].z;
    vec3 q = vec3(p.x - S[0].x - S[1].x, (p.y - S[0].z) * 1.3 - 0.3 * min(t, 600.0), p.z - S[0].y - S[1].y) / tile + warp;
    float r = texture(PallNoise, q).r;
    float g = texture(PallNoise, q * 3.1 + vec3(0.37, 0.11 * min(t, 600.0) / tile, 0.71)).g;
    return vec2(r, g);
}

struct Sample {
    float dust;
    float haze;
    vec3 scatter;
    float glow;
    vec3 wall;
    float tau;
};

// Пропускание к источнику света через τ — как у рассеянного света солнца: прямой луч и многократно рассеянный (два
// потока); в густой пыли — бурые сумерки, а не чернота
float pallPass(float tau) {
    return 0.25 * exp(-tau) + 0.75 / (1.0 + 0.25 * tau);
}

// плотность и то, что нужно свету, от одного слота — прибавить к s; G, H — свет стены пыли слота (PallGlow, PallHot)
void slot(mat4 S, vec4 G, vec4 H, vec3 p, vec3 sunDir, inout Sample s) {
    if (S[1].w < 0.5) return;
    vec2 column, height;
    float dense;
    vec4 f = pallField(Sampler1, S, p, column, height, dense);
    if (f.x + f.y <= 1e-7) {
        s.tau += column.x + column.y;
        return;
    }
    float fill = clamp(dense * 3.0, 0.0, 1.0) * (1.0 - smoothstep(0.4, 1.0, height.x));
    vec3 warp = warpAt(S, p);
    float shape = pallShape(noiseAt(S, p, warp), fill);
    float d = f.x * shape, h = f.y * mix(1.0, shape, 0.5);
    // свет стены пыли (CloudPuffs.wallColor): шар и шапка сверху, пожары под пылью — как у её клубов на том же месте
    float r = length(p.xz - S[0].xy), up = clamp(height.x, 0.0, 1.0);
    // пожары под пылью днём едва видны, как их зарево на мгле (NukePall: fireLight): за волной стена проходит за секунды,
    // а пелена висит над горящим городом минуты — днём во всю силу она выходила бы ровно-оранжевой
    float lit = pallLit(r, up, G.x, G.z), burn = pallBurn(r, up, G.y, G.w) * (1.0 - 0.75 * PallSun.w);
    float heat = max(lit, burn);
    // тень соседнего клуба: шум на пятую плитки к главному свету против среднего — к солнцу или, пока он ярче, к шару
    // и шапке над эпицентром или к пожарам под пылью (ночью это весь свет пыли)
    float key = clamp(heat * PallGlowOnSmoke / (heat * PallGlowOnSmoke + 0.5 * PallSun.w + 0.02), 0.0, 1.0);
    vec3 toLight = vec3(S[0].x, S[0].z + H.w, S[0].y) - p;
    // светят пожары внизу — тень клуба к ним, а не к шапке
    if (burn > lit) toLight = vec3(0.0, -1.0, 0.0);
    float sunStep = 0.2 * S[3].y;
    vec3 keyDir = mix(sunDir, toLight / max(length(toLight), 1e-3), key);
    float toward = pallShape(noiseAt(S, p + keyDir / max(length(keyDir), 1e-3) * sunStep, warp), fill);
    float clump = clamp(toward - 1.0, -0.8, 1.5) * f.x * sunStep * 0.5;
    s.tau += column.x + column.y + clump;
    s.dust += d;
    s.haze += h;
    s.scatter += d * mix(DUST, SMOKE, f.z) + h * HAZE;
    // пожары снизу освещают и пыль, и мглу — каждую по её белизне
    s.glow += (d * mix(0.6, 0.3, f.z) + h * 0.45) * f.w;
    if (heat > 1e-4) {
        // свет сверху — через столб над точкой, снизу (пожары) — через пыль под ней. Без «?:» внутри выражения: Veil
        // (его несёт Sable) пересобирает шейдер и теряет скобки вокруг него (client-fx.md, ShaderSourceTest)
        vec3 color = H.rgb;
        float tauLight = column.x + column.y;
        if (burn > lit) {
            color = FIRE;
            tauLight = (f.x + f.y) * max(p.y - S[0].z, 0.0);
        }
        s.wall += (d * mix(1.0, 0.6, f.z) + h * 0.5) * color * heat * pallPass(max(tauLight + clump, 0.0));
    }
}

// место столба в клетке карты c (доли клетки от её середины, до ±0,25): у каждой клетки своё, столбы не стоят рядами
vec2 fireSpot(ivec2 c) {
    // хеш без sin (дешевле на шаге луча): клетки карты — целые до нескольких сотен
    vec3 p = fract(vec3(vec2(c).xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return (fract((p.xx + p.yz) * p.zy) - 0.5) * 0.5;
}

// Огненный шторм слота в точке p (от глаза): x — ослабление дыма столбов (1/блок), y — зарево пожаров снизу (свет на
// единицу белизны). Зарево — пламя со всей площади шириной ~2h под точкой (копия карты, где клетка — эта ширина), с
// мерцанием; столб — подача дыма под точкой, снесённой ветром по высоте (fireBend). Пока столб уже клетки
// (fireWidth), у каждой из четырёх ближних клеток — свой столб на её месте: густота — подача клетки × площадь клетки
// / площадь столба (дыма столько же), между столбами чисто; шире — с копии карты, где клетка — ширина столба: дым
// растекается вширь, и густота падает как площадь. cone — ширина пикселя здесь, блоки: мельче её карта не читается
// (вдали столбы не рябят).
vec2 storm(sampler2D map, mat4 S, mat4 F, vec3 p, float cone) {
    if (F[0].x <= 0.0) return vec2(0.0);
    float h = p.y - S[0].z, cell = F[0].y, top = F[0].z, t = F[2].z;
    vec2 rel = p.xz - S[0].xy;
    float flame = textureLod(map, fireUv(S, F, p.xz), max(fireGlowLod(h, cell), fireLod(cone, cell))).r;
    float glow = 0.0;
    if (flame > 1e-4) {
        float flicker = 0.55 + 0.9 * texture(PallNoise, vec3(rel / 700.0, t * 0.021)).r;
        // свет пожаров гасит дым под точкой: у земли — яркий, к пятой части верха шторма — втрое слабее; остаток —
        // тусклый красный исподний свет пелены до самого верха
        float up = max(h, 0.0);
        glow = F[1].w * flame * flicker * (0.75 * exp(-up / (0.2 * top)) + 0.25 * exp(-up / top));
    }
    float smoke = 0.0;
    float col = fireColumn(h, top);
    if (col > 0.0) {
        vec2 shift = F[1].xy * fireBend(h, F[0].w);
        vec2 uv = fireUv(S, F, p.xz - shift);
        float w = fireWidth(h, cell), wide = max(w, cone);
        // столб встаёт за подъём дыма: у земли — вся подача (G), выше — доля подъёма (B)
        float rise = smoothstep(0.0, 0.3 * top, h);
        float spread = smoothstep(0.35, 0.6, wide / cell);
        float feed = 0.0;
        if (spread > 0.0) {
            vec3 m = textureLod(map, uv, fireLod(wide, cell)).rgb;
            feed = spread * mix(m.g, m.b, rise);
        }
        if (spread < 1.0) {
            ivec2 size = textureSize(map, 0);
            vec2 g = uv * vec2(size);
            ivec2 base = ivec2(floor(g - 0.5));
            float area = cell * cell / (3.14159 * w * w), pillars = 0.0;
            for (int j = 0; j <= 1; j++) {
                for (int i = 0; i <= 1; i++) {
                    ivec2 c = base + ivec2(i, j);
                    if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, size))) continue;
                    vec3 m = texelFetch(map, c, 0).rgb;
                    vec2 d = (g - vec2(c) - 0.5 - fireSpot(c)) * cell;
                    pillars += mix(m.g, m.b, rise) * area * exp(-dot(d, d) / (w * w));
                }
            }
            feed += (1.0 - spread) * pillars;
        }
        if (feed > 1e-4) {
            // клубы поднимаются со скоростью дыма и сносятся вместе со столбом
            vec3 q = vec3(rel.x - shift.x, h - 0.5 * F[1].z * min(t, 3000.0), rel.y - shift.y) / (2.0 * cell);
            smoke = F[2].y * feed * col * pallShape(texture(PallNoise, q).rg, 0.6);
        }
    }
    return vec2(smoke, glow);
}

// Огненный смерч i (столбец матриц смерчей): вход и выход луча dir в цилиндр вокруг него (с запасом на расширение
// вверх и наклон); вход ≥ выхода — мимо
vec2 whirlSpan(int i, vec3 dir) {
    vec4 P = PallWhirlPos[i], A = PallWhirlShape[i], L = PallWhirlLean[i];
    if (A.y <= 0.0) return vec2(1.0, 0.0);
    vec2 c = P.xy + L.xy * 0.5 * A.x;
    float R = P.w * 2.6 + length(L.xy) * 0.5 * A.x;
    float a = dot(dir.xz, dir.xz), b = dot(dir.xz, c), cc = dot(c, c) - R * R;
    float disc = b * b - a * cc;
    if (a < 1e-8 || disc <= 0.0) return vec2(1.0, 0.0);
    float sq = sqrt(disc);
    vec2 t = vec2(b - sq, b + sq) / a;
    if (abs(dir.y) > 1e-5) {
        float ta = P.z / dir.y, tb = (P.z + A.x) / dir.y;
        t = vec2(max(t.x, min(ta, tb)), min(t.y, max(ta, tb)));
    } else if (P.z > 0.0 || P.z + A.x < 0.0) {
        return vec2(1.0, 0.0);
    }
    return vec2(max(t.x, 0.0), t.y);
}

// Смерч i в точке q (от глаза): x — ослабление дыма (1/блок), y — свет пламени (на блок пути). Ядро шире кверху;
// закрутка — вихрь Лэмба — Озеена (угловая скорость (1 − e^(−r²/rc²))/r²: у оси — как твёрдое тело, дальше спадает),
// шум поворачивается по ней два полупериода со сдвигом и смешивается (как течение по карте: иначе закрутка копилась бы
// без конца в тонкие спирали), сверху — смещение по завихрённости шума (curl noise: поле без стоков, клубы рвутся,
// а не текут); всё поднимается.
vec2 whirlAt(int i, vec3 q) {
    vec4 P = PallWhirlPos[i], A = PallWhirlShape[i], L = PallWhirlLean[i];
    float y = q.y - P.z, yn = y / A.x;
    if (yn < 0.0 || yn > 1.0) return vec2(0.0);
    vec2 rel = q.xz - P.xy - L.xy * y;
    float rc = P.w * (0.45 + 1.4 * yn * yn);
    float r2 = dot(rel, rel) / (rc * rc);
    if (r2 > 6.0) return vec2(0.0);
    // смещение по завихрённости поля ψ (шум): v = (∂ψ/∂z, −∂ψ/∂x)
    float e = 0.04;
    vec3 c0 = vec3(rel.x / (4.0 * P.w), y / (1.5 * A.x) - 0.08 * A.z + L.w, rel.y / (4.0 * P.w));
    float psi = texture(PallNoise, c0).g;
    float dx = texture(PallNoise, c0 + vec3(e, 0.0, 0.0)).g - psi, dz = texture(PallNoise, c0 + vec3(0.0, 0.0, e)).g - psi;
    vec2 w = rel + vec2(dz, -dx) / e * 0.06 * rc;
    float vortex = r2 > 1e-4 ? (1.0 - exp(-r2)) / r2 : 1.0;
    float omega = A.w * 1.75 * WHIRL_SPEED / rc * vortex;
    float period = 3.0, ph = A.z / period;
    float a1 = omega * fract(ph) * period, a2 = omega * fract(ph + 0.5) * period;
    float k = 1.0 - abs(2.0 * fract(ph) - 1.0);
    vec2 w1 = mat2(cos(a1), sin(a1), -sin(a1), cos(a1)) * w, w2 = mat2(cos(a2), sin(a2), -sin(a2), cos(a2)) * w;
    float lift = y / (0.4 * A.x) - 0.35 * A.z + L.w;
    float n1 = texture(PallNoise, vec3(w1.x / (1.6 * P.w), lift, w1.y / (1.6 * P.w))).r;
    float n2 = texture(PallNoise, vec3(w2.x / (1.6 * P.w), lift + 0.5, w2.y / (1.6 * P.w))).r;
    float n = mix(n2, n1, k);
    float env = smoothstep(0.0, 0.06, yn) * (1.0 - smoothstep(0.7, 1.0, yn));
    float smoke = 0.2 * A.y * exp(-r2) * env * smoothstep(0.25, 0.7, n) * 2.0;
    float flame = 0.14 * L.z * A.y * exp(-2.5 * r2) * smoothstep(0.0, 0.03, yn) * (1.0 - smoothstep(0.1, 0.45, yn))
            * smoothstep(0.4, 0.75, n) * 3.0;
    return vec2(smoke, flame);
}

// Смерч i вдоль луча на [ta, tb]: свет (rgb) и пропускание (a); lit — свет неба и солнца там, glow — зарево снизу
vec4 whirl(int i, vec3 dir, float ta, float tb, float jitter, vec3 lit, vec3 glow) {
    vec3 light = vec3(0.0);
    float tr = 1.0, ds = (tb - ta) / 10.0;
    for (int k = 0; k < 10; k++) {
        vec3 q = dir * (ta + (float(k) + jitter) * ds);
        vec2 w = whirlAt(i, q);
        if (w.x + w.y <= 1e-6) continue;
        float yn = (q.y - PallWhirlPos[i].z) / PallWhirlShape[i].x;
        // дым смерча в свете неба и в огне у основания; пламя светит само
        vec3 sc = SOOT * (lit + glow + WHIRL_FIRE * 2.0 * PallWhirlLean[i].z * PallWhirlShape[i].y * (1.0 - yn) * (1.0 - yn));
        float a = exp(-w.x * ds);
        light += tr * (sc * (1.0 - a) + WHIRL_FIRE * w.y * ds);
        tr *= a;
    }
    return vec4(light, tr);
}

// где шаг u (0..1) луча [t0, t1] с рамкой шторма [fa, fb] на нём: доля a шагов — до шторма (гуще у глаза), b — в нём
// (ровно; если шторм начинается у глаза — тоже гуще у глаза), остальные — за ним. Без шторма (a = 1, fa = t1) —
// просто гуще у глаза
float along(float u, float t0, float fa, float fb, float t1, float a, float b) {
    if (u < a) {
        float x = u / a;
        return t0 + (fa - t0) * x * x;
    }
    if (u < a + b) {
        float x = (u - a) / b;
        float k = a > 0.0 ? x : x * x;
        return fa + (fb - fa) * k;
    }
    return fb + (t1 - fb) * (u - a - b) / max(1.0 - a - b, 1e-6);
}

void main() {
    vec2 low = gl_FragCoord.xy;
    ivec2 full = ivec2((floor(low) + 0.5) * PallFrame.z);
    ivec2 size = textureSize(Sampler0, 0);
    float depth = texelFetch(Sampler0, clamp(full, ivec2(0), size - 1), 0).r;
    vec2 ndc = (vec2(full) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    // ширина пикселя кадра лучей на расстоянии 1
    vec4 nextPoint = InvViewProj * vec4(ndc + vec2(0.0, 2.0 * PallFrame.z / float(size.y)), 1.0, 1.0);
    float pixel = length(normalize(nextPoint.xyz / nextPoint.w) - dir);
    float axis = max(dot(dir, PallForward), 1e-3);
    float scene = pallScene(depth);
    if (scene < 1e8) scene /= axis;
    if (scene >= 1e8 && dir.y < 0.0) {
        // мира за пикселем нет, а луч идёт вниз (без Distant Horizons — пустота за дальностью прорисовки): он кончается
        // у земли под эпицентром. Пелена ниже неё — для низин рядом, сквозь пустоту под горизонтом её не видно
        float ground = 1e9;
        if (PallSlot0[1].w > 0.5) ground = PallSlot0[0].z;
        if (PallSlot1[1].w > 0.5) ground = min(ground, PallSlot1[0].z);
        if (ground < 1e8) scene = max(ground / dir.y, 0.0);
    }
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(min(span.y, scene), t0 + PallFrame.w);
    vec3 sunDir = PallSun.xyz;
    float sunY = max(sunDir.y, 0.12);
    float c = dot(dir, sunDir);
    float phase = 0.75 * hg(0.5, c) + 0.25 * hg(-0.2, c);
    vec3 light = vec3(0.0);
    float transmit = 1.0;
    float jitter = fract(texelFetch(Sampler2, ivec2(mod(low, 64.0)), 0).r + PallFrame.x * 0.618034);
    // смерчи: отрезок луча и свет каждого — заранее, в луч пелены — когда шаг дойдёт до входа (до мира за пикселем)
    mat4 whirlLight = mat4(0.0);
    vec4 whirlIn = vec4(1e9);
    for (int i = 0; i < 4; i++) {
        vec2 sp = whirlSpan(i, dir);
        sp.y = min(sp.y, scene);
        if (sp.y <= sp.x) continue;
        // свет там: небо и солнце сквозь пелену над серединой, зарево пожаров снизу
        vec3 mid = dir * (0.5 * (sp.x + sp.y));
        Sample m = Sample(0.0, 0.0, vec3(0.0), 0.0, vec3(0.0), 0.0);
        slot(PallSlot0, PallGlow0, PallHot0, mid, sunDir, m);
        slot(PallSlot1, PallGlow1, PallHot1, mid, sunDir, m);
        float tm = max(m.tau, 0.0);
        vec3 lit = PallSunLight * (0.25 * phase * exp(-tm / sunY) + 0.75 / (1.0 + 0.25 * tm / sunY)) + PallSkyLight * (0.3 + 0.7 / (1.0 + 0.5 * tm));
        vec3 glow = storm(Sampler3, PallSlot0, PallFire0, mid, 0.0).y * PallFire0[3].rgb + storm(Sampler4, PallSlot1, PallFire1, mid, 0.0).y * PallFire1[3].rgb;
        whirlLight[i] = whirl(i, dir, sp.x, sp.y, jitter, lit, glow);
        whirlIn[i] = sp.x;
    }
    if (t1 > t0) {
        int steps = int(PallFrame.y);
        float n = float(steps);
        // рамка шторма на луче: туда — половина шагов (столбы мельче шага по всей пелене)
        float fa = t1, fb = t1, a = 1.0, b = 0.0;
        vec2 fs = pallBox(dir, PallFireMin, PallFireMax);
        if (PallFireMax.x > PallFireMin.x && min(fs.y, t1) - max(fs.x, t0) > 1.0) {
            fa = max(fs.x, t0);
            fb = min(fs.y, t1);
            a = fa - t0 > 1.0 ? 0.25 : 0.0;
            float after = t1 - fb > 1.0 ? 0.2 : 0.0;
            b = 1.0 - a - after;
        }
        for (int i = 0; i < steps; i++) {
            float u0 = (float(i) + jitter) / n, u1 = (float(i) + 1.0 + jitter) / n;
            float ta = along(u0, t0, fa, fb, t1, a, b);
            if (ta >= t1) break;
            float ds = min(along(u1, t0, fa, fb, t1, a, b), t1) - ta;
            for (int k = 0; k < 4; k++) {
                if (whirlIn[k] <= ta) {
                    light += transmit * whirlLight[k].rgb;
                    transmit *= whirlLight[k].a;
                    whirlIn[k] = 1e9;
                }
            }
            vec3 p = dir * ta;
            Sample s = Sample(0.0, 0.0, vec3(0.0), 0.0, vec3(0.0), 0.0);
            slot(PallSlot0, PallGlow0, PallHot0, p, sunDir, s);
            slot(PallSlot1, PallGlow1, PallHot1, p, sunDir, s);
            vec2 f0 = vec2(0.0), f1 = vec2(0.0);
            if (all(greaterThanEqual(p, PallFireMin)) && all(lessThanEqual(p, PallFireMax))) {
                f0 = storm(Sampler3, PallSlot0, PallFire0, p, ta * pixel);
                f1 = storm(Sampler4, PallSlot1, PallFire1, p, ta * pixel);
            }
            float smoke = f0.x + f1.x;
            float st = s.dust + s.haze + smoke;
            if (st <= 1e-7) continue;
            float tauSun = max(s.tau, 0.0) / sunY;
            float tauUp = max(s.tau, 0.0);
            // прямой луч солнца — по Бугеру–Ламберту (фаза на сфере в среднем 1, свет солнца — как у белой стены под
            // ним, отсюда ¼), рассеянный многократно — как пропускание толстого слоя (два потока, 1/(1 + τ/4)): густая
            // пыль и в глубине — бурые сумерки, как туча, а не чернота по прямому лучу
            vec3 lit = PallSunLight * (0.25 * phase * exp(-tauSun) + 0.75 / (1.0 + 0.25 * tauSun))
                    + PallSkyLight * (0.3 + 0.7 / (1.0 + 0.5 * tauUp));
            vec3 emit = s.scatter * lit + s.glow * PallFireLight + s.wall * PallGlowOnSmoke;
            // огненный шторм: дым столбов в свете неба и солнца, а снизу на всём — зарево пожаров, каждому по его белизне
            // (пыль светлее мглы, сажа темнее всех)
            emit += smoke * SOOT * lit + (0.5 * s.dust + 0.4 * s.haze + 0.3 * smoke) * (f0.y * PallFire0[3].rgb + f1.y * PallFire1[3].rgb);
            float tr = exp(-st * ds);
            light += transmit * emit * (1.0 - tr) / st;
            transmit *= tr;
            if (transmit < 0.005) {
                transmit = 0.0;
                break;
            }
        }
    }
    // смерчи за последним шагом (до мира)
    for (int k = 0; k < 4; k++) {
        if (whirlIn[k] < 1e8) {
            light += transmit * whirlLight[k].rgb;
            transmit *= whirlLight[k].a;
        }
    }
    // негодный итог (NaN, бесконечность — из глубины, матриц или поля) — пропускание −1: накопление возьмёт прошлое
    // этого места, а без него пиксель — без пелены (fx_pall_resolve)
    vec4 res = vec4(light, transmit);
    fragColor = pallValid(res) ? res : vec4(0.0, 0.0, 0.0, -1.0);
}
