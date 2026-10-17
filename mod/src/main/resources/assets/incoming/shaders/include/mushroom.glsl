// Гриб объёмом (client/nuclear/MushroomField — числа кадра, nuclear/model/MushroomModel — законы): шапка — кольцо
// с куполом над его дыркой, ножка, юбка пыли у её основания. Поле аналитическое: функции чисел гриба этого кадра и
// места; клубы — шум общей плитки пелены (VolumeNoise) в координатах тела гриба, в долях его размера. Общее у объёма
// (fx_pall), накопления (fx_pall_resolve) и квадратов слоя эффектов (fx.vsh: что гриб закрывает перед частицей).
// Скалярные законы — копии MushroomModel и MushroomField (MushroomGlslTest считает их и сверяет с Java).
//
// Таблица грибов (MushroomField.TEXELS текселей RGBA32F на гриб; строка k — гриб k, строка MUSH_BB_ROW — свет чёрного
// тела): блоки, от глаза; высоты — над землёй под эпицентром.
//   0 = (эпицентр x, z; земля под ним по y; 1 — гриб есть)
//   1 = (снос ветром x, z на всю долю; верх после стабилизации — по его доле снос; радиус рамки)
//   2 = (середина кольца шапки по высоте; радиус шапки; большой радиус кольца; верх рамки)
//   3 = (полуоси сечения кольца по горизонтали и вертикали; середина купола; его полуось по горизонтали)
//   4 = (полуось купола по вертикали; ослабление шапки, 1/блок; плитка клубов шапки, блоки; секунды после подрыва)
//   5 = (альбедо ω' шапки rgb; асимметрия g')
//   6 = (верх ножки; её радиус; доля пыли у земли; толща ножки поперёк)
//   7 = (ω' ножки rgb; g')
//   8 = (середина юбки по земле; её полуширина; высота; ослабление, 1/блок)
//   9 = (ω' юбки rgb; g')
//  10 = (воздух вокруг, K; перепад температуры ядра, K; низ шапки; плитка клубов ножки)
//  11 = (свет пожаров снизу 0..1; подъём клубов ножки, блоки; 0; 0)

const int MUSHROOMS = 4;
const int MUSH_BB_ROW = 4;
// свет чёрного тела в таблице: узлов, температуры края (K) — по логарифму (MushroomField.BB_*)
const float MUSH_BB_NODES = 256.0;
const float MUSH_BB_FROM = 500.0;
const float MUSH_BB_TO = 30000.0;
// размах сдвига края клубами и мягкость края — шапка, ножка, юбка, в единицах клубов части (MushroomField: LOBES_*,
// EDGE_*): у шапки — меньшая полуось сечения кольца, у ножки — её радиус на этой высоте, у юбки — меньшая полуось
const float MUSH_CAP_LOBES = 0.4;
const float MUSH_CAP_EDGE = 0.07;
const float MUSH_STEM_LOBES = 0.4;
const float MUSH_STEM_EDGE = 0.2;
const float MUSH_SKIRT_LOBES = 0.8;
const float MUSH_SKIRT_EDGE = 0.25;
// сглаживание объединения кольца и купола, в единицах клубов шапки (мягкий минимум опускает край не больше чем на
// четверть его)
const float MUSH_BLEND = 0.25;
// докуда за край без клубов доходит вещество (сдвиг клубами до ±0,68 размаха, мягкость края, у шапки — и мягкий
// минимум), в единицах клубов — шапка, ножка, юбка
const float MUSH_CAP_REACH = 0.68 * MUSH_CAP_LOBES + MUSH_CAP_EDGE + 0.25 * MUSH_BLEND;
const float MUSH_STEM_REACH = 0.68 * MUSH_STEM_LOBES + MUSH_STEM_EDGE;
const float MUSH_SKIRT_REACH = 0.68 * MUSH_SKIRT_LOBES + MUSH_SKIRT_EDGE;

