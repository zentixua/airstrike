// Пелена после ударной волны (client/nuclear/PallField — законы, client/fx/layer/PallPass — проходы): поле одного
// подрыва по таблице радиуса. Общее у объёма (fx_pall) и у квадратов слоя эффектов (fx.vsh: что пелена закрывает
// перед частицей). Числа и законы — копии PallField и VolumeNoise.shape (PallGlslTest считает эти функции и сверяет с Java).
//
// Слот подрыва — mat4 по столбцам:
//   [0] = (эпицентр x, z от глаза; земля под эпицентром от глаза по y; докуда таблица, блоки)
//   [1] = (снос пыли ветром x, z, блоки; длина шлейфа мглы, блоки; 1 — слот есть)
//   [2] = (ветер x, z — единичный; v строки пыли в таблице; v строки мглы)
//   [3] = (ослабление самой густой пыли, 1/блок; плитка шума, блоки; секунды после подрыва; 0)
// Таблица: по ширине — радиус 0..reach, строки — пыль (ослабление у земли, высота, доля дыма, ослабление брызг над
// водой — в том же слое) и мгла (ослабление, высота, свет пожаров снизу).
// Вода слота W — vec4: (радиус базисной волны, её верх — блоки; её ослабление в самой густой части, 1/блок, 0 — волны нет;
// 1 — под пеленой есть вода и её карта — канал g карты рельефа слота, PallGround; 0 — всюду суша).

// Вещество пелены (client/nuclear/PallLight; PallGlslTest сверяет): альбедо однократного рассеяния ω' по каналам и
// асимметрия g' после дельта-Эддингтона (пик вперёд f = g² — в непрорассеянный свет, остаток g' = g / (1 + g)). Ослабление
// таблицы (по видимости, которую видит глаз) — уже σ' дельта-Эддингтона: по нему идут и луч зрения, и свет к точке.
// ω' — из цвета толстого слоя (fx_pall: DUST, SMOKE, HAZE, MIST; сажа шторма — SOOT; в линейном — pallLinear) обращением
// отражения двух потоков: толстая пелена — того же цвета, что стена пыли и брызг. Пыль, дым и мгла — g = 0,6, капли
// водяной пыли — 0,85, сажа — 0,5.
const vec3 PALL_DUST_W = vec3(0.8687934, 0.8099112, 0.7286264);
const vec3 PALL_SMOKE_W = vec3(0.5520492, 0.5170717, 0.4837395);
const vec3 PALL_HAZE_W = vec3(0.7175592, 0.6833941, 0.6480812);
const vec3 PALL_MIST_W = vec3(0.9948032, 0.9964194, 0.9973450);
const vec3 PALL_SOOT_W = vec3(0.4368484, 0.4180439, 0.4008365);
const float PALL_DUST_G = 0.375;
const float PALL_MIST_G = 0.4594595;
const float PALL_SOOT_G = 0.3333333;
// альбедо земли под пеленой: суша и вода (PallLight.LAND, SEA)
const float PALL_LAND = 0.2;
const float PALL_SEA = 0.06;

// Пелена слота в точке (pallField)
struct PallAt {
    // ослабление без шума (1/блок): пыль, мгла, водяная пыль (брызги и базисная волна)
    vec3 sigma;
    // столбы над точкой (ослабление × блоки): то же
    vec3 column;
    // столбы всей пелены над полом слоя (pallField: floorUp; точка ниже него — над точкой): толща слоя, в котором точка
    vec3 slab;
    // доля дыма в пыли; свет пожаров снизу
    float smoke;
    float fire;
    // высота точки в долях пелены: пыль с брызгами, мгла
    vec2 height;
    // доля самой густой пыли (с водяной пылью — по всему поднятому)
    float dense;
    // доля воды там, откуда подняло пыль и брызги (в первые секунды, пока снос мал, — под точкой)
    float wet;
};

// доля густоты у земли на высоте x пелены (PallField.profile)
float pallProfile(float x) {
    if (x <= 0.0) return smoothstep(-1.0, -0.4, x);
    return (1.0 - smoothstep(0.45, 1.0, x)) * (0.55 + 0.45 * exp(-2.5 * x));
}

