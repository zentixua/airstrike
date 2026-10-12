#version 150

// Модели снарядов вдали в плитки атласа (client/render/FarModels): вырезка, как у ванильной сущности; в атлас — с
// умноженной альфой, чтобы мип-уровни усредняли закрытое и свет без тёмной каймы по краю силуэта.
uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor;
    fragColor = vec4(color.rgb * color.a, color.a);
}
