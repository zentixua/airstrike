#version 150

#moj_import <incoming:pall.glsl>

// Пелена после ударной волны (client/fx/layer/PallPass): луч из глаза через пиксель кадра пониженного разрешения —
// до мира за пикселем (SceneDepth, с LOD Distant Horizons; мира нет — до рельефа под пеленой, PallGround) или до выхода
// из рамки пелены. Шаги — гуще у начала
// (квадратично), начало сдвинуто синим шумом по кадрам (накопление сглаживает зерно, fx_pall_resolve). Плотность —
// поле подрыва по таблице × клубы по трёхмерному шуму, снесённому ветром: крупные и в них мелкие — те, что луч здесь
// различает. Свет (client/nuclear/PallLight): пелена над точкой и под ней — плоский слой, в нём два потока Эддингтона
// после дельта-Эддингтона: солнце и отсвет шара и шапки — пучками сверху, небо сверху, пожары под пылью (свет стены пыли:
// у фронта пелена продолжает её цвет) — снизу, земля под слоем; клубы у точки (выборки шума к главному свету) — её тень
// или просвет: глубже в слой или ближе к его краю. К глазу точка рассеивает пучки с фазой Хеньи–Гринстейна, рассеянный
// свет — по Эддингтону; вещество — альбедо ω' и асимметрия g' пыли, дыма, мглы и водяной пыли (pall.glsl): тонкая
// пелена светится по толще, толстая — цвета стены пыли и брызг, в густой пыли свет гаснет медленнее прямого луча.
// Свет — в линейных долях (перенос линеен по излучению); кадр Minecraft — значения экрана: на выходе цвет пелены как
// вещества (свет, делённый на её непрозрачность) переведён в экран (pallDisplay) и ярче 0,6 — плечом, как у плёнки.
// Цвета экрана (стена пыли и брызг, сажа, пламя) — в линейные (pallLinear), отсвет стены и зарево пожаров, что на экране
// прибавлены к свету неба, — разностью переводов (pallLift); свет огня — не ярче самого пламени (pallFlame).
// Выход: rgb — цвет пелены на экране, умноженный на её непрозрачность (свет пелены перед миром), a — сколько света мира
// проходит (только ослабление: его же берут квадраты слоя, fx.fsh, — цвет пелены там rgb / (1 − a); тень пелены на мире
// — в fx_pall_composite).

// расстояние до мира за пикселем по оси взгляда (полное разрешение)
uniform sampler2D Sampler0;
// таблица поля (PallField.row): 4 строки — пыль (с брызгами над водой) и мгла двух слотов
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
// свет солнца и неба, линейный (белая стена в полдень — 1); зарево пожаров под мглой на белой стене — на экране
uniform vec3 PallSunLight;
uniform vec3 PallSkyLight;
uniform vec3 PallFireLight;
// свет неба кадра на экране (белая стена: днём 1, ночью — как у клубов стены, FarView.ambient): к нему стена пыли на экране
// прибавляет отсвет, а пелена — его линейную разность (pallLift)
uniform float PallBase;
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
// рельеф и вода под пеленой слотов (client/nuclear/PallGround, pall.glsl: pallFloor, pallWater) — карты и их числа; мир
// кадра кончается не ближе PallFloorFrom блоков по земле от глаза
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform float PallFloorFrom;
// вода слотов (pall.glsl): базисная волна и есть ли под пеленой вода
uniform vec4 PallWater0;
uniform vec4 PallWater1;
// стена пыли слотов (pall.glsl, client/nuclear/BlastWall): голова-вал и облако Вильсона
uniform mat4 PallWall0;
uniform mat4 PallWall1;
uniform vec4 PallWilson0;
uniform vec4 PallWilson1;

out vec4 fragColor;

// Цвет толстого слоя (по нему альбедо веществ, pall.glsl: PALL_DUST_W и др.): пыль — как стена пыли, дым, мгла;
// водяная пыль — белая, как стена брызг над водой (CloudPuffs.SPRAY: пелена над водой продолжает её цвет)
const vec3 DUST = vec3(0.60, 0.53, 0.45);
const vec3 SMOKE = vec3(0.30, 0.27, 0.24);
const vec3 HAZE = vec3(0.44, 0.41, 0.38);
const vec3 MIST = vec3(0.902, 0.918, 0.929);
// пламя пожаров под стеной (CloudPuffs.FIRE)
const vec3 FIRE = vec3(1.0, 0.541, 0.180);
// дым столбов огненного шторма и смерчей: сажа (цвет толстого слоя; альбедо — PALL_SOOT_W); SOOT_LINEAR — она же
// в линейном (pallLinear)
const vec3 SOOT = vec3(0.21, 0.19, 0.17);
const vec3 SOOT_LINEAR = vec3(0.0322762, 0.0258975, 0.0202762);
// пламя смерча — горячее пламени города: желтее (на экране; WHIRL_FIRE_LINEAR — в линейном)
const vec3 WHIRL_FIRE = vec3(1.0, 0.58, 0.2);
const vec3 WHIRL_FIRE_LINEAR = vec3(1.0, 0.3016766, 0.0289912);
// доля зарева округи (fireAround): остальное — зарево под точкой, у земли в зоне оно и так покрывает половину неба
const float AROUND = 0.6;
// шагов поиска рельефа на луче в пустоту (pallFloor): клетка карты вдали — не меньше шага
const int FLOOR_STEPS = 24;
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

// Клубы слота — множитель густоты, в среднем 1: шум снесён вместе с пылью и медленно клубится вверх. Каскад: крупные
// клубы — по плитке (pallShape), в них мелкие — по плитке вчетверо мельче (pallDetail), пока луч их различает (fx_pall:
// slot, fine). Поле одно и то же при любом месте глаза — близко у него видно больше подробностей, а не другие клубы.
// Место точки p (от глаза) в плитке шума
vec3 clumpUv(mat4 S, vec3 p, vec3 warp) {
    float tile = S[3].y, t = S[3].z;
    return vec3(p.x - S[0].x - S[1].x, (p.y - S[0].z) * 1.3 - 0.3 * min(t, 600.0), p.z - S[0].y - S[1].y) / tile + warp;
}

// крупные клубы в месте q плитки
float coarseAt(mat4 S, vec3 q, float fill) {
    float tile = S[3].y, t = S[3].z;
    float r = texture(PallNoise, q).r;
    float g = texture(PallNoise, q * 3.1 + vec3(0.37, 0.11 * min(t, 600.0) / tile, 0.71)).g;
    return pallShape(vec2(r, g), fill);
}

// мелкие клубы в месте q плитки (множитель крупных)
float fineAt(vec3 q) {
    return pallDetail(textureLod(PallNoise, q * 4.0 + vec3(0.53, 0.29, 0.17), 0.0).r);
}

