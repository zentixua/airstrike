#version 150

// Наклейка на воду (client/fx/layer/WaterDecal): тёмное кольцо фронта ядерной волны — рябь под ветром за фронтом — и пена
// барашками у эпицентра. Место за пикселем — по расстоянию до мира по оси взгляда (Sampler0, SceneDepth) и матрицам
// кадра; вода ли там — по высоте: верх стоячей воды — 8/9 блока, а дальше прорисовки (LOD DH) — гладь моря: место на её
// высоте или ниже, тогда — пересечение луча с гладью. Профили колец — Sampler1: строка — кольцо, по x — корень доли
// пути от фронта к центру; r — темнота, g — пена.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvProj;
uniform mat4 InvView;
// позиция камеры: x, z — по модулю 4096 (шум), y — как есть
uniform vec3 CamPos;
// x — докуда по горизонтали чанки прорисовки (дальше — LOD DH); y — гладь моря от камеры по высоте; z — колец; w — свет неба
uniform vec4 WaterParams;
// x — 3.912 / дальность видимости, 1/блок; y, z — начало и конец тумана Minecraft; w — 1, если туман цилиндром
uniform vec4 Haze;
// угол пикселя, рад
uniform float Pixel;
// кольца: x, z центра от камеры; радиус фронта по воде; длина профиля за ним, блоков
uniform vec4 Ring0;
uniform vec4 Ring1;
uniform vec4 Ring2;
uniform vec4 Ring3;

out vec4 fragColor;

float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

vec3 unproject(vec2 ndc, float z) {
    vec4 v = InvProj * vec4(ndc, z, 1.0);
    return v.xyz / v.w;
}

vec4 ring(int i) {
    return i == 0 ? Ring0 : (i == 1 ? Ring1 : (i == 2 ? Ring2 : Ring3));
}

void main() {
    float dist = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    if (dist > 1e8) discard;
    vec2 ndc = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0)) * 2.0 - 1.0;
    // точка луча пикселя на расстоянии dist по оси взгляда (вид), потом — от позиции камеры; o — начало луча
    vec3 n = unproject(ndc, -1.0);
    vec3 f = unproject(ndc, 1.0);
    vec3 pv = n + (f - n) * ((dist + n.z) / (n.z - f.z));
    vec3 p = (InvView * vec4(pv, 1.0)).xyz;
    vec3 o = (InvView * vec4(n, 1.0)).xyz;
    float h = fract(CamPos.y + p.y);
    bool water = h > 0.85 && h < 0.925;
    if (!water && length(p.xz) > WaterParams.x) {
        float sea = WaterParams.y;
        if (p.y > sea - 1.2 && p.y < sea + 0.15) {
            water = true;
        } else if (p.y < sea && o.y > sea) {
            p = o + (p - o) * ((sea - o.y) / (p.y - o.y));
            water = true;
        }
    }
    if (!water) discard;

    vec3 ray = p - o;
    float len = max(length(ray), 1e-3);
    // след пикселя на воде, блоков: у пологого луча вдоль луча длиннее
    float foot = max(0.2, len * Pixel / max(abs(ray.y) / len, 0.02));
    float dark = 0.0;
    float foam = 0.0;
    // куда дует ветер за фронтом (от центра кольца с самой густой пеной): пена тянется полосами по ветру
    vec2 wind = vec2(1.0, 0.0);
    int rings = int(WaterParams.z + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= rings) break;
        vec4 rg = ring(i);
        vec2 off = p.xz - rg.xy;
        float r = length(off);
        float behind = rg.z - r;
        if (behind < -foot) continue;
        float edge = smoothstep(-foot, foot, behind);
        float u = sqrt(clamp(behind / rg.w, 0.0, 1.0));
        vec2 prof = textureLod(Sampler1, vec2((u * 63.0 + 0.5) / 64.0, (float(i) + 0.5) / 4.0), 0.0).rg;
        dark = 1.0 - (1.0 - dark) * (1.0 - prof.r * edge);
        if (prof.g * edge > foam && r > 1e-3) wind = off / r;
        foam = max(foam, prof.g * edge);
    }
    if (dark <= 0.002 && foam <= 0.002) discard;

    vec2 w = p.xz + CamPos.xz;
    // кошачьи лапы: пятна ряби разной густоты; мельче пикселя — к среднему
    float paws = noise(w / 23.0) * 0.6 + noise(w / 7.0) * 0.4;
    paws = mix(paws, 0.5, clamp(foot / 7.0, 0.0, 1.0));
    dark = clamp(dark * (0.65 + 0.7 * paws), 0.0, 1.0);
    // пена барашками: мелкие пятна, вытянутые по ветру полосами, доля которых — пена профиля; мельче пикселя — сама доля
    vec2 q = vec2(dot(w, wind), dot(w, vec2(-wind.y, wind.x)));
    float caps = noise(q / vec2(3.5, 1.2)) * 0.55 + noise(q / vec2(12.0, 4.0)) * 0.45;
    foam = mix(smoothstep(1.0 - foam, 1.12 - foam, caps), foam, clamp(foot / 1.5, 0.0, 1.0));

    // дымка воздуха и туман Minecraft гасят наклейку, как частицы
    float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
    float seen = exp(-Haze.x * len) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
    float k = dark * seen;
    float fo = clamp(foam, 0.0, 1.0) * seen;
    float sky = WaterParams.w;
    // рябь отражает меньше неба: вода темнее, с синевой глубины; пена — белая, в свете неба
    vec3 tint = vec3(0.02, 0.035, 0.05) * sky;
    vec3 white = vec3(0.86, 0.9, 0.93) * (0.1 + 0.9 * sky);
    fragColor = vec4(tint * k * (1.0 - fo) + white * fo, k + fo - k * fo);
}
