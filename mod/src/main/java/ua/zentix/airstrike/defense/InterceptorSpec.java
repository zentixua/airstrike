package ua.zentix.airstrike.defense;

/**
 * Паспорт зенитной ракеты: числа её полёта. Дальности радара и огня, вероятность поражения, число направляющих
 * и перезарядка — настройки мира ({@code air_defense}): их подбирают под сервер, а не под ракету.
 *
 * @param railTicks   первые тики после схода нос держит угол направляющей и на цель не доворачивает
 * @param elevation   угол направляющей над горизонтом, градусов: ракета уходит вверх в сторону цели
 * @param launchSpeed скорость схода, блоков/тик
 * @param boostTicks  тики разгона
 * @param boostAccel  прирост скорости за тик разгона, блоков/тик²
 * @param maxSpeed    наибольшая скорость, блоков/тик
 * @param turnRate    предельная скорость поворота, °/тик
 * @param fuelTicks   столько тиков летит, потом самоликвидация
 * @param fuse        радиус неконтактного взрывателя, блоков (сверх полудлины корпуса цели)
 * @param noseLength  полудлина корпуса, блоков (модель и срез сопла — {@code client.render.InterceptorRenderer})
 */
public record InterceptorSpec(int railTicks, double elevation, double launchSpeed, int boostTicks, double boostAccel, double maxSpeed,
                              double turnRate, int fuelTicks, double fuse, double noseLength) {
    /**
     * Ракета ЗРК средней дальности (как 9М96 или PAC-3, медленнее настоящей — полёт видно): 14 блоков/тик (280 м/с)
     * за полторы секунды разгона, разворот 9°/тик (радиус ~90 блоков на полной скорости), 15 с полёта — ~4 км.
     * Взрыватель — 6 блоков: шахед на 2 блоках/тик она проходит за тик с запасом.
     */
    public static final InterceptorSpec SAM = new InterceptorSpec(5, 65, 0.8, 30, 0.45, 14, 9, 300, 6, 2.0);
}