// Отклонение толщи от средней на пути к свету по клубам одного масштаба (их величина — scale/8): выборки у точки (a) и на
// 0,06 и 0,22 масштаба к свету (b, c), с весами на 0,15 масштаба (дальше клубы уже чужие — их тень в среднем, в столбе над
// точкой); в долях масштаба × ослабление
float clumpShade(float a, float b, float c) {
    return clamp(0.02 * (a - 1.0) + 0.06 * (b - 1.0) + 0.07 * (c - 1.0), -0.12, 0.24);
}

struct Sample {
    // пелена в точке с клубами: ослабление (1/блок), Σ ослабление × альбедо ω' (по каналам), Σ ослабление × асимметрия g'
    float sigma;
    vec3 albedo;
    float phase;
    // свет пожаров снизу на мгле слота (PallField.row) — Σ ослабление × ω' × его линейный свет
    vec3 fire;
    // слой над точкой по таблице (без шума): толща над ней, толща всего слоя (pallField: slab), его вещество (Σ толща ×
    // ω', × g') и вода под ним (Σ толща × доля воды)
    float tau;
    float slab;
    vec3 slabAlbedo;
    float slabPhase;
    float water;
    // свет стены пыли (CloudPuffs.pallLight), линейный: пучок шара и шапки сверху (rgb — что от него на белой стене), Σ силы
    // пучка × косинус к вертикали и × косинус к лучу зрения; пожары под пылью — свет снизу (rgb)
    vec3 cap;
    float capUp;
    float capView;
    vec3 burn;
    // тень своего и соседних клубов — толща против средней к солнцу, к шару и шапке, к небу сверху и к пожарам снизу
    float shadeSun;
    float shadeCap;
    float shadeUp;
    float shadeDown;
    // клубы пелены здесь (крупные × мелкие, среднее 1): ими же клубится и широкий дым шторма — та же масса воздуха
    float clumps;
};

Sample emptySample() {
    return Sample(0.0, vec3(0.0), 0.0, vec3(0.0), 0.0, 0.0, vec3(0.0), 0.0, 0.0, vec3(0.0), 0.0, 0.0, vec3(0.0), 0.0, 0.0, 0.0, 0.0, 1.0);
}

// Плечо, как у плёнки: на экране до 0,6 — как есть, ярче — плавно к 1 (наклон в 0,6 тот же). Кадр срезает всё ярче 1: ночью
// в отсвете пожаров клубы сливались бы в ровный оранжевый цвет. По самому яркому каналу, остальные — в той же доле:
// оттенок не меняется (по каналам отдельно белая водяная пыль в отсвете шара выходила розовой)
vec3 shoulder(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.6) return c;
    return c * ((0.6 + 0.4 * (1.0 - exp(-(m - 0.6) / 0.4))) / m);
}

// ---------------------------------------------------------------- голова стены пыли (pall.glsl: pallWall*)

// Клубы головы стены: (x) — 0..1 (равномерно, как канал плитки), (y) — мелкие клубы (множитель, в среднем 1; fine — их
// доля, как у пелены). Шум в плитке V[2].w — у вала (доля roll) в плитке, повёрнутой с ним (rolled — от оси вала), у хвоста
// в неподвижной (still — от оси кольца и над рельефом: поднятая пыль остаётся позади головы); вдоль кольца — две фазы
// (BlastWall.slot): клубы стоят на своём угле theta и растут с головой, фаза, что растянулась, сменяется новой с другими
// клубами, пока другая в полной силе. Смесь — с прежним разбросом (иначе в середине смены клубы бледнели бы вдвое).
// Смесь, а не выбор сильнейшей: выбор менял бы клубы скачком — на кадре шов
vec2 wallNoise(mat4 V, vec2 rolled, vec2 still, float theta, float roll, float fine) {
    float sum = 0.0, norm = 0.0, dsum = 0.0, dnorm = 0.0, tile = max(V[2].w, 1.0);
    for (int j = 0; j < 2; j++) {
        float wj = j == 0 ? V[2].z : 1.0 - V[2].z;
        if (wj < 0.02) continue;
        float arc = theta * 0.1591549 * V[2][j];
        vec3 off = fract(vec3(V[3][j]) * vec3(0.618034, 0.414214, 0.732051));
        vec3 qr = vec3(rolled / tile, arc) + off, qs = vec3(still / tile, arc) + off.zxy;
        float a = wj * roll, b = wj * (1.0 - roll);
        if (a > 0.02) {
            sum += a * (textureLod(PallNoise, qr, 0.0).r - 0.5);
            norm += a * a;
        }
        if (b > 0.02) {
            sum += b * (textureLod(PallNoise, qs, 0.0).r - 0.5);
            norm += b * b;
        }
        if (fine > 0.0) {
            dsum += wj * fineAt(roll >= 0.5 ? qr : qs);
            dnorm += wj;
        }
    }
    float detail = dnorm > 0.0 ? dsum / dnorm : 1.0;
    return vec2(clamp(0.5 + sum / sqrt(max(norm, 1e-6)), 0.0, 1.0), mix(1.0, detail, fine));
}

// Голова стены в точке h (pallWallAt) с клубами: ослабление, 1/блок. Клубы выедают огибающую с края на WALL_BITE (впадины
// глубже, выступы — до огибающей): край головы — круглые клубы с мягким краем WALL_SOFT, внутри — сплошная пыль; в них —
// мелкие клубы, пока луч их различает (fine; у тени клубов — 0)
float wallAt(mat4 V, WallAt h, float fine) {
    float s = h.s - h.P.z, w = 1.5 * h.P.x;
    float inside = pallWallInside(s, h.z, h.P.x, h.P.y);
    float fade = pallWallFade(s, h.z, h.P.x, h.P.y);
    if (inside <= 0.0 || fade <= 0.0 || h.P.w <= 0.0) return 0.0;
    // у переднего края пыль идёт вверх, поверху — назад: точка в плитке вала — повёрнутая назад на его угол
    vec2 u = vec2(s - w, h.z - 0.5 * h.P.y);
    float c = cos(V[1].x), sn = sin(V[1].x);
    vec2 rolled = vec2(u.x * c - u.y * sn, u.x * sn + u.y * c);
    float roll = 1.0 - smoothstep(0.4, 1.4, (s - w) / max(w, 0.001));
    vec2 n = wallNoise(V, rolled, vec2(length(h.rel), h.z), atan(h.rel.y, h.rel.x), roll, fine);
    return h.P.w * fade * smoothstep(0.0, WALL_SOFT, inside - WALL_BITE * (1.0 - n.x)) * n.y;
}

