#version 150

// Наклейка на воду (client/fx/layer/WaterDecal): тёмное кольцо фронта ядерной волны — рябь под ветром за фронтом — и пена
// барашками у эпицентра. Место за пикселем — по расстоянию до мира по оси взгляда (Sampler0, SceneDepth) и матрицам
// кадра; вода ли там — гладь (поверхность за пикселем горизонтальна, и соседние пиксели на той же высоте: стены, стволы,
// трава, мобы, склоны и края предметов перед небом на той же высоте в блоке — не вода) на высоте верха стоячей воды —
// 8/9 блока (у шейдерпака — ниже, на его волны), а дальше прорисовки (LOD DH) — гладь на высоте моря или место ниже
// моря, тогда — пересечение луча с гладью. Профили колец — Sampler1: строка — кольцо, по x — корень доли пути от фронта
// к центру; r — темнота, g — пена. Шум ряби и пены — у каждого кольца свой, от его центра (у далёких координат мира
// в float нет дробной части, а центр подрыва стоит на месте).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvProj;
uniform mat4 InvView;
// высота камеры, блоки
uniform float CamY;
// x — докуда по горизонтали чанки прорисовки (дальше — LOD DH); y — гладь моря от камеры по высоте; z — колец; w — свет неба
uniform vec4 WaterParams;
// x — 3.912 / дальность видимости, 1/блок; y, z — начало и конец тумана Minecraft; w — 1, если туман цилиндром
uniform vec4 Haze;
// угол пикселя, рад
uniform float Pixel;
// шейдерпак (без него — 0): x — на сколько блоков ниже 8/9 его волны опускают гладь; y — перепад его глади от вершины
// к соседней, блоков; z — дрожание кадра для TAA, пикселей
uniform vec3 Pack;
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

// место мира за пикселем от позиции камеры (у неба — точка далеко на луче: разности с ней — числа); o — начало луча
vec3 scene(ivec2 px, ivec2 size, out vec3 o) {
    float dist = texelFetch(Sampler0, px, 0).r;
    vec2 ndc = (vec2(px) + 0.5) / vec2(size) * 2.0 - 1.0;
    // точка луча на расстоянии dist по оси взгляда (вид), потом — от позиции камеры
    vec3 n = unproject(ndc, -1.0);
    vec3 f = unproject(ndc, 1.0);
    float d = min(dist, 1e6);
    o = (InvView * vec4(n, 1.0)).xyz;
    return (InvView * vec4(n + (f - n) * ((d + n.z) / (n.z - f.z)), 1.0)).xyz;
}

// шаг к соседнему пикселю по оси экрана: из двух соседей — тот, что ближе по высоте (за краем предмета второй — уже фон
// или небо, у берега — суша); за краем кадра соседа нет
vec3 neighbour(ivec2 px, ivec2 axis, vec3 p, ivec2 size) {
    ivec2 a = px + axis;
    ivec2 b = px - axis;
    bool hasA = all(lessThan(a, size));
    bool hasB = all(greaterThanEqual(b, ivec2(0)));
    vec3 o;
    vec3 da = hasA ? scene(a, size, o) - p : vec3(0.0);
    vec3 db = hasB ? p - scene(b, size, o) : vec3(0.0);
    if (!hasA) return db;
    if (!hasB) return da;
    return abs(da.y) <= abs(db.y) ? da : db;
}

vec4 ring(int i) {
    if (i == 0) return Ring0;
    if (i == 1) return Ring1;
    if (i == 2) return Ring2;
    return Ring3;
}

const float PI = 3.14159265;

// барашки: x — вдоль ветра, y — поперёк, блоки
float whitecaps(vec2 q) {
    return noise(q / vec2(3.5, 1.2)) * 0.55 + noise(q / vec2(12.0, 4.0)) * 0.45;
}

// секторов угла у пены: в своём секторе дуга от его оси почти не скошена
const float SECTORS = 24.0;

// барашки сектора k (ось — угол k·2π/24 − π от оси x мира) на радиусе r и угле a: вдоль ветра — радиус, поперёк — дуга
// от оси (у оси сектора — расстояние поперёк радиуса); сектор 24 — это сектор 0
float sectorCaps(float r, float a, float k) {
    float s = mod(k, SECTORS);
    return whitecaps(vec2(r, r * (a - (k * (2.0 * PI / SECTORS) - PI))) + vec2(s * 17.0, s * 31.0));
}

// пена барашками от центра кольца (rel — место от него): ветер за фронтом дует от центра, поэтому полосы — в полярных
// координатах (место от центра, повёрнутое по ветру от того же центра, — это радиус и ноль: пена шла бы кругами). Угол —
// от осей мира, а не от камеры (иначе узор ехал бы вбок, когда камера обходит подрыв), дуга — от оси своего сектора:
// дуга от одной оси на весь круг скашивала бы полосы вдали от неё (вдоль радиуса растёт и дуга). Соседние сектора
// сходятся плавно, с той же силой шума
float caps(vec2 rel) {
    float r = length(rel);
    float a = atan(rel.y, rel.x);
    float t = (a + PI) * (SECTORS / (2.0 * PI));
    float k = floor(t);
    float w = smoothstep(0.25, 0.75, t - k);
    float c = mix(sectorCaps(r, a, k), sectorCaps(r, a, k + 1.0), w);
    return 0.5 + (c - 0.5) * inversesqrt(1.0 - 2.0 * w * (1.0 - w));
}

