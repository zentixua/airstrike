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

out vec4 fragColor;

const vec3 DUST = vec3(0.60, 0.53, 0.45);
const vec3 SMOKE = vec3(0.30, 0.27, 0.24);
const vec3 HAZE = vec3(0.44, 0.41, 0.38);
// пламя пожаров под стеной (CloudPuffs.FIRE)
const vec3 FIRE = vec3(1.0, 0.541, 0.180);

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

void main() {
    vec2 low = gl_FragCoord.xy;
    ivec2 full = ivec2((floor(low) + 0.5) * PallFrame.z);
    ivec2 size = textureSize(Sampler0, 0);
    float depth = texelFetch(Sampler0, clamp(full, ivec2(0), size - 1), 0).r;
    vec2 ndc = (vec2(full) + 0.5) / vec2(size) * 2.0 - 1.0;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
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
    if (t1 > t0) {
        int steps = int(PallFrame.y);
        float jitter = fract(texelFetch(Sampler2, ivec2(mod(low, 64.0)), 0).r + PallFrame.x * 0.618034);
        float len = t1 - t0, n = float(steps);
        for (int i = 0; i < steps; i++) {
            float u0 = (float(i) + jitter) / n, u1 = (float(i) + 1.0 + jitter) / n;
            float ta = t0 + len * u0 * u0;
            if (ta >= t1) break;
            float ds = min(t0 + len * u1 * u1, t1) - ta;
            vec3 p = dir * ta;
            Sample s = Sample(0.0, 0.0, vec3(0.0), 0.0, vec3(0.0), 0.0);
            slot(PallSlot0, PallGlow0, PallHot0, p, sunDir, s);
            slot(PallSlot1, PallGlow1, PallHot1, p, sunDir, s);
            float st = s.dust + s.haze;
            if (st <= 1e-7) continue;
            float tauSun = max(s.tau, 0.0) / sunY;
            float tauUp = max(s.tau, 0.0);
            // прямой луч солнца — по Бугеру–Ламберту (фаза на сфере в среднем 1, свет солнца — как у белой стены под
            // ним, отсюда ¼), рассеянный многократно — как пропускание толстого слоя (два потока, 1/(1 + τ/4)): густая
            // пыль и в глубине — бурые сумерки, как туча, а не чернота по прямому лучу
            vec3 lit = PallSunLight * (0.25 * phase * exp(-tauSun) + 0.75 / (1.0 + 0.25 * tauSun))
                    + PallSkyLight * (0.3 + 0.7 / (1.0 + 0.5 * tauUp));
            vec3 emit = s.scatter * lit + s.glow * PallFireLight + s.wall * PallGlowOnSmoke;
            float tr = exp(-st * ds);
            light += transmit * emit * (1.0 - tr) / st;
            transmit *= tr;
            if (transmit < 0.005) {
                transmit = 0.0;
                break;
            }
        }
    }
    // негодный итог (NaN, бесконечность — из глубины, матриц или поля) — пропускание −1: накопление возьмёт прошлое
    // этого места, а без него пиксель — без пелены (fx_pall_resolve)
    vec4 res = vec4(light, transmit);
    fragColor = pallValid(res) ? res : vec4(0.0, 0.0, 0.0, -1.0);
}