// точка h, сдвинутая на v (блоки): та же вода и тот же рельеф под ней
WallAt wallMove(mat4 V, WallAt h, vec3 v) {
    WallAt m = h;
    m.rel = h.rel + v.xz;
    m.s = V[0].x - length(m.rel);
    m.z = h.z + v.y;
    return m;
}

// Толща средней головы (без клубов) от точки h по направлению v (единичному) на длину len, n шагов
float wallChord(mat4 V, WallAt h, vec3 v, float len, int n) {
    float sum = 0.0, ds = len / float(n);
    for (int i = 0; i < n; i++) {
        WallAt m = wallMove(V, h, v * ((float(i) + 0.5) * ds));
        sum += pallWallMean(m.s - m.P.z, m.z, m.P.x, m.P.y);
    }
    return sum * ds * h.P.w;
}

// Голова стены слота в точке (h — pallWallAt) — в s. Это та же пыль, что пелена, только гуще и с валом: тот же свет
// (свет стены — пелены у фронта, CloudPuffs.pallLight), то же вещество (в зоне пожаров — с дымом и сажей, над водой —
// брызги). Голова не плоский слой: в неё свет входит и сбоку — рассеянный свет в точке — по меньшей из толщ над ней и до
// её переднего края (как глубина под верхом слоя); пучки (солнце, шар и шапка) — по толще средней головы к ним (вблизи —
// по самим клубам: тень соседних клубов к главному свету keyDir, иначе голова выходит плоской), а не столбом над точкой.
// fireR — край зоны пожаров (блоки), r — точка от эпицентра по земле
void wall(mat4 V, WallAt h, vec3 keyDir, vec3 sunDir, vec3 lightDir, float fireR, float r, float cone, bool pall, inout Sample s) {
    float tile = max(V[2].w, 1.0), fine = 1.0 - smoothstep(0.5, 1.0, cone * 32.0 / tile);
    float hd = wallAt(V, h, fine);
    // ни головы здесь, ни пелены слота — свет не нужен
    if (hd <= 0.0 && !pall) return;
    float hm = h.P.w * pallWallMean(h.s - h.P.z, h.z, h.P.x, h.P.y);
    // тень клубов: две выборки клубов к главному свету (на 0,06 и 0,2 плитки) и сама точка — против средней там
    float dev = 0.0;
    if (hd > 0.0) {
        WallAt h1 = wallMove(V, h, keyDir * (0.06 * tile)), h2 = wallMove(V, h, keyDir * (0.2 * tile));
        float m1 = h.P.w * pallWallMean(h1.s - h1.P.z, h1.z, h1.P.x, h1.P.y);
        float m2 = h.P.w * pallWallMean(h2.s - h2.P.z, h2.z, h2.P.x, h2.P.y);
        dev = ((hd - hm) * 0.03 + (wallAt(V, h1, 0.0) - m1) * 0.1 + (wallAt(V, h2, 0.0) - m2) * 0.17) * tile;
        dev = clamp(dev, -3.0, 6.0);
    }
    // толща средней головы над точкой, до её переднего края и под точкой (до низа головы)
    float reach = 4.5 * h.P.x + h.P.y;
    vec2 out1 = h.rel / max(length(h.rel), 1e-3);
    float up = wallChord(V, h, vec3(0.0, 1.0, 0.0), max(h.P.y - h.z, 0.0), 4);
    float side = wallChord(V, h, vec3(out1.x, 0.0, out1.y), max(h.s - h.P.z, 0.0) + 1.0, 4);
    float down = wallChord(V, h, vec3(0.0, -1.0, 0.0), max(h.z + 0.5 * h.P.y, 0.0), 2);
    float depth = min(up, side);
    float toSun = wallChord(V, h, sunDir, reach, 4), toCap = wallChord(V, h, lightDir, reach, 4);
    float smoke = 0.77 * (1.0 - smoothstep(0.92 * max(fireR, 1e-3), 1.04 * max(fireR, 1e-3), r));
    vec3 w = mix(mix(PALL_DUST_W, PALL_SMOKE_W, smoke), PALL_MIST_W, h.wet);
    float g = mix(PALL_DUST_G, PALL_MIST_G, h.wet);
    s.tau += depth;
    s.slab += depth + down;
    s.slabAlbedo += (depth + down) * w;
    s.slabPhase += (depth + down) * g;
    s.water += (depth + down) * h.wet;
    // пучки идут по s.tau / μ: голова в нём — глубина рассеянного света, а пучку — его толща к свету
    s.shadeSun += toSun + dev * max(dot(keyDir, sunDir), 0.0) - depth / max(sunDir.y, 0.12);
    s.shadeCap += toCap + dev * max(dot(keyDir, lightDir), 0.0) - depth / clamp(lightDir.y, 0.12, 1.0);
    s.shadeUp += dev * max(keyDir.y, 0.0);
    s.shadeDown += dev * max(-keyDir.y, 0.0);
    s.sigma += hd;
    s.albedo += hd * w;
    s.phase += hd * g;
}