// интеграл профиля от x до верха, в долях высоты (PallField.column)
float pallColumn(float x) {
    float xa = clamp(x, 0.0, 1.0);
    float above = 0.548 * pow(1.0 - xa, 2.45) * (1.0 + 0.83 * xa);
    if (x >= 0.0) return above;
    float f = clamp((-x - 0.4) / 0.6, 0.0, 1.0);
    return above + min(-x, 0.4) + 0.6 * (f - f * f * f + 0.5 * f * f * f * f);
}

// край таблицы: к нему поле тает (PallField.edge)
float pallEdge(float u) {
    return 1.0 - smoothstep(0.96, 1.0, u);
}

// базисная волна по радиусу (PallField.surgeProfile): u — доля её радиуса; у края катится вал — гуще
float pallSurge(float u) {
    return (0.35 + 0.65 * smoothstep(0.2, 0.85, u)) * (1.0 - smoothstep(0.9, 1.05, u));
}

// верх базисной волны по радиусу, доля её высоты (PallField.surgeTop): вал у края выше
float pallSurgeTop(float u) {
    return 0.55 + 0.45 * smoothstep(0.3, 0.95, u);
}

// доля воды под точкой rel (от эпицентра по земле) по карте слота (PallGround.water); карты нет (G.x = 0) — суша
float pallWater(sampler2D ground, vec4 G, vec2 rel) {
    if (G.x <= 0.0) return 0.0;
    return clamp(textureLod(ground, rel / (2.0 * G.x) + 0.5, 0.0).g, 0.0, 1.0);
}

// Поле слота S в точке p (от глаза), без шума (среднее по шуму — оно); ground, G — карта рельефа и воды слота, W — вода
// слота; floorUp — пол слоя под точкой (верх рельефа, блоки над землёй под эпицентром; 0 — земля под эпицентром): от него
// считается slab. Вещество — по воде там, откуда его подняло (PallField: пыль, снесённая на воду, остаётся пылью): пыль и брызги —
// у точки за вычетом сноса, где читается и таблица; мгла — у её источника на оси шлейфа (над водой пожаров нет), только
// если она здесь есть; базисная волна — вода, поднятая у эпицентра, над чем бы ни катилась (W.z = 0 — её нет). Без воды
// под пеленой (W.w = 0) карта не читается.
PallAt pallField(sampler2D table, sampler2D ground, mat4 S, vec4 G, vec4 W, vec3 p, float floorUp) {
    vec2 q = p.xz - S[0].xy, lift = q - S[1].xy;
    float above = p.y - S[0].z, reach = S[0].w, r = length(lift);
    float ud = r / reach;
    vec4 dust = texture(table, vec2(min(ud, 1.0), S[2].z));
    float topD = max(dust.g, 1.0), edge = pallEdge(ud);
    float sd = dust.r * edge, ss = dust.a * edge;
    float len = S[1].z, along = clamp(dot(q, S[2].xy), 0.0, len);
    vec2 source = q - S[2].xy * along;
    float uh = length(source) / reach;
    vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));
    float topH = max(haze.g, 1.0), sh = haze.r * (1.0 - 0.5 * along / max(len, 1.0)) * pallEdge(uh), fire = haze.b;
    float wet = 0.0, sg = 0.0, topG = 1.0;
    if (W.w > 0.5) {
        if (sd + ss > 0.0) wet = pallWater(ground, G, lift);
        sd *= 1.0 - wet;
        ss *= wet;
        if (sh + fire > 0.0) {
            float dry = 1.0 - pallWater(ground, G, source);
            sh *= dry;
            fire *= dry;
        }
    }
    if (W.z > 0.0) {
        float ug = r / max(W.x, 1.0);
        sg = W.z * pallSurge(ug);
        topG = max(W.y * pallSurgeTop(ug), 1.0);
    }
    PallAt a;
    a.height = vec2(above / topD, above / topH);
    float hg = above / topG;
    a.sigma = vec3(sd * pallProfile(a.height.x), sh * pallProfile(a.height.y), ss * pallProfile(a.height.x) + sg * pallProfile(hg));
    a.column = vec3(sd * topD * pallColumn(a.height.x), sh * topH * pallColumn(a.height.y),
            ss * topD * pallColumn(a.height.x) + sg * topG * pallColumn(hg));
    float floorD = pallColumn(min(a.height.x, floorUp / topD));
    a.slab = vec3(sd * topD * floorD, sh * topH * pallColumn(min(a.height.y, floorUp / topH)),
            ss * topD * floorD + sg * topG * pallColumn(min(hg, floorUp / topG)));
    a.smoke = dust.b;
    a.fire = fire * exp(-3.0 * max(a.height.y, 0.0));
    a.dense = (sd + ss + sg) / max(S[3].x, 1e-6);
    a.wet = wet;
    return a;
}

