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

// Пелена слота в точке p — прибавить к s; ground, R — карта рельефа и воды слота и её числа, W — вода слота, G, H — свет
// стены пыли слота (PallGlow, PallHot), dir — луч зрения, cone — меньшее, что луч здесь различает (блоки)
void slot(mat4 S, sampler2D ground, vec4 R, vec4 W, vec4 G, vec4 H, vec3 p, vec3 sunDir, vec3 dir, float cone, inout Sample s) {
    if (S[1].w < 0.5) return;
    // пол слоя — рельеф под точкой (карта PallGround): в долине пелена над ней — тоже слой, и пожары и земля под ней —
    // на дне, а не на уровне земли под эпицентром; карты нет — земля под эпицентром
    float floorUp = R.x > 0.0 ? pallGroundAt(ground, S, R, p.xz) - S[0].z : 0.0;
    PallAt f = pallField(Sampler1, ground, S, R, W, p, floorUp);
    vec3 dustW = mix(PALL_DUST_W, PALL_SMOKE_W, f.smoke);
    s.tau += f.column.x + f.column.y + f.column.z;
    s.slab += f.slab.x + f.slab.y + f.slab.z;
    s.slabAlbedo += f.slab.x * dustW + f.slab.y * PALL_HAZE_W + f.slab.z * PALL_MIST_W;
    s.slabPhase += (f.slab.x + f.slab.y) * PALL_DUST_G + f.slab.z * PALL_MIST_G;
    // пыль — над водой, если её подняло с воды; водяная пыль — всегда
    s.water += (f.slab.x + f.slab.y) * f.wet + f.slab.z;
    // Свет стены пыли (CloudPuffs.wallColor): шар и шапка — пучок к верху слоя (свет на верху стены, pallLit), пожары под
    // пылью — свет снизу (у низа стены, pallBurn); сила — та, что на клубе стены даёт отсвет (× PallGlowOnSmoke), делённая
    // на цвет пыли, — на экране поверх света неба, в линейный — разностью (pallLift): толстая пыль у фронта отражает его
    // как клубы стены — пелена продолжает её цвет. Свет пожаров — не ярче пламени (pallFlame): ночью над зоной пожаров
    // клубы стены на экране уже цвета огня, и обращение через цвет пыли дало бы свет втрое ярче огня — пыль у земли вышла
    // бы ярче 1 и ровной (плечо). Пожары под пылью днём едва видны, как их зарево на мгле (NukePall: fireLight): за волной
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
    if (f.sigma.x + f.sigma.y + f.sigma.z <= 1e-7) return;
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
    // главный свет — солнце или, пока он ярче, шар и шапка над эпицентром или пожары под пылью (ночью это весь свет
    // пыли); светят пожары внизу — к ним, а не к шапке
    float heat = max(lit, burn) * PallGlowOnSmoke;
    float key = clamp(heat / (heat + 0.5 * PallSun.w + 0.02), 0.0, 1.0);
    vec3 keyLight = lightDir;
    if (burn > lit) keyLight = vec3(0.0, -1.0, 0.0);
    vec3 keyDir = mix(sunDir, keyLight, key);
    keyDir /= max(length(keyDir), 1e-3);
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

// Рассеянный свет слоя пелены в точке, I0 + c·I1 по каналам (pallDiffuse; без альбедо точки): свет сверху (солнце, шар
// и шапка, небо) — на глубине, сдвинутой тенью клубов к нему, свет снизу (пожары под пылью) — на своей. Вещество слоя —
// по толще его частей, земля под ним — суша или вода
vec3 scattered(Sample s, float c) {
    float slab = max(s.slab, s.tau);
    vec3 w = PALL_DUST_W;
    float g = PALL_DUST_G, water = 0.0;
    if (s.slab > 1e-6) {
        w = s.slabAlbedo / s.slab;
        g = s.slabPhase / s.slab;
        water = clamp(s.water / s.slab, 0.0, 1.0);
    }
    float ground = mix(PALL_LAND, PALL_SEA, water);
    float muS = max(PallSun.y, 0.12);
    float capI = s.cap.r + s.cap.g + s.cap.b;
    float muC = clamp(s.capUp / max(capI, 1e-6), 0.12, 1.0);
    float top = clamp(s.tau + s.shadeUp, 0.0, slab);
    vec3 d = vec3(pallDiffuse(w.r, g, top, slab, ground, muS, PallSunLight.r, muC, s.cap.r, PallSkyLight.r, 0.0, c),
            pallDiffuse(w.g, g, top, slab, ground, muS, PallSunLight.g, muC, s.cap.g, PallSkyLight.g, 0.0, c),
            pallDiffuse(w.b, g, top, slab, ground, muS, PallSunLight.b, muC, s.cap.b, PallSkyLight.b, 0.0, c));
    if (s.burn.r + s.burn.g + s.burn.b > 1e-5) {
        float bottom = clamp(s.tau - s.shadeDown, 0.0, slab);
        d += vec3(pallDiffuse(w.r, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.r, c),
                pallDiffuse(w.g, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.g, c),
                pallDiffuse(w.b, g, bottom, slab, ground, 1.0, 0.0, 1.0, 0.0, 0.0, s.burn.b, c));
    }
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
// асимметрией сажи), glow — зарево снизу (линейные). Ядро смерча толстое (поперёк — несколько единиц толщи): его дым
// отражает свет как толстый слой сажи — её цветом SOOT, а не альбедо однократного рассеяния
vec4 whirl(int i, vec3 dir, float ta, float tb, float jitter, vec3 lit, vec3 glow) {
    vec3 light = vec3(0.0);
    float tr = 1.0, ds = (tb - ta) / 10.0;
    for (int k = 0; k < 10; k++) {
        vec3 q = dir * (ta + (float(k) + jitter) * ds);
        vec2 w = whirlAt(i, q);
        if (w.x + w.y <= 1e-6) continue;
        float yn = (q.y - PallWhirlPos[i].z) / PallWhirlShape[i].x;
        // дым смерча в свете неба и в огне у основания; пламя светит само
        float heat = 2.0 * PallWhirlLean[i].z * PallWhirlShape[i].y * (1.0 - yn) * (1.0 - yn);
        vec3 sc = SOOT_LINEAR * (lit + glow + pallFlame(PallBase, WHIRL_FIRE * heat, WHIRL_FIRE));
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
        slot(PallSlot0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, mid, sunDir, dir, 1e9, m);
        slot(PallSlot1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, mid, sunDir, dir, 1e9, m);
        vec3 lit = scattered(m, PALL_SOOT_G * mv) + PallSunLight * (sootSun * exp(-m.tau / muS));
        float g0 = storm(Sampler3, PallSlot0, PallFire0, mid, 0.0, 1.0).y, g1 = storm(Sampler4, PallSlot1, PallFire1, mid, 0.0, 1.0).y;
        vec3 flame = g0 >= g1 ? PallFire0[3].rgb : PallFire1[3].rgb;
        vec3 glow = pallFlame(PallBase, g0 * PallFire0[3].rgb + g1 * PallFire1[3].rgb, flame);
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
            // меньшее, что луч здесь различает: ширина пикселя или треть шага
            float cone = max(ta * pixel, 0.35 * ds);
            Sample s = emptySample();
            slot(PallSlot0, Sampler5, PallGround0, PallWater0, PallGlow0, PallHot0, p, sunDir, dir, cone, s);
            slot(PallSlot1, Sampler6, PallGround1, PallWater1, PallGlow1, PallHot1, p, sunDir, dir, cone, s);
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
                vec3 flame = f0.y >= f1.y ? PallFire0[3].rgb : PallFire1[3].rgb;
                glow = pallFlame(PallBase, f0.y * PallFire0[3].rgb + f1.y * PallFire1[3].rgb, flame);
            }
            if (around0.x > 0.0) around += pallFlame(PallBase, 0.5 * around0.x * PallFire0[3].rgb, PallFire0[3].rgb) * exp(-kd * around0.y);
            if (around1.x > 0.0) around += pallFlame(PallBase, 0.5 * around1.x * PallFire1[3].rgb, PallFire1[3].rgb) * exp(-kd * around1.y);
            vec3 emit = s.albedo * (field + beams + under * glow + around) + s.fire * under;
            // огненный шторм: дым столбов (сажа) — в том же свете и в своей тени: прямой свет гаснет в столбе по его толще к
            // солнцу, рассеянный — как e^(−kτ) сажи (сторона к солнцу и верх клубов светлые, другая сторона и низ — тёмные:
            // столб объёмный, а не плоская серая полоса); снизу — зарево пожаров
            if (soot > 0.0) {
                float self = (f0.z + f1.z) / soot;
                vec3 sootLit = field * exp(-sootK * self) + PallSunLight * (sootSun * exp(-max(s.tau / muS + s.shadeSun, 0.0) - self))
                        + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around;
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
    // смерчи за последним шагом (до мира)
    for (int k = 0; k < 4; k++) {
        if (whirlIn[k] < 1e8) {
            light += transmit * whirlLight[k].rgb;
            transmit *= whirlLight[k].a;
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