struct Mush {
    vec3 base;
    vec2 drift;
    float endTop;
    float boundR;
    float capY;
    float capR;
    float ringR;
    float boundTop;
    float ringA;
    float ringB;
    float domeY;
    float domeR;
    float domeH;
    float capSigma;
    float capTile;
    float seconds;
    vec4 capMat;
    float stemTop;
    float stemR;
    float lowDust;
    float stemTau;
    vec4 stemMat;
    float skirtR;
    float skirtW;
    float skirtH;
    float skirtSigma;
    vec4 skirtMat;
    float tAmb;
    float dT;
    float capBot;
    float stemTile;
    float stemRise;
    float fireTop;
    float fireR;
    vec3 fire;
};

Mush mushLoad(sampler2D data, int k) {
    vec4 t0 = texelFetch(data, ivec2(0, k), 0), t1 = texelFetch(data, ivec2(1, k), 0), t2 = texelFetch(data, ivec2(2, k), 0);
    vec4 t3 = texelFetch(data, ivec2(3, k), 0), t4 = texelFetch(data, ivec2(4, k), 0), t6 = texelFetch(data, ivec2(6, k), 0);
    vec4 t8 = texelFetch(data, ivec2(8, k), 0), t10 = texelFetch(data, ivec2(10, k), 0), t11 = texelFetch(data, ivec2(11, k), 0);
    // эпицентр в таблице — x, z и земля по y (как у слота пелены): в место — x, y, z
    return Mush(t0.xzy, t1.xy, t1.z, t1.w, t2.x, t2.y, t2.z, t2.w, t3.x, t3.y, t3.z, t3.w, t4.x, t4.y, t4.z, t4.w,
            texelFetch(data, ivec2(5, k), 0), t6.x, t6.y, t6.z, t6.w, texelFetch(data, ivec2(7, k), 0), t8.x, t8.y, t8.z, t8.w,
            texelFetch(data, ivec2(9, k), 0), t10.x, t10.y, t10.z, t10.w, t11.x, t11.y, t11.z, texelFetch(data, ivec2(12, k), 0).rgb);
}

// есть ли гриб k в кадре
bool mushOn(sampler2D data, int k) {
    return texelFetch(data, ivec2(0, k), 0).w > 0.5;
}

// доля сноса ветром на высоте h (MushroomModel.driftShare): верх сносит сильнее низа
float mushShare(float h, float endTop) {
    return clamp(h / max(endTop, 1.0), 0.0, 1.0);
}

// радиус ножки на доле её высоты w (MushroomModel.stemRadius): шире у шапки и у земли — раструб пыли, оседает с lowDust
float mushStemR(float w, float stemR, float lowDust) {
    float x = clamp(w, 0.0, 1.0);
    float base = pow(1.0 - x, 6.0);
    return stemR * (0.75 + 0.9 * x * x) + stemR * 1.6 * base * lowDust;
}

// мера места в сечении кольца: 1 — на его краю (rho — от оси по горизонтали, y — от середины кольца)
float mushRing(float rho, float y, float ringR, float a, float b) {
    float u = (rho - ringR) / max(a, 1e-3), v = y / max(b, 1e-3);
    return sqrt(u * u + v * v);
}

// мера места в эллипсе (купол, юбка в сечении): 1 — на краю
float mushEllipse(float x, float y, float rx, float ry) {
    float u = x / max(rx, 1e-3), v = y / max(ry, 1e-3);
    return sqrt(u * u + v * v);
}

// Расстояние со знаком до края эллипса с полуосями rx, ry, блоки (внутри — меньше нуля): мера − 1, делённая на её
// наклон, — точно у края, где его и сдвигают клубы; в глубине — не глубже меньшей полуоси
float mushEllipseDist(float x, float y, float rx, float ry) {
    vec2 r = max(vec2(rx, ry), vec2(1e-3));
    vec2 u = vec2(x, y) / r;
    float s = length(u), slope = length(u / r);
    float deep = -min(r.x, r.y);
    if (slope < 1e-9) return deep;
    return max((s - 1.0) * s / slope, deep);
}