// Форма клубов по шуму (VolumeNoise.shape): n — каналы плитки (клубы, мелкие края), fill — насколько пелена сплошная;
// среднее по плитке — 1, густота — плавно от просветов до вдвое (у края — втрое) гуще средней.
float pallShape(vec2 n, float fill) {
    return smoothstep(0.4 - 0.45 * fill, 0.7, n.x - 0.3 * (1.0 - n.y)) / (0.3 + 0.225 * fill);
}

// Мелкие клубы (VolumeNoise.detail): r — канал клубов плитки вчетверо мельче; множитель густоты 0,3..1,7, в среднем по
// плитке — 1 (канал равномерен), поэтому средняя густота поля остаётся его густотой. Пелена — каскад: крупные клубы,
// внутри них мелкие; мельче того, что луч различает (пиксель, шаг), мелкие — их среднее, 1 (fx_pall: fineAt).
float pallDetail(float r) {
    return 0.3 + 1.4 * smoothstep(0.2, 0.8, r);
}

// Отрезок луча (из глаза, dir — единичный) в рамке [lo, hi]: (вход, выход); вход > выхода — мимо.
vec2 pallBox(vec3 dir, vec3 lo, vec3 hi) {
    vec3 inv = 1.0 / (dir + vec3(equal(dir, vec3(0.0))) * 1e-6);
    vec3 a = lo * inv, b = hi * inv;
    vec3 t0 = min(a, b), t1 = max(a, b);
    return vec2(max(max(t0.x, t0.y), max(t0.z, 0.0)), min(min(t1.x, t1.y), t1.z));
}

// ---------------------------------------------------------------- рельеф под пеленой (client/nuclear/PallGround)
// Карта слота: r — высоты верха рельефа над землёй под эпицентром, g — доля воды (pallWater); середина карты — эпицентр,
// края — как у края; G = (полсторона карты; низ и верх карты; клетка — блоки), G.x = 0 — карты нет.

// верх рельефа под точкой xz (от глаза) — от глаза по y (PallGround.at)
float pallGroundAt(sampler2D ground, mat4 S, vec4 G, vec2 xz) {
    return S[0].z + textureLod(ground, (xz - S[0].xy) / (2.0 * G.x) + 0.5, 0.0).r;
}

// Где луч dir из глаза (единичный) уходит под рельеф слота, до end; 1e9 — не уходит. Мира за пикселем нет: луч прошёл
// всё, что нарисовано (дальше from по земле от глаза — дальность прорисовки), ничего не задев, — ищем только дальше.
// Шаги растут как расстояние (вдали клетка карты на экране мельче), начало сдвинуто на jitter шага, последний — на end;
// между шагом над рельефом и шагом под ним — по секущей. Карта грубее мира: если у начала она выше луча (он прошёл над
// настоящим рельефом), там она опускается под луч и к четырём клеткам дальше — снова как есть.
float pallFloor(sampler2D ground, mat4 S, vec4 G, vec3 dir, float from, float end, int steps, float jitter) {
    if (G.x <= 0.0 || S[1].w < 0.5) return 1e9;
    // рамка карты: по земле — карта, сверху — верх рельефа, снизу — без края (луч вниз кончится под рельефом)
    vec2 span = pallBox(dir, vec3(S[0].x - G.x, -1e6, S[0].y - G.x), vec3(S[0].x + G.x, S[0].z + G.z, S[0].y + G.x));
    float fade = 4.0 * G.w, near = from / max(length(dir.xz), 1e-3);
    float ta = max(max(span.x, near), 1.0);
    float tb = min(span.y, end);
    // ниже самого низкого места карты луч точно под рельефом (и там, где карта у начала опущена, — уже нет)
    if (dir.y < 0.0) tb = min(tb, max((S[0].z + G.y) / dir.y, ta) + fade);
    if (tb <= ta) return 1e9;
    float g0 = pallGroundAt(ground, S, G, dir.xz * ta);
    // опускать — только там, где луч прошёл нарисованный мир; вошёл в карту дальше — до неё он шёл над неизвестным
    float lift = max(g0 + 1.0 - dir.y * ta, 0.0) * step(ta, near + 1.0);
    float ratio = pow(tb / ta, 1.0 / float(steps));
    float prevT = ta, prevD = dir.y * ta - g0 + lift;
    float t = ta * pow(ratio, jitter);
    for (int i = 0; i <= steps; i++) {
        float tt = min(t, tb);
        vec3 q = dir * tt;
        float d = q.y - pallGroundAt(ground, S, G, q.xz) + lift * max(1.0 - (tt - ta) / fade, 0.0);
        // начало уже под картой (верх рамки — по высотам до R16F, текстура бывает чуть выше) — луч кончается в начале
        if (d <= 0.0) return prevT + (tt - prevT) * max(prevD, 0.0) / max(prevD - d, 1e-6);
        prevT = tt;
        prevD = d;
        t *= ratio;
    }
    return 1e9;
}

