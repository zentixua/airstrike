#version 150

// Плитки дальних моделей в кадр (client/far/FarSprites). Атлас (client/render/FarModels) уже с умноженной альфой;
// цвет вершины — доля, которую модель закрывает и светит (дымка воздуха и переход от точки), во всех четырёх каналах.
// Смешивание — ONE, ONE_MINUS_SRC_ALPHA, как у остального дальнего.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
}