// Пелена слота в точке p — прибавить к s; V — стена пыли слота, ground, R — карта рельефа и воды слота и её числа, W — вода
// слота, G, H — свет стены пыли слота (PallGlow, PallHot), dir — луч зрения, cone — меньшее, что луч здесь различает (блоки)
void slot(mat4 S, mat4 V, sampler2D ground, vec4 R, vec4 W, vec4 G, vec4 H, vec3 p, vec3 sunDir, vec3 dir, float cone, inout Sample s) {
    if (S[1].w < 0.5) return;
    // пол слоя — рельеф под точкой (карта PallGround): в долине пелена над ней — тоже слой, и пожары и земля под ней —
    // на дне, а не на уровне земли под эпицентром; карты нет — земля под эпицентром
    float floorUp = R.x > 0.0 ? pallGroundAt(ground, S, R, p.xz) - S[0].z : 0.0;
    PallAt f = pallField(Sampler1, ground, S, R, W, p, floorUp);
    WallAt head = pallWallAt(ground, S, V, R, W, p, floorUp);
    vec3 dustW = mix(PALL_DUST_W, PALL_SMOKE_W, f.smoke);
    s.tau += f.column.x + f.column.y + f.column.z;
    s.slab += f.slab.x + f.slab.y + f.slab.z;
    s.slabAlbedo += f.slab.x * dustW + f.slab.y * PALL_HAZE_W + f.slab.z * PALL_MIST_W;
    s.slabPhase += (f.slab.x + f.slab.y) * PALL_DUST_G + f.slab.z * PALL_MIST_G;
    // пыль — над водой, если её подняло с воды; водяная пыль — всегда
    s.water += (f.slab.x + f.slab.y) * f.wet + f.slab.z;
    // Свет шара, шапки и пожаров на пыли (CloudPuffs.pallLight): шар и шапка — пучок к верху слоя (pallLit), пожары под
    // пылью — свет снизу (pallBurn); сила — отсвет на дыме (× PallGlowOnSmoke), делённый на цвет пыли, — на экране поверх
    // света неба, в линейный — разностью (pallLift): толстая пыль в нём на экране — цвета отсвета. Свет пожаров — не ярче
    // пламени (pallFlame): ночью над зоной пожаров отсвет на экране уже цвета огня, и обращение через цвет пыли дало бы
    // свет втрое ярче огня — пыль у земли вышла бы ярче 1 и ровной (плечо). Пожары под пылью днём едва видны, как их зарево на мгле (NukePall: fireLight): за волной
    // стена проходит за секунды, а пелена висит над горящим городом минуты. Под водой пожаров нет (стена брызг их и не
    // берёт): вода — там, откуда подняло пыль
    float r = length(p.xz - S[0].xy);
    float lit = pallLit(r, 1.0, G.x, G.z), burn = pallBurn(r, 0.0, G.y, G.w) * (1.0 - 0.75 * PallSun.w) * (1.0 - f.wet);
    vec3 toLight = vec3(S[0].x, S[0].z + H.w, S[0].y) - p;
    vec3 lightDir = toLight / max(length(toLight), 1e-3);
    vec3 cap = pallLift3(PallBase, H.rgb * (lit * PallGlowOnSmoke) / DUST);
    float capI = cap.r + cap.g + cap.b;
    s.cap += cap;
    s.capUp += capI * lightDir.y;
    s.capView += capI * dot(dir, lightDir);
    if (burn > 0.0) s.burn += pallFlame(PallBase, FIRE * (burn * PallGlowOnSmoke) / DUST, FIRE);
    // главный свет — солнце или, пока он ярче, шар и шапка над эпицентром или пожары под пылью (ночью это весь свет
    // пыли); светят пожары внизу — к ним, а не к шапке
    float heat = max(lit, burn) * PallGlowOnSmoke;
    float key = clamp(heat / (heat + 0.5 * PallSun.w + 0.02), 0.0, 1.0);
    vec3 keyLight = lightDir;
    if (burn > lit) keyLight = vec3(0.0, -1.0, 0.0);
    vec3 keyDir = mix(sunDir, keyLight, key);
    keyDir /= max(length(keyDir), 1e-3);
    bool pall = f.sigma.x + f.sigma.y + f.sigma.z > 1e-7;
    if (head.P.w > 0.0) wall(V, head, keyDir, sunDir, lightDir, G.w, r, cone, pall, s);
    if (!pall) return;
    float fill = clamp(f.dense * 3.0, 0.0, 1.0) * (1.0 - smoothstep(0.4, 1.0, f.height.x));
    vec3 warp = warpAt(S, p);
    // мелкие клубы — около 1/32 плитки: видны во всю силу, пока луч различает вдвое мельче (fine — их доля; 0 — мельче
    // пикселя и шага: их среднее, 1, без выборки)
    float tile = S[3].y, fine = 1.0 - smoothstep(0.5, 1.0, cone * 32.0 / tile);
    vec3 q = clumpUv(S, p, warp);
    float coarse = coarseAt(S, q, fill), detail = fine > 0.0 ? fineAt(q) : 1.0;
    float shape = coarse * mix(1.0, detail, fine);
    s.clumps = shape;
    float d = f.sigma.x * shape, h = f.sigma.y * mix(1.0, shape, 0.5), m = f.sigma.z * shape;
    // Тень своего и соседних клубов: толща к главному свету против средней (clumpShade). Густота — крупные × мелкие, и
    // отклонение толщи — сумма по масштабам: крупных клубов — по выборкам на 0,06 и 0,22 плитки к свету, мелких — вчетверо
    // ближе, × густота крупного клуба, в котором они (на пути в пару мелких клубов крупный почти тот же). Тень крупных —
    // большие светлые и тёмные массы (в густой пыли — несколько единиц толщи), мелких — их край вблизи. Две выборки на
    // масштаб, а не одна: одна не видит, у края своего клуба точка или в его глубине, — тень выходит пятнами, а не светлой
    // стороной клуба и тёмной. Выборок шума на тень: крупных — по две (по два канала), мелких — по одной и только пока
    // луч их различает
    float sigmaShade = f.sigma.x + f.sigma.z;
    float clump = clumpShade(coarse, coarseAt(S, clumpUv(S, p + keyDir * (0.06 * tile), warp), fill),
            coarseAt(S, clumpUv(S, p + keyDir * (0.22 * tile), warp), fill)) * sigmaShade * tile;
    if (fine > 0.0) {
        clump += fine * clumpShade(detail, fineAt(clumpUv(S, p + keyDir * (0.015 * tile), warp)),
                fineAt(clumpUv(S, p + keyDir * (0.055 * tile), warp))) * sigmaShade * coarse * (0.25 * tile);
    }
    // тень — каждому свету, насколько он со стороны главного
    s.shadeSun += clump * max(dot(keyDir, sunDir), 0.0);
    s.shadeCap += clump * max(dot(keyDir, lightDir), 0.0);
    s.shadeUp += clump * max(keyDir.y, 0.0);
    s.shadeDown += clump * max(-keyDir.y, 0.0);
    vec3 sc = d * dustW + h * PALL_HAZE_W + m * PALL_MIST_W;
    s.sigma += d + h + m;
    s.albedo += sc;
    s.phase += (d + h) * PALL_DUST_G + m * PALL_MIST_G;
    if (f.fire > 0.0) s.fire += sc * pallFlame(PallBase, f.fire * PallFireLight, PallFireLight / max(PallFireLight.r, 1e-4));
}

// Вещество слоя пелены над точкой и под ней — по толще его частей: альбедо по каналам и асимметрия (a)
vec4 slabMatter(Sample s) {
    vec4 w = vec4(PALL_DUST_W, PALL_DUST_G);
    if (s.slab > 1e-6) w = vec4(s.slabAlbedo, s.slabPhase) / s.slab;
    return w;
}

// земля под слоем: суша или вода
float slabGround(Sample s) {
    float water = s.slab > 1e-6 ? clamp(s.water / s.slab, 0.0, 1.0) : 0.0;
    return mix(PALL_LAND, PALL_SEA, water);
}