// Расстояние до мира за пикселем (SceneDepth), годное для пелены: неба, а ещё NaN, бесконечность и «за глазом» (чужая
// глубина LOD, дальняя плоскость) — 1e9, мира за пикселем нет. Иначе луч шёл бы сквозь километры пыли или NaN
// попадал бы в накопленную пелену — чёрный кадр.
float pallScene(float d) {
    if (d < 0.0) return 1e9;
    if (d < 1e8) return d;
    return 1e9;
}

// Пиксель пелены годен: свет конечен (и в половинной точности кадра), пропускание 0..1; NaN не годен.
bool pallValid(vec4 v) {
    return all(lessThan(abs(v.rgb), vec3(6.0e4))) && v.a >= 0.0 && v.a <= 1.0;
}

// Отсвет на пыли, как у стены пыли (CloudPuffs.lit и CloudPuffs.burn — её свет; fx_pall берёт его, чтобы пелена за
// фронтом продолжала цвет стены): r — по земле от эпицентра, up — доля высоты пыли 0..1 (низ темнее: земля и обломки).
// r и радиусы — в одних единицах (блоки).
// Свет шара, потом раскалённой шапки сверху: overhead 0..1, lightR — шесть радиусов шара (там отсвет вдвое слабее).
float pallLit(float r, float up, float overhead, float lightR) {
    float x = r / max(lightR, 1e-3);
    return (0.3 + 0.4 * up) * overhead / (1.0 + x * x);
}

// Пожары под пылью: glow — накал гриба 0..1 (стена берёт его же), fireR — край зоны поджига (мягкий, от 0,92 до 1,04).
float pallBurn(float r, float up, float glow, float fireR) {
    float e = max(fireR, 1e-3);
    return (1.0 - smoothstep(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
}

// ---------------------------------------------------------------- свет в пелене (client/nuclear/PallLight)
// Два потока Эддингтона в плоском слое после дельта-Эддингтона: излучение I(τ, μ) = I0 + μ·I1, μ — косинус к вертикали
// (вверх — плюс), τ — толща от верха слоя вниз. Законы и вывод — PallLight (PallGlslTest сверяет числа).

// Свет пелены считается в линейных долях (перенос линеен по излучению), кадр Minecraft — значения экрана (sRGB без
// перевода): v экрана — v^2,2 линейного (PallLight.GAMMA). Чистая степень: перевод произведения — произведение переводов
float pallLinear(float v) {
    return pow(max(v, 0.0), 2.2);
}

vec3 pallLinear3(vec3 v) {
    return vec3(pallLinear(v.r), pallLinear(v.g), pallLinear(v.b));
}

float pallDisplay(float v) {
    return pow(max(v, 0.0), 0.4545455);
}

// Свет, что на экране прибавляет x к белой стене поверх света неба base (тоже на экране: отсвет стены пыли, зарево
// пожаров — CloudPuffs складывает их на экране), в линейном: (base + x)^γ − base^γ (PallLight.lift)
float pallLift(float base, float x) {
    return pow(max(base + x, 0.0), 2.2) - pow(max(base, 0.0), 2.2);
}

vec3 pallLift3(float base, vec3 x) {
    return vec3(pallLift(base, x.r), pallLift(base, x.g), pallLift(base, x.b));
}

// Свет огня (пожары, шторм, смерч), что на экране прибавляет x к белой стене поверх света неба base, в линейном — не
// ярче самого пламени: его цвет на экране flame, излучение — pallLinear(flame). Рассеивающее вещество не ярче своего
// источника (по каждому каналу): белая стена, вся в огне, — цвета огня, а не ярче. Отсвет, что на экране уже доходит до
// цвета пламени (стена пыли над пожарами ночью — CloudPuffs: на экране и есть цвет огня), обращением через цвет пыли
// (/ DUST, pallLift) давал свет в разы ярче пламени (PallLight.flame)
vec3 pallFlame(float base, vec3 x, vec3 flame) {
    return min(pallLift3(base, x), vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b)));
}