// верх стоячей воды над низом блока
const float SURFACE = 8.0 / 9.0;

void main() {
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 px = ivec2(gl_FragCoord.xy);
    if (texelFetch(Sampler0, px, 0).r > 1e8) discard;
    vec3 o;
    vec3 p = scene(px, size, o);
    vec3 ray = p - o;
    float len = max(length(ray), 1e-3);
    // наклон поверхности за пикселем — по соседним пикселям; у каждой оси — сосед на той же поверхности
    vec3 across = neighbour(px, ivec2(1, 0), p, size);
    vec3 along = neighbour(px, ivec2(0, 1), p, size);
    vec3 face = cross(across, along);
    float area = length(face);
    // шаг пикселя по глади вдоль луча, блоков
    float reach = len * Pixel / max(abs(ray.y) / len, 0.002);
    // высота по глубине тем грубее, чем дальше и круче вниз луч (глубина 24 бита: ошибка ~ d² у ближней плоскости 0,05);
    // вблизи допуск уже 1/72 блока — верх блоков в 14/16 (сундук, 7 слоёв снега) не вода
    float tol = clamp(0.01 + 2.4e-6 * len * abs(ray.y), 0.01, 0.037);
    // нормаль не круче 20° от вертикали, соседи — на той же высоте, шаг к ним — не скачок к фону за предметом. Перепад
    // к соседу на глади — шум глубины (и волны пака), на стене — шаг пикселя по высоте (len·Pixel), у пикселя боковой
    // грани под краем верха блока вдали — 1/9 блока до соседа на верхе, а у скачка вдоль почти горизонтального луча
    // (край листа, травы, конька крыши перед дальним фоном) — тем больше, чем дальше скачок от шага пикселя по глади:
    // шаг вбок и такой скачок дают горизонталь, но не ту высоту
    float rise = max(abs(across.y), abs(along.y));
    bool level = area > 0.0 && abs(face.y) > 0.94 * area && max(length(across), length(along)) < 3.0 * reach + 0.5
            && rise < 0.002 + 0.1 * len * Pixel + (tol - 0.01) + Pack.y * min(1.0, reach);
    // дрожание кадра шейдерпака сдвигает луч, по которому записана глубина: высота места ошибается на сдвиг × расстояние
    float jitter = Pack.z * Pixel * length(ray.xz);
    float h = fract(CamY + p.y) - SURFACE;
    bool water = level && h > -(tol + jitter + Pack.x) && h < tol + jitter;
    if (!water && length(p.xz) > WaterParams.x) {
        float sea = WaterParams.y;
        if (level && p.y > sea - 1.2 && p.y < sea + 0.15) {
            water = true;
        } else if (p.y < sea && o.y > sea) {
            // ниже моря вдали: LOD без глубины воды показывает дно — вода над ним, на глади
            p = o + (p - o) * ((sea - o.y) / (p.y - o.y));
            water = true;
        }
    }
    if (!water) discard;

    ray = p - o;
    len = max(length(ray), 1e-3);
    // след пикселя на воде, блоков: у пологого луча вдоль луча длиннее
    float foot = max(0.2, len * Pixel / max(abs(ray.y) / len, 0.02));
    float dark = 0.0;
    float foam = 0.0;
    int rings = int(WaterParams.z + 0.5);
    for (int i = 0; i < 4; i++) {
        if (i >= rings) break;
        vec4 rg = ring(i);
        vec2 rel = p.xz - rg.xy;
        float behind = rg.z - length(rel);
        if (behind < -foot) continue;
        float edge = smoothstep(-foot, foot, behind);
        float u = sqrt(clamp(behind / rg.w, 0.0, 1.0));
        vec2 prof = textureLod(Sampler1, vec2((u * 63.0 + 0.5) / 64.0, (float(i) + 0.5) / 4.0), 0.0).rg * edge;
        if (prof.r > 0.002) {
            // кошачьи лапы: пятна ряби разной густоты; мельче пикселя — к среднему
            float paws = noise(rel / 23.0) * 0.6 + noise(rel / 7.0) * 0.4;
            paws = mix(paws, 0.5, clamp(foot / 7.0, 0.0, 1.0));
            dark = 1.0 - (1.0 - dark) * (1.0 - clamp(prof.r * (0.65 + 0.7 * paws), 0.0, 1.0));
        }
        if (prof.g > 0.002) {
            // пена барашками: мелкие пятна, вытянутые по ветру полосами, доля которых — пена профиля; мельче пикселя —
            // сама доля
            float f = prof.g;
            if (foot < 1.5) f = mix(smoothstep(1.0 - f, 1.12 - f, caps(rel)), f, foot / 1.5);
            foam = max(foam, f);
        }
    }
    if (dark <= 0.002 && foam <= 0.002) discard;

    // дымка воздуха и туман Minecraft гасят наклейку, как частицы
    float fd = Haze.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p);
    float seenAir = exp(-Haze.x * len) * (1.0 - smoothstep(Haze.y, Haze.z, fd));
    float k = dark * seenAir;
    float fo = clamp(foam, 0.0, 1.0) * seenAir;
    float sky = WaterParams.w;
    // рябь отражает меньше неба: вода темнее, с синевой глубины; пена — белая, в свете неба
    vec3 tint = vec3(0.02, 0.035, 0.05) * sky;
    vec3 white = vec3(0.86, 0.9, 0.93) * (0.1 + 0.9 * sky);
    fragColor = vec4(tint * k * (1.0 - fo) + white * fo, k + fo - k * fo);
}