// Рассеянный свет слоя пелены в точке от света сверху (солнце, шар и шапка, небо), I0 + c·I1 по каналам (pallDiffuse;
// без альбедо точки) — на глубине, сдвинутой тенью клубов к нему
vec3 scattered(Sample s, float c) {
    float slab = max(s.slab, s.tau);
    vec4 w = slabMatter(s);
    float g = w.a, ground = slabGround(s);
    float muS = max(PallSun.y, 0.12);
    float capI = s.cap.r + s.cap.g + s.cap.b;
    float muC = clamp(s.capUp / max(capI, 1e-6), 0.12, 1.0);
    float top = clamp(s.tau + s.shadeUp, 0.0, slab);
    vec3 d = vec3(pallDiffuse(w.r, g, top, slab, ground, muS, PallSunLight.r, muC, s.cap.r, PallSkyLight.r, 0.0, c),
            pallDiffuse(w.g, g, top, slab, ground, muS, PallSunLight.g, muC, s.cap.g, PallSkyLight.g, 0.0, c),
            pallDiffuse(w.b, g, top, slab, ground, muS, PallSunLight.b, muC, s.cap.b, PallSkyLight.b, 0.0, c));
    return max(d, vec3(0.0));
}

// Рассеянный свет слоя от пожаров под пылью (свет снизу) — на глубине, сдвинутой тенью клубов к ним. Это огонь: он идёт
// в сумму огня точки (main: fire), а не в свет сверху
vec3 burnt(Sample s, float c) {
    if (s.burn.r + s.burn.g + s.burn.b <= 1e-5) return vec3(0.0);
    float slab = max(s.slab, s.tau);
    vec4 w = slabMatter(s);
    float g = w.a, ground = slabGround(s);
    float bottom = clamp(s.tau - s.shadeDown, 0.0, slab);
    vec3 d = vec3(pallDiffuse(w.r, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.r, c),
            pallDiffuse(w.g, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.g, c),
            pallDiffuse(w.b, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.b, c));
    return max(d, vec3(0.0));
}

// место столба в клетке карты c (доли клетки от её середины, до ±0,25): у каждой клетки своё, столбы не стоят рядами
vec2 fireSpot(ivec2 c) {
    // хеш без sin (дешевле на шаге луча): клетки карты — целые до нескольких сотен
    vec3 p = fract(vec3(vec2(c).xyx) * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return (fract((p.xx + p.yz) * p.zy) - 0.5) * 0.5;
}

// Огненный шторм слота в точке p (от глаза): x — ослабление дыма столбов (1/блок), y — зарево пожаров снизу (свет на
// единицу белизны), z — x × оптическая толща дыма столбов от точки к солнцу (тень столба на самом себе). Зарево — пламя
// со всей площади шириной ~2h под точкой (копия карты, где клетка — эта ширина), с мерцанием; столб — подача дыма под
// точкой, снесённой ветром по высоте (fireBend). Пока столб уже клетки (fireWidth), у каждой из четырёх ближних клеток
// — свой столб на её месте: густота — подача клетки × площадь клетки / площадь столба (дыма столько же), между
// столбами чисто; шире — с копии карты, где клетка — ширина столба: дым растекается вширь, и густота падает как
// площадь. cone — меньшее, что луч здесь различает, блоки (ширина пикселя, треть шага): мельче карта не читается —
// вдали столбы не рябят, а столб уже шага не выходит редкой полупрозрачной полосой («дождём»), он там широкий и мягкий.
// clumps — клубы пелены в точке (Sample.clumps): широкий дым клубится ими (столбы — своим шумом мельче).
vec3 storm(sampler2D map, mat4 S, mat4 F, vec3 p, float cone, float clumps) {
    if (F[0].x <= 0.0) return vec3(0.0);
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
    float col = fireColumn(h, top);
    if (col <= 0.0) return vec3(0.0, glow, 0.0);
    vec2 shift = F[1].xy * fireBend(h, F[0].w);
    vec2 uv = fireUv(S, F, p.xz - shift);
    float w = fireWidth(h, cell), wide = max(w, cone);
    // широкий столб и «рядом не горит» — одной выборкой: копия карты с клеткой не мельче 64 блоков накрывает и четыре
    // ближние клетки столбов (среднее из неотрицательных: ноль — нет дыма ни в одной). Над несгоревшим — без выборок
    // столбов и шума
    vec3 m = textureLod(map, uv, max(fireLod(wide, cell), 1.0)).rgb;
    if (m.g + m.b <= 1e-6) return vec3(0.0, glow, 0.0);
    // столб встаёт за подъём дыма: у земли — вся подача (G), выше — доля подъёма (B)
    float rise = smoothstep(0.0, 0.3 * top, h);
    float spread = smoothstep(0.4, 0.75, wide / cell);
    // клубы поднимаются со скоростью дыма и сносятся вместе со столбом; тот же шум гнёт место, где читается столб, —
    // край столба клубится, а не идёт ровной полосой
    vec2 n = texture(PallNoise, vec3(rel.x - shift.x, h - 0.5 * F[1].z * min(t, 3000.0), rel.y - shift.y) / (2.0 * cell)).rg;
    // к солнцу: путь в столбе по горизонтали длиннее в 1/cos высоты солнца (высокое — не дальше, чем вчетверо), и не
    // выше верха столба
    vec3 sun = PallSun.xyz;
    float across = length(sun.xz), slant = 1.0 / max(across, 0.25);
    vec2 toSun = sun.xz / max(across, 1e-4);
    float feed = spread * mix(m.g, m.b, rise);
    // широкий: дым до края столба или до его верха — на половине пути в среднем
    float tau = feed * 0.5 * min(wide * slant, max(top - h, 0.0) / max(sun.y, 0.1));
    float core = 0.6 * spread;
    if (spread < 1.0) {
        ivec2 size = textureSize(map, 0);
        vec2 g = fireUv(S, F, p.xz - shift + (n - 0.5) * (1.2 * w)) * vec2(size);
        ivec2 base = ivec2(floor(g - 0.5));
        float area = cell * cell / (3.14159 * w * w), pillars = 0.0, shade = 0.0, peak = 0.0;
        for (int j = 0; j <= 1; j++) {
            for (int i = 0; i <= 1; i++) {
                ivec2 c = base + ivec2(i, j);
                if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, size))) continue;
                vec3 mc = texelFetch(map, c, 0).rgb;
                vec2 d = (g - vec2(c) - 0.5 - fireSpot(c)) * cell / w;
                float axis = mix(mc.g, mc.b, rise) * area, r2 = dot(d, d), e = exp(-r2);
                pillars += axis * e;
                // толща гауссова столба от точки к солнцу: поперёк луча — гаусс, по ходу — остаток erfc (через tanh),
                // со стороны солнца столб светлый, с другой — в своей тени
                float ahead = dot(d, toSun);
                shade += axis * exp(-max(r2 - ahead * ahead, 0.0)) * (1.0 - tanh(1.2 * ahead)) * 0.886 * w * slant;
                peak = max(peak, e);
            }
        }
        feed += (1.0 - spread) * pillars;
        tau += (1.0 - spread) * shade;
        core += (1.0 - spread) * peak;
    }
    if (feed <= 1e-4) return vec3(0.0, glow, 0.0);
    // сердцевина столба сплошная, края рвутся на клубы; широкий дым — клубами пелены
    float k = F[2].y * col * mix(pallShape(n, 0.2 + 0.7 * core), clumps, spread);
    return vec3(k * feed, glow, k * feed * k * tau);
}