// Затухание рассеянного света с толщей: k² = 3(1 − ω)(1 − ωg) (в густой пыли — e^(−kτ), а не e^(−τ))
float pallK(float w, float g) {
    return sqrt(max(3.0 * (1.0 - w) * (1.0 - w * g), 1e-6));
}

// Косинус пучка в частном решении (pallDiffuse): у пучка, что гаснет так же, как рассеянный свет (kμ = 1), знаменатель
// частного решения k²μ² − 1 обнуляется. В полосе |k²μ² − 1| < 0,05 пучок сдвинут к её краю — весь: в частном решении,
// в своём пропускании и на земле. Решение остаётся решением уравнения, косинус — в пределах 2,5%; зажатый знаменатель
// при прежнем μ решением не был: у дыма (k > 1) около полудня свет в слое выходил в разы не тот
float pallBeamMu(float k, float mu) {
    float d = k * k * mu * mu - 1.0;
    if (abs(d) >= 0.05) return mu;
    if (d < 0.0) return 0.9746794 / k;
    return 1.0246951 / k;
}

// Рассеянный свет на глубине tau слоя толщи tc (альбедо w, асимметрия g, земля под ним — альбедо ground): I0 + c·I1.
// c = g·μ глаза — то, что точка рассеивает к глазу (без ω); c = ±2/3 — поток вверх или вниз, делённый на π. Источники:
// два пучка сверху (косинус к вертикали mu, сила f — что отражает белая стена к пучку), небо сверху top и свет снизу
// bottom (излучение вверх, сверх отражённого землёй). Решение — в затухающих экспонентах: e^(−kτ) от верха и e^(−k(tc − τ))
// от низа — без переполнения в толстом слое; k ≥ 10⁻³ — и у непоглощающего тумана (k = 0) матрица не вырождена.
// Пучок, что гаснет так же, как рассеянный свет (kμ = 1), — со сдвинутым косинусом (pallBeamMu): не делит на 0.
float pallDiffuse(float w, float g, float tau, float tc, float ground, float mu1, float f1, float mu2, float f2, float top, float bottom, float c) {
    float q = 1.0 - w * g;
    float k = sqrt(max(3.0 * (1.0 - w) * q, 1e-6));
    float p = 0.6666667 * k / q;
    float e = exp(-k * tc);
    float m1 = pallBeamMu(k, mu1), m2 = pallBeamMu(k, mu2);
    float a1 = 0.75 * w * m1 * m1 * f1 * (1.0 + g * (1.0 - w)) / (k * k * m1 * m1 - 1.0);
    float b1 = 0.75 * w * m1 * f1 - 3.0 * m1 * (1.0 - w) * a1;
    float a2 = 0.75 * w * m2 * m2 * f2 * (1.0 + g * (1.0 - w)) / (k * k * m2 * m2 - 1.0);
    float b2 = 0.75 * w * m2 * f2 - 3.0 * m2 * (1.0 - w) * a2;
    float e1 = exp(-tc / m1), e2 = exp(-tc / m2);
    float r1 = top - a1 - a2 + 0.6666667 * (b1 + b2);
    float r2 = bottom + ground * (m1 * f1 * e1 + m2 * f2 * e2) - ((1.0 - ground) * a1 + 0.6666667 * (1.0 + ground) * b1) * e1
            - ((1.0 - ground) * a2 + 0.6666667 * (1.0 + ground) * b2) * e2;
    float m11 = 1.0 + p, m12 = e * (1.0 - p), m21 = e * (1.0 - ground - (1.0 + ground) * p), m22 = 1.0 - ground + (1.0 + ground) * p;
    float det = m11 * m22 - m12 * m21;
    float c1 = (r1 * m22 - m12 * r2) / det, c2 = (m11 * r2 - m21 * r1) / det;
    float x = exp(-k * tau), y = exp(-k * (tc - tau));
    float t1 = exp(-tau / m1), t2 = exp(-tau / m2);
    float i0 = c1 * x + c2 * y + a1 * t1 + a2 * t2;
    float i1 = k / q * (c2 * y - c1 * x) + b1 * t1 + b2 * t2;
    return i0 + c * i1;
}

