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

// Водяная пыль (брызги, базисная волна) — капли в десятки микрон: почти не поглощают и рассеивают вперёд (g ≈ 0,85).
// Свет, что идёт к точке сквозь неё (солнце, небо, отсвет шара; тень на мире), почти весь проходит вперёд: по
// дельта-Эддингтону толща для света — (1 − g²) от полной, а остаток рассеяния (g ≈ 0,46) — как у пыли (её фаза). Луч
// зрения — по полной толще: туман размывает и гасит то, что за ним.
const float PALL_MIST_TRANSPORT = 0.28;

// Пелена слота в точке (pallField)
struct PallAt {
    // ослабление без шума (1/блок): пыль, мгла, водяная пыль (брызги и базисная волна)
    vec3 sigma;
    // столбы над точкой (ослабление × блоки): то же
    vec3 column;
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
// слота. Вещество — по воде там, откуда его подняло (PallField: пыль, снесённая на воду, остаётся пылью): пыль и брызги —
// у точки за вычетом сноса, где читается и таблица; мгла — у её источника на оси шлейфа (над водой пожаров нет), только
// если она здесь есть; базисная волна — вода, поднятая у эпицентра, над чем бы ни катилась (W.z = 0 — её нет). Без воды
// под пеленой (W.w = 0) карта не читается.
PallAt pallField(sampler2D table, sampler2D ground, mat4 S, vec4 G, vec4 W, vec3 p) {
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