// мягкий минимум (полиномиальный, k — ширина сглаживания): кольцо и купол — одна поверхность; опускает не больше k/4
float mushSmin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / max(k, 1e-3), 0.0, 1.0);
    return b + (a - b) * h - k * h * (1.0 - h);
}

// Плотность края: 1 внутри, 0 снаружи, край мягкий на ±e блоков (d — расстояние со знаком до края, сдвинутого клубами)
float mushEdge(float d, float e) {
    return smoothstep(-e, e, -d);
}

// Место точки p (от глаза) в теле гриба: x, z — от оси гриба на этой высоте (со сносом), y — над землёй под эпицентром
vec3 mushLocal(Mush m, vec3 p) {
    float h = p.y - m.base.y;
    vec2 xz = p.xz - m.base.xz - m.drift * mushShare(h, m.endTop);
    return vec3(xz.x, h, xz.y);
}

// единицы клубов, блоки (MushroomField.capUnit, skirtUnit): шапка — меньшая полуось сечения кольца, юбка — её сечения
float mushCapUnit(Mush m) {
    return min(m.ringA, m.ringB);
}

float mushSkirtUnit(Mush m) {
    return min(m.skirtW, m.skirtH);
}

// радиус ножки на высоте h места тела
float mushStemAt(Mush m, float h) {
    return mushStemR(h / max(m.capBot, 1.0), m.stemR, m.lowDust);
}

// Расстояния со знаком до края шапки, ножки и юбки без клубов в месте q тела, блоки (у ножки — по радиусу на этой
// высоте; её верх и низ — mushStemSigma)
vec3 mushDist(Mush m, vec3 q) {
    float rho = length(q.xz);
    float ring = mushEllipseDist(rho - m.ringR, q.y - m.capY, m.ringA, m.ringB);
    float dome = mushEllipseDist(rho, q.y - m.domeY, m.domeR, m.domeH);
    return vec3(mushSmin(ring, dome, MUSH_BLEND * mushCapUnit(m)), rho - mushStemAt(m, q.y),
            mushEllipseDist(rho - m.skirtR, q.y, m.skirtW, m.skirtH));
}

// Густота ножки на высоте h без доли края: её толща поперёк на её ширине; верх тает у верха ножки, под землёй её нет
float mushStemSigma(Mush m, float h) {
    if (h < -0.02 * m.capBot || h > m.stemTop) return 0.0;
    float topFade = clamp((m.stemTop - h) / (0.05 * m.capBot + 1.0), 0.0, 1.0);
    return m.stemTau / (2.0 * mushStemAt(m, h)) * topFade;
}

// Ослабление шапки, ножки и юбки в месте q без шума — среднее по клубам (край размыт на размах клубов): для толщи к
// свету и квадратов слоя
vec3 mushMean(Mush m, vec3 q) {
    vec3 d = mushDist(m, q);
    return vec3(m.capSigma * mushEdge(d.x, 0.5 * MUSH_CAP_LOBES * mushCapUnit(m)),
            mushStemSigma(m, q.y) * mushEdge(d.y, 0.5 * MUSH_STEM_LOBES * mushStemAt(m, q.y)),
            m.skirtSigma * mushEdge(d.z, 0.5 * MUSH_SKIRT_LOBES * mushSkirtUnit(m)));
}