// ---------------------------------------------------------------- стена пыли (client/nuclear/BlastWall)
// Голова стены — вал у земли за фронтом ударной волны, кольцом вокруг эпицентра (со сносом пыли, как у пелены): то,
// что ветер за фронтом поднимает сейчас. Законы огибающей — копии BlastWall (PallGlslTest сверяет). Слот стены V — mat4
// по столбцам (BlastWall.slot):
//   [0] = (передний край пыли от эпицентра, радиус вала, верх головы — блоки; ослабление в самой густой части, 1/блок;
//          0 — головы нет)
//   [1] = (угол вала, рад; над водой — брызги: отставание от края пыли, блоки; доля высоты; ослабление, 1/блок)
//   [2] = (клубы — две фазы шума вдоль кольца: окружность головы в плитках у каждой; вес первой; плитка шума, блоки)
//   [3] = (номера фаз — у каждой свои клубы; 0; 0)
// Облако Вильсона слота — vec4 (там же): радиус середины оболочки от точки подрыва, её полутолщина (блоки), ослабление
// в середине (1/блок; 0 — облака нет), высота точки подрыва над землёй под эпицентром.

// Клубы выедают край головы на эту долю огибающей внутрь (по шуму 0..1: впадины — глубже); мягкость края клуба
const float WALL_BITE = 0.45;
const float WALL_SOFT = 0.12;

// Насколько точка внутри огибающей головы (BlastWall.inside): 1 — ось вала, 0 — край, меньше — снаружи. s — за передним
// краем пыли (блоки; впереди — меньше нуля), z — над рельефом (блоки), a — радиус вала, top — верх головы. Вал —
// полушириной 1,5 a за краем, во всю высоту: спереди у земли — стоячая стена, сверху скруглён; за серединой вала хвост
// ниже, к 0,55 высоты.
float pallWallInside(float s, float z, float a, float top) {
    float w = 1.5 * a, b = 0.5 * top;
    float x = (s - w) / max(w, 0.001), y = (z - b) / max(b, 0.001);
    if (x >= 0.0) return 1.0 - max(y / (1.0 - 0.9 * smoothstep(0.0, 2.0, x)), 0.0);
    if (y <= 0.0) return 1.0 + x;
    return 1.0 - sqrt(x * x + y * y);
}

// Хвост и низ головы 0..1 (BlastWall.fade): хвост тает с 0,6 до 2 полуширин вала за его серединой (дальше пыль — пелена),
// под рельефом карты — глубже четверти высоты (карта грубее мира: в низинах под ней голова не висит)
float pallWallFade(float s, float z, float a, float top) {
    float w = 1.5 * a;
    return (1.0 - smoothstep(0.6, 2.0, (s - w) / max(w, 0.001))) * smoothstep(-0.5 * top, -0.25 * top, z);
}

// Доля густоты головы без шума (среднее по клубам): край выеден клубами на WALL_BITE (BlastWall.mean)
float pallWallMean(float s, float z, float a, float top) {
    return clamp(pallWallInside(s, z, a, top) / WALL_BITE, 0.0, 1.0) * pallWallFade(s, z, a, top);
}

// Голова стены в точке
struct WallAt {
    // с поправкой на воду под точкой: радиус вала, верх головы, сдвиг назад (блоки), ослабление (1/блок); P.w = 0 — точка
    // вне головы
    vec4 P;
    // за передним краем пыли и над рельефом, блоки
    float s;
    float z;
    // доля воды под точкой: над водой голова — брызги, ниже и позади
    float wet;
    // точка от оси кольца (эпицентр со сносом), блоки
    vec2 rel;
};

