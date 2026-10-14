package ua.zentix.incoming.defense;

import ua.zentix.incoming.guidance.Ballistics;

/**
 * Паспорт зенитной ракеты: числа её полёта. Дальности радара и огня, вероятность поражения, число направляющих
 * и перезарядка — настройки мира ({@code air_defense}): их подбирают под сервер, а не под ракету.
 *
 * @param railTicks   первые тики после схода нос держит угол направляющей и на цель не доворачивает
 * @param elevation   угол направляющей над горизонтом, градусов: ракета уходит вверх в сторону цели (90 — отвесно)
 * @param launchSpeed скорость схода, блоков/тик
 * @param boostTicks  тики разгона
 * @param boostAccel  прирост скорости за тик разгона, блоков/тик²
 * @param maxSpeed    наибольшая скорость, блоков/тик
 * @param lateralG    наибольшее боковое ускорение, g: предел поворота на любой скорости ({@link #turnLimit}), поэтому
 *                    медленная ракета поворачивает круче
 * @param fuelTicks   столько тиков летит, потом самоликвидация
 * @param fuse        радиус неконтактного взрывателя, блоков (сверх полудлины корпуса цели)
 * @param noseLength  полудлина корпуса, блоков (модель и срез сопла — {@code client.render.InterceptorRenderer})
 */
public record InterceptorSpec(int railTicks, double elevation, double launchSpeed, int boostTicks, double boostAccel, double maxSpeed,
                              double lateralG, int fuelTicks, double fuse, double noseLength) {
    /**
     * Ракета ЗРК средней дальности (как 9М96 или PAC-3, медленнее настоящей — полёт видно): вертикальный пуск из
     * контейнера, как у 9М96 на С-350 и С-400, 14 блоков/тик (280 м/с) за полторы секунды разгона, 15 с полёта — ~4 км.
     * Боковое ускорение 40 g: у 9М96 открытые источники называют «свыше 20 g» на высотах до 35 км (FAS, «S-400 SA-20
     * Triumf», https://nuke.fas.org/guide/russia/airdef/s-400.htm), у земли воздух плотнее и манёвр круче. Число — предел
     * игры: с 30 g ракета не догоняет шахед, который к пуску уже над ЗРК и пикирует за ним в цель
     * ({@code InterceptorFlightTest.catchesDivingTargetOverhead}). После схода (3 блока/тик) — поворот ~18°/тик, из отвеса
     * к цели у горизонта — дугой ~0,3 с; на полной скорости — 4°/тик (радиус ~200 блоков). Взрыватель — 6 блоков: шахед на 2 блоках/тик она проходит за тик с запасом.
     */
    public static final InterceptorSpec SAM = new InterceptorSpec(5, 90, 0.8, 30, 0.45, 14, 40, 300, 6, 2.0);

    /**
     * Предел поворота за тик на скорости {@code speed}, °: боковое ускорение у ракеты одно (a = v·ω, ω = a / v), поэтому
     * на разгоне она поворачивает круче, чем на наибольшей скорости. С одной угловой скоростью ракета, сошедшая
     * с направляющей навстречу цели, которая проходит над самым ЗРК, разворачивалась бы уже за ней и шла за пикирующим
     * шахедом до земли.
     */
    public double turnLimit(double speed) {
        return Math.toDegrees(lateralG * Ballistics.GRAVITY / Math.max(speed, launchSpeed));
    }
}