// Сколько до вещества гриба от места q, блоки мира, не больше (0 — уже в нём или рядом): шаг луча по пустому. Мера
// эллипса растёт не быстрее расстояния, делённого на меньшую полуось, — мера − 1, умноженная на неё, не дальше края;
// радиус ножки по высоте меняется не круче своего наклона; снос по высоте растягивает тело не больше чем
// в 1 + снос / верх раз
float mushGap(Mush m, vec3 q) {
    float rho = length(q.xz);
    float gap = 1e9;
    if (m.capSigma > 0.0) {
        float ring = (mushRing(rho, q.y - m.capY, m.ringR, m.ringA, m.ringB) - 1.0) * min(m.ringA, m.ringB);
        float dome = (mushEllipse(rho, q.y - m.domeY, m.domeR, m.domeH) - 1.0) * min(m.domeR, m.domeH);
        gap = min(ring, dome) - MUSH_CAP_REACH * mushCapUnit(m);
    }
    if (m.stemTau > 0.0) {
        // наклон радиуса ножки с клубами: раструб у земли (−9,6·пыль) и у шапки (+1,8) на высоту шапки
        float slope = (1.0 + MUSH_STEM_REACH) * m.stemR * max(1.8, 9.6 * m.lowDust) / max(m.capBot, 1.0);
        float radial = (rho - (1.0 + MUSH_STEM_REACH) * mushStemAt(m, q.y)) / sqrt(1.0 + slope * slope);
        gap = min(gap, max(radial, max(q.y - m.stemTop, -0.02 * m.capBot - q.y)));
    }
    if (m.skirtSigma > 0.0) {
        float u = mushSkirtUnit(m);
        gap = min(gap, (mushEllipse(rho - m.skirtR, q.y, m.skirtW, m.skirtH) - 1.0) * u - MUSH_SKIRT_REACH * u);
    }
    return max(gap, 0.0) / (1.0 + length(m.drift) / max(m.endTop, 1.0));
}

// Отрезок луча (из глаза, dir — единичный) в рамке гриба: цилиндр вокруг его оси посреди сноса; вход ≥ выхода — мимо
vec2 mushSpan(Mush m, vec3 dir) {
    vec2 c = m.base.xz + 0.5 * m.drift;
    float a = dot(dir.xz, dir.xz), b = dot(dir.xz, c), cc = dot(c, c) - m.boundR * m.boundR;
    float disc = b * b - a * cc;
    if (a < 1e-8 || disc <= 0.0) return vec2(1.0, 0.0);
    float sq = sqrt(disc);
    vec2 t = vec2(b - sq, b + sq) / a;
    float lo = m.base.y - 0.05 * m.boundTop, hi = m.base.y + m.boundTop;
    if (abs(dir.y) > 1e-5) {
        float ta = lo / dir.y, tb = hi / dir.y;
        t = vec2(max(t.x, min(ta, tb)), min(t.y, max(ta, tb)));
    } else if (lo > 0.0 || hi < 0.0) {
        return vec2(1.0, 0.0);
    }
    return vec2(max(t.x, 0.0), t.y);
}

// место температуры в строке света чёрного тела (0..1)
float mushBbU(float kelvin) {
    float x = (log2(max(kelvin, MUSH_BB_FROM)) - log2(MUSH_BB_FROM)) / (log2(MUSH_BB_TO) - log2(MUSH_BB_FROM));
    return clamp(x, 0.0, 1.0);
}

// Свет чёрного тела температуры T в единицах кадра (белая стена в полдень — 1), линейный rgb (BlackBody)
vec3 mushBlackBody(sampler2D data, float kelvin) {
    vec2 size = vec2(textureSize(data, 0));
    float x = (mushBbU(kelvin) * (MUSH_BB_NODES - 1.0) + 0.5) / size.x;
    return texture(data, vec2(x, (float(MUSH_BB_ROW) + 0.5) / size.y)).rgb;
}

// Пропускание гриба k вдоль луча dir на [0, len] без шума (steps шагов) — квадраты слоя перед частицей
float mushTransmit(sampler2D data, vec3 dir, float len, int steps) {
    float tau = 0.0;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) continue;
        Mush m = mushLoad(data, k);
        vec2 span = mushSpan(m, dir);
        span.y = min(span.y, len);
        if (span.y <= span.x) continue;
        float ds = (span.y - span.x) / float(steps);
        for (int i = 0; i < steps; i++) {
            vec3 s = mushMean(m, mushLocal(m, dir * (span.x + (float(i) + 0.5) * ds)));
            tau += (s.x + s.y + s.z) * ds;
        }
    }
    return exp(-tau);
}