// Голова стены слота S, V в точке p (от глаза): floorUp — верх рельефа под точкой над землёй под эпицентром (блоки),
// ground, G, W — карта рельефа и воды слота и её числа, вода слота (воду карта читает, только если она под пеленой есть)
WallAt pallWallAt(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 p, float floorUp) {
    WallAt h;
    h.rel = p.xz - S[0].xy - S[1].xy;
    h.s = V[0].x - length(h.rel);
    h.z = p.y - S[0].z - floorUp;
    h.wet = 0.0;
    h.P = vec4(V[0].y, V[0].z, 0.0, 0.0);
    float back = 4.5 * V[0].y + V[1].y;
    if (V[0].w <= 0.0 || h.s < -1.0 || h.s > back || h.z > V[0].z || h.z < -0.5 * V[0].z) return h;
    if (W.w > 0.5) h.wet = pallWater(ground, G, p.xz - S[0].xy);
    float f = mix(1.0, V[1].z, h.wet);
    h.P = vec4(V[0].y * f, V[0].z * f, V[1].y * h.wet, mix(V[0].w, V[1].w, h.wet));
    return h;
}

// Отрезки луча dir из глаза на [ta, tb], где может быть голова слота: кольцо от переднего края пыли до конца хвоста (с
// брызгами), по высоте — от низа карты рельефа до её верха с головой. (x, y) и (z, w) — вход и выход; выход ≤ входа — мимо
vec4 pallWallSpans(mat4 S, mat4 V, vec4 G, vec3 dir, float ta, float tb) {
    vec4 none = vec4(1.0, 0.0, 1.0, 0.0);
    if (V[0].w <= 0.0 || S[1].w < 0.5) return none;
    vec2 c = S[0].xy + S[1].xy;
    float top = V[0].z, outer = V[0].x + 1.0, inner = max(V[0].x - 4.5 * V[0].y - V[1].y, 0.0);
    // по высоте: карта рельефа (нет её — земля под эпицентром) с головой
    float lo = S[0].z + min(G.y, 0.0) - 0.5 * top, hi = S[0].z + max(G.z, 0.0) + top;
    if (abs(dir.y) > 1e-6) {
        float y0 = lo / dir.y, y1 = hi / dir.y;
        ta = max(ta, min(y0, y1));
        tb = min(tb, max(y0, y1));
    } else if (lo > 0.0 || hi < 0.0) {
        return none;
    }
    float a = dot(dir.xz, dir.xz), b = dot(dir.xz, c);
    if (a < 1e-10 || tb <= ta) return none;
    float cc = dot(c, c), disc = b * b - a * (cc - outer * outer);
    if (disc <= 0.0) return none;
    float q = sqrt(disc);
    vec2 out1 = vec2(b - q, b + q) / a;
    vec4 spans = vec4(max(out1.x, ta), min(out1.y, tb), 1.0, 0.0);
    float din = b * b - a * (cc - inner * inner);
    if (inner > 0.0 && din > 0.0) {
        float qi = sqrt(din);
        vec2 in1 = vec2(b - qi, b + qi) / a;
        spans = vec4(max(out1.x, ta), min(in1.x, tb), max(in1.y, ta), min(out1.y, tb));
    }
    return spans;
}

// Толща головы слота без шума на отрезке луча [ta, tb] (dir из глаза), steps шагов на каждом её отрезке (pallWallSpans):
// что голова закрывает перед квадратом слоя (fx.vsh). Пол — рельеф карты под каждой точкой
float pallWallTau(sampler2D ground, mat4 S, mat4 V, vec4 G, vec4 W, vec3 dir, float ta, float tb, int steps) {
    vec4 spans = pallWallSpans(S, V, G, dir, ta, tb);
    float tau = 0.0;
    for (int k = 0; k < 2; k++) {
        vec2 sp = k == 0 ? spans.xy : spans.zw;
        if (sp.y <= sp.x) continue;
        float ds = (sp.y - sp.x) / float(steps);
        for (int i = 0; i < steps; i++) {
            vec3 q = dir * (sp.x + (float(i) + 0.5) * ds);
            float floorUp = G.x > 0.0 ? pallGroundAt(ground, S, G, q.xz) - S[0].z : 0.0;
            WallAt h = pallWallAt(ground, S, V, G, W, q, floorUp);
            if (h.P.w > 0.0) tau += h.P.w * pallWallMean(h.s - h.P.z, h.z, h.P.x, h.P.y) * ds;
        }
    }
    return tau;
}

