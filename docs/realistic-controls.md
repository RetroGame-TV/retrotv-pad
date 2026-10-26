# Controles com acabamento tridimensional

Os quatro desenhos fornecidos como referência foram inspecionados visualmente. A implementação mantém a composição adaptável dos controles nativos e reproduz seus materiais em `RealisticPadArt`, usando paths, gradientes, contornos, sombras e símbolos vetoriais do Android Canvas.

O visual é um **modo adicional e opcional**. O padrão continua **Clássica**, inclusive para quem atualiza o app. Ative em **chip do console > Mais opções > Aparência dos controles > Realista**. A preferência fica salva; voltar a Clássica restaura o desenho anterior. O renderer realista e sua textura são inicializados apenas quando necessários. Skins importadas e os layouts GB, Arcade e TV não mudam de aparência.

![Seletor de aparência com o clássico como padrão](images/appearance-picker.png)

| Console | Acabamento |
|---|---|
| NES | Moldura clara chanfrada, painel preto rebaixado, faixas centrais, teclas de borracha e botões vermelhos em bases quadradas. |
| SNES | Carcaça cinza acetinada, ombros lilás, discos rebaixados e botões coloridos convexos com letras claras. |
| PS1 | Carcaça cinza, quatro ombros, símbolos coloridos desenhados por paths e dois analógicos com aros, concavidade e pontos de aderência. |
| Mega Drive | Plástico preto, aros claros no direcional e nas duas fileiras inclinadas, seis botões escuros e letras cinza. |

São desenhos próprios. Não há imagens completas usadas como botões, modelos 3D, bibliotecas novas, logotipos ou assets extraídos das referências. A granulação usa um tile procedural de 64×64 pixels (16 KiB), criado uma vez por renderer e repetido por shader em uma única operação. As partes interativas continuam vetoriais. Não há animação contínua nem geração de ruído a cada evento.

## Integração e interação

`ControllerView` escolhe o novo renderer somente quando o usuário ativa Realista, para os quatro consoles e somente quando não está desenhando uma skin Delta importada. O renderer recebe os mesmos `PadControl` e `PadState` já usados pelo input. A aparência de pressão é imediata: o encaixe permanece fixo, a face desce de 2,5 a 4,5 dp, perde altura, escurece e deixa de projetar a sombra elevada; a borda interna superior aparece acima dela. Os ombros e as teclas Start/Select também afundam. O direcional inclina e os analógicos acompanham os eixos. Ao soltar ou cancelar o gesto, o desenho volta ao estado neutro.

![Comparação entre botão solto e pressionado nos quatro consoles, em frames do renderer da View](images/realistic-pressure.png)

Não foram alterados o método de layout, as áreas ampliadas de toque, a seleção do controle mais próximo, os IDs/máscaras, o rastreamento de dedos, o editor, os protocolos de conexão ou os demais consoles. As bases decorativas seguem as posições e dimensões finais dos controles após personalização. A Activity continua em `sensorLandscape`, como antes; o teste com janela em formato retrato cobre os limites da View, sem adicionar um novo modo retrato ao app.

## Validação em 26/10/2026

- Compilação do app e do APK de testes com JDK 21, SDK 35 e Gradle 8.9.
- 30 testes JVM passaram, abrangendo input, máscaras/reports HID, transformação de layout, transporte e fila de reports.
- 9 testes Android passaram em Android 11/API 30 x86 virtual: 3 existentes e 6 novos em `RealisticControllerTest`.
- Modo clássico como padrão; preferência realista preservada ao recriar a View; mesmas áreas de toque nos dois modos; retorno pixel a pixel à aparência clássica ao desativar; entrada liberada ao alternar.
- Todos os botões dos quatro consoles foram pressionados e soltos via `MotionEvent`, conferindo suas máscaras.
- Direcional diagonal com um segundo dedo em botão, soltura independente e `ACTION_CANCEL` nos quatro consoles.
- PS1 com os dois analógicos e um terceiro dedo em botão, seguido de cancelamento.
- Limites das áreas de toque em Views de 2400×1080, 1920×1080, 1440×1080 e 1080×1920; liberação de entrada ao redimensionar ou abrir o editor.
- Renderização pressionada diferente da neutra e retorno pixel a pixel ao desenho original após cancelar, nos quatro consoles.
- Lint: zero erros e nove avisos em arquivos existentes; nenhum check foi desativado.
- Capturas do app no renderer Android em 1920×886, densidade 420 dpi, nas imagens deste README. A captura de abertura cinza foi descartada; as imagens mostram frames completos.

Comandos reproduzíveis (substitua o serial pelo seu emulador):

```sh
./gradlew :retropad:assembleDebug :retropad:assembleDebugAndroidTest :retropad:testDebugUnitTest :retropad:lintDebug
adb -s emulator-5558 install -r retropad/build/outputs/apk/debug/retropad-debug.apk
adb -s emulator-5558 install -r retropad/build/outputs/apk/androidTest/debug/retropad-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w -e class org.retrotv.pad.PadAndroidTest,org.retrotv.pad.RealisticControllerTest org.retrotv.pad.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 shell am start -W -S -n org.retrotv.pad/.PadActivity --es previewConsole SNES
```

O seletor `previewConsole` já existe apenas nas builds de depuração. Também aceita `NES`, `PS1` e `MD`. Aguarde o desenho completo antes de capturar a tela.

Para exportar pares de frames neutro/pressionado do renderer da View, acrescente `-e exportFrames 1` ao comando de instrumentação. Os PNGs ficam em `files/realistic-review` no diretório interno privado do app; em build debug, podem ser lidos com `adb exec-out run-as org.retrotv.pad cat files/realistic-review/snes-pressed.png`. Esses frames são desenhados em Canvas de bitmap; as imagens principais do README são capturas do app no renderer da janela Android.

Esta validação cobre compilação, renderer e interação local. O modo realista executa mais operações de desenho que o clássico; não há benchmark comparativo de FPS, latência, energia ou memória em celular físico, nem teste de conexão real celular–TV ou Bluetooth. Não há loop contínuo de animação. A fidelidade ao material das referências é aproximada por desenho vetorial, sem prometer igualdade fotográfica com os renders.