// Зарево всей округи (свет горящего города, рассеянный пылью и дымом вокруг, и там, где под точкой не горит): весь огонь
// карты — как один источник объёмом с город (средний огонь карты × её площадь — копия карты 1×1), спадает как 1/r² от
// середины (мягко — внутри зоны пожаров), гаснет дымом над огнём (как зарево под точкой) и пеленой по пути — от края
// зоны, а внутри неё не ближе, чем за ширину зарева под точкой (ближнее — уже в нём); с фазой рассеяния пыли — к глазу,
// что смотрит на город, ярче. sigma — ослабление пелены в точке (1/блок), view — луч из глаза. x — свет со стороны города
// (как y у storm, только сбоку), y — толща пелены по пути: рассеянный свет гаснет по ней как e^(−kτ) (main, pallK) — в
// густой пыли первых минут дальний огонь до точки почти не доходит, а в непоглощающей водяной пыли идёт далеко.
vec2 fireAround(sampler2D map, mat4 S, mat4 F, vec3 p, vec3 view, float sigma) {
    if (F[0].x <= 0.0) return vec2(0.0);
    float mean = textureLod(map, vec2(0.5), 16.0).r;
    if (mean <= 1e-5) return vec2(0.0);
    float side = F[0].x, top = F[0].z, zone = 0.6 * side;
    vec3 from = vec3(S[0].x, S[0].z, S[0].y), to = p - from;
    float h = max(to.y, 0.0), r2 = dot(to, to), rg = length(to.xz);
    float power = mean * 4.0 * side * side / 3.14159;
    float c = dot(to / max(sqrt(r2), 1.0), -view);
    float phase = mix(0.75 * hg(0.5, c) + 0.25 * hg(-0.2, c), 1.0, zone * zone / (zone * zone + r2));
    float smoke = 0.75 * exp(-h / (0.2 * top)) + 0.25 * exp(-h / top);
    float path = max(rg - zone, 2.0 * h + 2.0 * F[0].y);
    return vec2(AROUND * F[1].w * power / (zone * zone + r2) * min(phase, 3.0) * smoke, sigma * path);
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

// Смерч i вдоль луча на [ta, tb]: свет (rgb, линейный) и пропускание (a); lit — свет пелены и солнца там (рассеянный — с
// асимметрией сажи), fire — огонь там (пожары под пылью через слой и зарево снизу; линейные): вместе с пламенем самого
// смерча — не ярче flame. Ядро смерча толстое (поперёк — несколько единиц толщи): его дым отражает свет как толстый слой
// сажи — её цветом SOOT, а не альбедо однократного рассеяния
vec4 whirl(int i, vec3 dir, float ta, float tb, float jitter, vec3 lit, vec3 fire, vec3 flame) {
    vec3 light = vec3(0.0);
    float tr = 1.0, ds = (tb - ta) / 10.0;
    for (int k = 0; k < 10; k++) {
        vec3 q = dir * (ta + (float(k) + jitter) * ds);
        vec2 w = whirlAt(i, q);
        if (w.x + w.y <= 1e-6) continue;
        float yn = (q.y - PallWhirlPos[i].z) / PallWhirlShape[i].x;
        // дым смерча в свете неба и в огне у основания; пламя светит само
        float heat = 2.0 * PallWhirlLean[i].z * PallWhirlShape[i].y * (1.0 - yn) * (1.0 - yn);
        vec3 sc = SOOT_LINEAR * (lit + min(fire + pallFlame(PallBase, WHIRL_FIRE * heat, WHIRL_FIRE), flame));
        float a = exp(-w.x * ds);
        light += tr * (sc * (1.0 - a) + WHIRL_FIRE_LINEAR * (w.y * ds));
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

// Облако Вильсона слота (S, L — pallWilson), проход луча dir из глаза в t толщей tau: свет (rgb) и пропускание (a).
// Туман конденсации за фронтом — те же капли, что водяная пыль (MIST): небо, солнце и шар с шапкой (свет стены пыли у
// верха, pallLit) с фазой капель; толща — с клубами по направлению от подрыва (оболочка рвётся полосами и пятнами)
vec4 wilsonLight(mat4 S, vec4 G, vec4 H, vec4 L, vec3 dir, vec3 sunDir, float t, float tau) {
    if (tau <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 q = dir * t;
    vec3 toBurst = vec3(S[0].x, S[0].z + L.w, S[0].y) - q;
    toBurst /= max(length(toBurst), 1e-3);
    tau *= 0.5 + textureLod(PallNoise, -toBurst * 1.7 + vec3(0.21, 0.47, 0.83), 0.0).r;
    float lit = pallLit(length(q.xz - S[0].xy), 1.0, G.x, G.z);
    vec3 cap = pallLift3(PallBase, H.rgb * (lit * PallGlowOnSmoke) / MIST);
    vec3 l = PALL_MIST_W * (0.5 * PallSkyLight + PallSunLight * (0.25 * hg(PALL_MIST_G, dot(dir, sunDir)))
            + cap * (0.25 * hg(PALL_MIST_G, dot(dir, toBurst))));
    float tr = exp(-tau);
    return vec4(l * (1.0 - tr), tr);
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
    vec2 span = pallBox(dir, PallMin, PallMax);
    float jitter = fract(texelFetch(Sampler2, ivec2(mod(low, 64.0)), 0).r + PallFrame.x * 0.618034);
    if (scene < 1e8) {
        scene /= axis;
    } else if (span.y > span.x) {
        // мира за пикселем нет (за дальностью прорисовки, за краем LOD Distant Horizons и между ними): луч кончается на
        // рельефе под пеленой (PallGround), а не у плоскости земли под эпицентром — из долины ниже неё он уходил бы
        // под неё у самого глаза, и под горизонтом была бы пустота без пелены
        float end = min(span.y, span.x + PallFrame.w);
        scene = min(pallFloor(Sampler5, PallSlot0, PallGround0, dir, PallFloorFrom, end, FLOOR_STEPS, jitter),
                pallFloor(Sampler6, PallSlot1, PallGround1, dir, PallFloorFrom, end, FLOOR_STEPS, jitter));
    }
    float t0 = span.x, t1 = min(min(span.y, scene), t0 + PallFrame.w);
    vec3 sunDir = PallSun.xyz;
    float muS = max(sunDir.y, 0.12);
    float c = dot(dir, sunDir);
    // путь света к глазу — против луча: его косинус к вертикали
    float mv = -dir.y;
    vec3 sootK = vec3(pallK(PALL_SOOT_W.r, PALL_SOOT_G), pallK(PALL_SOOT_W.g, PALL_SOOT_G), pallK(PALL_SOOT_W.b, PALL_SOOT_G));
    float sootSun = 0.25 * hg(PALL_SOOT_G, c);
    // Самое яркое пламя кадра, линейное: пожары под стеной, пламя шторма слотов, пожары под мглой (цвет их света). Огонь
    // в точке — сумма всех огней: у каждого свой предел (pallFlame), а вместе они — ещё раз не ярче него: точка, со всех
    // сторон окружённая пламенем, светит им, а не ярче
    vec3 flame = max(pallLinear3(FIRE), max(pallLinear3(PallFire0[3].rgb), pallLinear3(PallFire1[3].rgb)));
    flame = max(flame, pallLinear3(PallFireLight / max(PallFireLight.r, 1e-4)));
    vec3 whirlFlame = max(flame, WHIRL_FIRE_LINEAR);
    vec3 light = vec3(0.0);
    float transmit = 1.0;
    // смерчи: отрезок луча и свет каждого — заранее, в луч пелены — когда шаг дойдёт до входа (до мира за пикселем)
    mat4 whirlLight = mat4(0.0);
    vec4 whirlIn = vec4(1e9);
    for (int i = 0; i < 4; i++) {
        vec2 sp = whirlSpan(i, dir);
        sp.y = min(sp.y, scene);
        if (sp.y <= sp.x) continue;
        // свет там: рассеянный пеленой и солнце сквозь неё над серединой, зарево пожаров снизу
        vec3 mid = dir * (0.5 * (sp.x + sp.y));
        Sample m = emptySample();
        slot(PallSlot0, PallWall0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, mid, sunDir, dir, 1e9, m);
        slot(PallSlot1, PallWall1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, mid, sunDir, dir, 1e9, m);
        vec3 lit = scattered(m, PALL_SOOT_G * mv) + PallSunLight * (sootSun * exp(-m.tau / muS));
        float g0 = storm(Sampler3, PallSlot0, PallFire0, mid, 0.0, 1.0).y, g1 = storm(Sampler4, PallSlot1, PallFire1, mid, 0.0, 1.0).y;
        vec3 strongest = g0 >= g1 ? PallFire0[3].rgb : PallFire1[3].rgb;
        vec3 glow = pallFlame(PallBase, g0 * PallFire0[3].rgb + g1 * PallFire1[3].rgb, strongest);
        whirlLight[i] = whirl(i, dir, sp.x, sp.y, jitter, lit, burnt(m, PALL_SOOT_G * mv) + glow, whirlFlame);
        whirlIn[i] = sp.x;
    }
    // облако Вильсона: до двух проходов оболочки на слот — так же, когда шаг дойдёт до прохода (до мира за пикселем)
    vec4 w0 = pallWilson(PallSlot0, PallWilson0, dir, scene), w1 = pallWilson(PallSlot1, PallWilson1, dir, scene);
    mat4 wilsonLit = mat4(wilsonLight(PallSlot0, PallGlow0, PallHot0, PallWilson0, dir, sunDir, w0.x, w0.y),
            wilsonLight(PallSlot0, PallGlow0, PallHot0, PallWilson0, dir, sunDir, w0.z, w0.w),
            wilsonLight(PallSlot1, PallGlow1, PallHot1, PallWilson1, dir, sunDir, w1.x, w1.y),
            wilsonLight(PallSlot1, PallGlow1, PallHot1, PallWilson1, dir, sunDir, w1.z, w1.w));
    vec4 wilsonIn = vec4(w0.x, w0.z, w1.x, w1.z);
    if (w0.y <= 0.0) wilsonIn.x = 1e9;
    if (w0.w <= 0.0) wilsonIn.y = 1e9;
    if (w1.y <= 0.0) wilsonIn.z = 1e9;
    if (w1.w <= 0.0) wilsonIn.w = 1e9;
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
        // Голова стены пыли на луче — гуще: шагом в восьмую её высоты (вал толще шага луч то пробивал бы насквозь, то
        // проходил мимо, и накопление давало бы рябь вместо клубов), на все отрезки — не больше steps шагов сверх обычных.
        // Шаги отрезка — своя сетка от его входа, со сдвигом кадра (jitter); обычные шаги внутри отрезка пропускаются
        vec4 hs0 = pallWallSpans(PallSlot0, PallWall0, PallGround0, dir, t0, t1);
        vec4 hs1 = pallWallSpans(PallSlot1, PallWall1, PallGround1, dir, t0, t1);
        vec4 enter = vec4(hs0.x, hs0.z, hs1.x, hs1.z), leave = vec4(hs0.y, hs0.w, hs1.y, hs1.w);
        vec4 fineStep = vec4(0.12 * PallWall0[0].z, 0.12 * PallWall0[0].z, 0.12 * PallWall1[0].z, 0.12 * PallWall1[0].z);
        vec4 want = max(leave - enter, vec4(0.0)) / max(fineStep, vec4(1.0));
        float wanted = want.x + want.y + want.z + want.w;
        float fit = wanted > n ? n / wanted : 1.0;
        for (int k = 0; k < 4; k++) {
            if (leave[k] <= enter[k]) {
                enter[k] = 1e9;
                leave[k] = 1e9;
                continue;
            }
            fineStep[k] = (leave[k] - enter[k]) / max(floor(want[k] * fit), 1.0);
        }
        int base = 0;
        float t = along(jitter / n, t0, fa, fb, t1, a, b), nextBase = along((1.0 + jitter) / n, t0, fa, fb, t1, a, b);
        for (int it = 0; it < 2 * steps + 16; it++) {
            if (t >= t1) break;
            // конец шага: обычная граница или вход в отрезок головы, а в нём — граница его сетки или выход
            float next = nextBase;
            bool dense = false;
            for (int k = 0; k < 4; k++) {
                if (!dense && enter[k] <= t && t < leave[k]) {
                    dense = true;
                    float j = floor((t - enter[k]) / fineStep[k] - jitter + 1e-3) + 1.0;
                    next = min(enter[k] + (j + jitter) * fineStep[k], leave[k]);
                }
            }
            if (!dense) {
                for (int k = 0; k < 4; k++) {
                    if (enter[k] > t) next = min(next, enter[k]);
                }
            }
            next = min(next, t1);
            for (int k = 0; k < steps && nextBase <= next + 1e-4; k++) {
                base += 1;
                nextBase = along((float(base) + 1.0 + jitter) / n, t0, fa, fb, t1, a, b);
            }
            float ta = t, ds = next - t;
            t = next;
            for (int k = 0; k < 4; k++) {
                if (whirlIn[k] <= ta) {
                    light += transmit * whirlLight[k].rgb;
                    transmit *= whirlLight[k].a;
                    whirlIn[k] = 1e9;
                }
                if (wilsonIn[k] <= ta) {
                    light += transmit * wilsonLit[k].rgb;
                    transmit *= wilsonLit[k].a;
                    wilsonIn[k] = 1e9;
                }
            }
            if (ds <= 0.0) continue;
            vec3 p = dir * ta;
            // меньшее, что луч здесь различает: ширина пикселя или треть шага
            float cone = max(ta * pixel, 0.35 * ds);
            Sample s = emptySample();
            slot(PallSlot0, PallWall0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, p, sunDir, dir, cone, s);
            slot(PallSlot1, PallWall1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, p, sunDir, dir, cone, s);
            vec3 f0 = vec3(0.0), f1 = vec3(0.0);
            vec2 around0 = vec2(0.0), around1 = vec2(0.0);
            if (all(greaterThanEqual(p, PallFireMin)) && all(lessThanEqual(p, PallFireMax))) {
                f0 = storm(Sampler3, PallSlot0, PallFire0, p, cone, s.clumps);
                f1 = storm(Sampler4, PallSlot1, PallFire1, p, cone, s.clumps);
                around0 = fireAround(Sampler3, PallSlot0, PallFire0, p, dir, s.sigma);
                around1 = fireAround(Sampler4, PallSlot1, PallFire1, p, dir, s.sigma);
            }
            float soot = f0.x + f1.x;
            float st = s.sigma + soot;
            if (st <= 1e-7) continue;
            // вещество в точке: альбедо и асимметрия пелены (здесь её нет, а есть дым шторма — как у пыли)
            vec3 w = PALL_DUST_W;
            float g = PALL_DUST_G;
            if (s.sigma > 1e-7) {
                w = s.albedo / s.sigma;
                g = s.phase / s.sigma;
            }
            // рассеянный свет слоя и пучки: солнце, шар и шапка — по Бугеру–Ламберту сквозь столб над точкой и тень клубов,
            // с фазой Хеньи–Гринстейна (её среднее по сфере — 1, пучок на белой стене — его сила: отсюда ¼)
            vec3 field = scattered(s, g * mv);
            vec3 beams = PallSunLight * (0.25 * hg(g, c) * exp(-max(s.tau / muS + s.shadeSun, 0.0)));
            float capI = s.cap.r + s.cap.g + s.cap.b;
            if (capI > 1e-5) {
                float muC = clamp(s.capUp / capI, 0.12, 1.0);
                beams += s.cap * (0.25 * hg(g, clamp(s.capView / capI, -1.0, 1.0)) * exp(-max(s.tau / muC + s.shadeCap, 0.0)));
            }
            // Свет снизу — пожары под мглой, зарево шторма — рассеянный поток вверх: по Эддингтону точка рассеивает его к
            // глазу как ω'·(1/2 + 3/4·g'·μ) (вниз, к огню, ярче), в тени клубов под точкой — как e^(−kτ) рассеянного света;
            // зарево округи — сбоку, со своей фазой (fireAround): половина, как от светлого полупространства
            vec3 kd = vec3(pallK(w.r, g), pallK(w.g, g), pallK(w.b, g));
            vec3 under = (0.5 + 0.75 * g * mv) * exp(-kd * s.shadeDown);
            // зарево шторма и округи — на экране поверх света неба (как у частиц, fx.vsh), в линейный — разностью, не ярче
            // пламени (pallFlame)
            vec3 glow = vec3(0.0), around = vec3(0.0);
            if (f0.y + f1.y > 0.0) {
                vec3 strongest = f0.y >= f1.y ? PallFire0[3].rgb : PallFire1[3].rgb;
                glow = pallFlame(PallBase, f0.y * PallFire0[3].rgb + f1.y * PallFire1[3].rgb, strongest);
            }
            if (around0.x > 0.0) around += pallFlame(PallBase, 0.5 * around0.x * PallFire0[3].rgb, PallFire0[3].rgb) * exp(-kd * around0.y);
            if (around1.x > 0.0) around += pallFlame(PallBase, 0.5 * around1.x * PallFire1[3].rgb, PallFire1[3].rgb) * exp(-kd * around1.y);
            // огонь в точке — все огни вместе, не ярче пламени (flame): пожары под пылью через слой, зарево снизу,
            // пожары под мглой (s.fire — их свет × вещество; свет — делением на вещество) и зарево округи
            vec3 below = burnt(s, g * mv);
            vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, flame);
            vec3 emit = s.albedo * (field + beams + fire);
            // огненный шторм: дым столбов (сажа) — в том же свете и в своей тени: прямой свет гаснет в столбе по его толще к
            // солнцу, рассеянный — как e^(−kτ) сажи (сторона к солнцу и верх клубов светлые, другая сторона и низ — тёмные:
            // столб объёмный, а не плоская серая полоса); снизу — зарево пожаров
            if (soot > 0.0) {
                float self = (f0.z + f1.z) / soot;
                vec3 sootLit = field * exp(-sootK * self) + PallSunLight * (sootSun * exp(-max(s.tau / muS + s.shadeSun, 0.0) - self))
                        + min(below * exp(-sootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, flame);
                emit += soot * PALL_SOOT_W * sootLit;
            }
            float tr = exp(-st * ds);
            light += transmit * emit * (1.0 - tr) / st;
            transmit *= tr;
            if (transmit < 0.005) {
                transmit = 0.0;
                break;
            }
        }
    }
    // смерчи и облако Вильсона за последним шагом (до мира)
    for (int k = 0; k < 4; k++) {
        if (whirlIn[k] < 1e8) {
            light += transmit * whirlLight[k].rgb;
            transmit *= whirlLight[k].a;
        }
        if (wilsonIn[k] < 1e8) {
            light += transmit * wilsonLit[k].rgb;
            transmit *= wilsonLit[k].a;
        }
    }
    // на экран: цвет пелены как вещества — свет, делённый на непрозрачность (в однородной — её функция источника), в
    // значения экрана, умноженный на непрозрачность; с миром смешивается, как всё полупрозрачное в кадре
    float cover = 1.0 - transmit;
    vec3 medium = light / max(cover, 1e-4);
    vec3 shown = shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * cover;
    // негодный итог (NaN, бесконечность — из глубины, матриц или поля) — пропускание −1: накопление возьмёт прошлое
    // этого места, а без него пиксель — без пелены (fx_pall_resolve)
    vec4 res = vec4(shown, transmit);
    fragColor = pallValid(res) ? res : vec4(0.0, 0.0, 0.0, -1.0);
}