// Облако Вильсона слота на луче dir из глаза до end: до двух мест, где луч проходит оболочку (x, z — где, блоки), и толща
// там (y, w; 0 — мимо). Оболочка гауссова поперёк: толща на проходе — √π·полутолщина·ослабление на косинус луча к
// оболочке; по касательной — не больше, чем у оболочки радиуса r (косинус не меньше 0,35), и дальше от середины —
// тает. Ниже земли под эпицентром облака нет
vec4 pallWilson(mat4 S, vec4 L, vec3 dir, float end) {
    vec4 none = vec4(0.0);
    if (L.z <= 0.0 || S[1].w < 0.5) return none;
    vec3 b = vec3(S[0].x, S[0].z + L.w, S[0].y);
    float along = dot(dir, b), q2 = max(dot(b, b) - along * along, 0.0), r = L.x, w = max(L.y, 1e-3);
    float base = 1.7724539 * w * L.z;
    vec4 hit = none;
    if (q2 < r * r) {
        float h = sqrt(r * r - q2);
        float tau = base / max(h / r, 0.35);
        hit = vec4(along - h, tau, along + h, tau);
    } else {
        float q = sqrt(q2);
        float x = (q - r) / w;
        hit = vec4(along, 2.0 * base / 0.35 * exp(-x * x), 1.0, 0.0);
    }
    // за глазом, за миром и под землёй — нет
    if (hit.x < 0.0 || hit.x > end || dir.y * hit.x < S[0].z) hit.y = 0.0;
    if (hit.z < 0.0 || hit.z > end || dir.y * hit.z < S[0].z) hit.w = 0.0;
    return hit;
}

// ---------------------------------------------------------------- огненный шторм (client/nuclear/FireStorm)
// Законы — копии FireStorm (PallGlslTest сверяет). Слот огня F — mat4 по столбцам, у каждого слота подрыва S свой:
//   [0] = (половина стороны карты огня, блоки (0 — шторма нет); клетка карты, блоки; верх столбов над землёй под
//          эпицентром, блоки; высота, на которой снос столба вдвое больше прямого, блоки)
//   [1] = (снос столба по ветру x, z на блок подъёма — ветер против подъёма дыма; подъём дыма, блоков/с;
//          зарево снизу — сила с ночью и доверием)
//   [2] = (пламя на поверхностях — сила с днём и доверием; ослабление в столбе на единицу подачи дыма, 1/блок;
//          секунды после подрыва; 0)
//   [3] = (свет пожаров на экране rgb; облака Minecraft от глаза по y — пламя на поверхностях только ниже них)
// Карта огня: R — пламя, G — подача дыма в столбы, B — G × доля подъёма столба; уменьшенные копии — скопления
// источников (свет и дым на высоте h — с уровня, где клетка карты — ширина столба или зарева там).

// снос столба на высоте h над землёй, блоки на единицу отношения ветра к подъёму (FireStorm.bend)
float fireBend(float h, float hb) {
    float x = max(h, 0.0);
    return x * (1.0 + x / hb);
}

// полуширина столба на высоте h, блоки (FireStorm.width): у земли уже клетки, выше растёт
float fireWidth(float h, float cell) {
    return 0.3 * cell + 0.22 * max(h, 0.0);
}

// уровень карты огня, где клетка — ширина w (FireStorm.lod)
float fireLod(float w, float cell) {
    return log2(max(2.0 * w / cell, 1.0));
}

// уровень карты для зарева на высоте h: свет с площади шириной ~2h (FireStorm.glowLod)
float fireGlowLod(float h, float cell) {
    return log2(max(2.0 * max(h, 6.0) / cell, 1.0));
}

// столб по высоте: от низин до верха, где он растекается в мглу (FireStorm.column)
float fireColumn(float h, float top) {
    return smoothstep(-40.0, 0.0, h) * (1.0 - smoothstep(0.65 * top, top, h));
}

// место на карте огня слота под точкой xz (от глаза)
vec2 fireUv(mat4 S, mat4 F, vec2 xz) {
    return (xz - S[0].xy) / (2.0 * F[0].x) + 0.5;
}
